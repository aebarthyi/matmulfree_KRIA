/*
 * cpu_bench.c — A53 NEON baseline for the MMfreeLM-370M projections.
 *
 * The companion to the FPGA bench (bench.c): runs the SAME four distinct
 * single-token projection shapes on the Cortex-A53 cores so the systolic
 * engine's numbers have a "best the CPU can do" reference to sit next to.
 *
 *   i/f/g/o_proj : 1024 x 1024    (4 per layer x 24 = 96 / token)
 *   gate_up      : 1024 x 5632    (24 / token)
 *   down_proj    : 2816 x 1024    (24 / token)
 *   lm_head      : 1024 x 32000   (1 / token)
 *
 * The math is the real BitLinear matmul: y[m] = sum_n W[n][m] * x[n], with
 * ternary weights W in {-1,0,+1} accumulated into int32. Three kernels (run
 * all by default; pick with CPU_MODE=a8|a16|p2|all):
 *
 *   a8  : int8 activations,  UNPACKED int8 weights  (vmull_s8 + vpadalq_s16)
 *   a16 : int16 activations, UNPACKED int8 weights  (vmlal_s16) — int16 is the
 *         HGRN recurrence's fixed-point precision; feeding it straight in skips
 *         the down-to-int8 requant.
 *   p2  : int16 activations, PACKED 2-bit weights — the apples-to-apples match
 *         to the FPGA, which streams the same 2-bit ternary (85 MB/token vs the
 *         unpacked kernels' 340 MB). The board run showed the unpacked CPU is
 *         purely DDR-bound at ~4 GB/s, so cutting weight bytes 4x is THE lever.
 *
 * GEMV layout: weights COLUMN-MAJOR so each output column's weights are
 * contiguous; the activation vector (<= 2816 elems) stays hot in L1 and is
 * reused across every column; each weight byte is read exactly once
 * (cache-optimal GEMV). Four output columns are blocked to share one
 * activation load and keep the in-order A53 NEON pipe fed; OpenMP fans the
 * column range across all cores. A53 is ARMv8.0 (no SDOT), so a widening
 * multiply is the fastest dense path even though the weights are ternary.
 *
 * Packed decode (p2): codes are 4-per-byte (2 bits, {0:+0,1:+1,2:+0,3:-1} —
 * the FPGA's c_map). Shift-and-mask extraction yields the 4 codes INTERLEAVED
 * across lanes; pairing them with a 4-way deinterleaving activation load
 * (vld4q_s16) makes the deinterleave order match the shift order for free, so
 * no weight shuffle is needed. Each code stream is decoded by a vqtbl1 table
 * lookup ({0,+1,0,-1}) then vmlal'd in. The dot product is order-independent,
 * which is what lets the deinterleaved pairing be correct.
 *
 * Build (native on the KRIA):  make cpu      (-> build/cpu_bench)
 * Run:                         ./build/cpu_bench
 * Env: CPU_THREADS (default = all cores), CPU_REPS (default 5, best-of),
 *      CPU_MODE (a8|a16|p2|all, default all), CPU_VERIFY=0 to skip check.
 */

#define _GNU_SOURCE
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <arm_neon.h>
#ifdef _OPENMP
#include <omp.h>
#endif

/* ---------- projection shapes (mirror bench.c's shapes_370m) ---------- */
typedef struct { const char *name; int N, M, count; } proj_t;
static const proj_t PROJS[] = {
    { "ifgo_proj", 1024,  1024, 96 },
    { "gate_up",   1024,  5632, 24 },
    { "down_proj", 2816,  1024, 24 },
    { "lm_head",   1024, 32000,  1 },
};

/* ---------- helpers ---------- */
static double now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC_RAW, &ts);
    return ts.tv_sec * 1e6 + ts.tv_nsec / 1e3;
}
static uint64_t next_rand(uint64_t *s) {
    uint64_t x = *s;
    x ^= x >> 12; x ^= x << 25; x ^= x >> 27;
    *s = x;
    return x * 0x2545F4914F6CDD1DULL;
}
static uint32_t env_u32(const char *name, uint32_t dflt) {
    const char *e = getenv(name);
    if (!e || !*e) return dflt;
    return (uint32_t)strtoul(e, NULL, 0);
}

/* 2-bit ternary code <-> value (matches the FPGA packing: +1->1, -1->3). */
static inline uint8_t ter_code(int v) { return v == 1 ? 1u : v == -1 ? 3u : 0u; }
static inline int     code_val(uint8_t c) { return c == 1 ? 1 : c == 3 ? -1 : 0; }

/* ---------- a8: int8 act, unpacked int8 weights ---------- */
static void gemv_cols_a8(const int8_t *restrict W, const int8_t *restrict x,
                         int32_t *restrict y, int N, int m0, int m1)
{
    int m = m0;
    for (; m + 4 <= m1; m += 4) {
        const int8_t *w0 = W + (size_t)(m+0)*N, *w1 = W + (size_t)(m+1)*N;
        const int8_t *w2 = W + (size_t)(m+2)*N, *w3 = W + (size_t)(m+3)*N;
        int32x4_t a0 = vdupq_n_s32(0), a1 = a0, a2 = a0, a3 = a0;
        int n = 0;
        for (; n + 16 <= N; n += 16) {
            int8x16_t xv = vld1q_s8(x + n);
            int8x8_t  xl = vget_low_s8(xv), xh = vget_high_s8(xv);
            int8x16_t wv;
            wv = vld1q_s8(w0+n); a0 = vpadalq_s16(a0, vmull_s8(xl, vget_low_s8(wv))); a0 = vpadalq_s16(a0, vmull_s8(xh, vget_high_s8(wv)));
            wv = vld1q_s8(w1+n); a1 = vpadalq_s16(a1, vmull_s8(xl, vget_low_s8(wv))); a1 = vpadalq_s16(a1, vmull_s8(xh, vget_high_s8(wv)));
            wv = vld1q_s8(w2+n); a2 = vpadalq_s16(a2, vmull_s8(xl, vget_low_s8(wv))); a2 = vpadalq_s16(a2, vmull_s8(xh, vget_high_s8(wv)));
            wv = vld1q_s8(w3+n); a3 = vpadalq_s16(a3, vmull_s8(xl, vget_low_s8(wv))); a3 = vpadalq_s16(a3, vmull_s8(xh, vget_high_s8(wv)));
        }
        int32_t s0 = vaddvq_s32(a0), s1 = vaddvq_s32(a1), s2 = vaddvq_s32(a2), s3 = vaddvq_s32(a3);
        for (; n < N; n++) { int32_t xn = x[n]; s0 += xn*w0[n]; s1 += xn*w1[n]; s2 += xn*w2[n]; s3 += xn*w3[n]; }
        y[m+0] = s0; y[m+1] = s1; y[m+2] = s2; y[m+3] = s3;
    }
    for (; m < m1; m++) {
        const int8_t *w = W + (size_t)m*N;
        int32x4_t a = vdupq_n_s32(0); int n = 0;
        for (; n + 16 <= N; n += 16) {
            int8x16_t xv = vld1q_s8(x+n), wv = vld1q_s8(w+n);
            a = vpadalq_s16(a, vmull_s8(vget_low_s8(xv),  vget_low_s8(wv)));
            a = vpadalq_s16(a, vmull_s8(vget_high_s8(xv), vget_high_s8(wv)));
        }
        int32_t s = vaddvq_s32(a);
        for (; n < N; n++) s += (int32_t)x[n] * w[n];
        y[m] = s;
    }
}

/* ---------- a16: int16 act, unpacked int8 weights ---------- */
static void gemv_cols_a16(const int8_t *restrict W, const int16_t *restrict x,
                          int32_t *restrict y, int N, int m0, int m1)
{
    int m = m0;
    for (; m + 4 <= m1; m += 4) {
        const int8_t *w0 = W + (size_t)(m+0)*N, *w1 = W + (size_t)(m+1)*N;
        const int8_t *w2 = W + (size_t)(m+2)*N, *w3 = W + (size_t)(m+3)*N;
        int32x4_t a0 = vdupq_n_s32(0), a1 = a0, a2 = a0, a3 = a0;
        int n = 0;
        for (; n + 8 <= N; n += 8) {
            int16x8_t xv = vld1q_s16(x + n);
            int16x4_t xl = vget_low_s16(xv), xh = vget_high_s16(xv);
            int16x8_t wv;
            wv = vmovl_s8(vld1_s8(w0+n)); a0 = vmlal_s16(a0, xl, vget_low_s16(wv)); a0 = vmlal_s16(a0, xh, vget_high_s16(wv));
            wv = vmovl_s8(vld1_s8(w1+n)); a1 = vmlal_s16(a1, xl, vget_low_s16(wv)); a1 = vmlal_s16(a1, xh, vget_high_s16(wv));
            wv = vmovl_s8(vld1_s8(w2+n)); a2 = vmlal_s16(a2, xl, vget_low_s16(wv)); a2 = vmlal_s16(a2, xh, vget_high_s16(wv));
            wv = vmovl_s8(vld1_s8(w3+n)); a3 = vmlal_s16(a3, xl, vget_low_s16(wv)); a3 = vmlal_s16(a3, xh, vget_high_s16(wv));
        }
        int32_t s0 = vaddvq_s32(a0), s1 = vaddvq_s32(a1), s2 = vaddvq_s32(a2), s3 = vaddvq_s32(a3);
        for (; n < N; n++) { int32_t xn = x[n]; s0 += xn*w0[n]; s1 += xn*w1[n]; s2 += xn*w2[n]; s3 += xn*w3[n]; }
        y[m+0] = s0; y[m+1] = s1; y[m+2] = s2; y[m+3] = s3;
    }
    for (; m < m1; m++) {
        const int8_t *w = W + (size_t)m*N;
        int32x4_t a = vdupq_n_s32(0); int n = 0;
        for (; n + 8 <= N; n += 8) {
            int16x8_t xv = vld1q_s16(x+n), wv = vmovl_s8(vld1_s8(w+n));
            a = vmlal_s16(a, vget_low_s16(xv),  vget_low_s16(wv));
            a = vmlal_s16(a, vget_high_s16(xv), vget_high_s16(wv));
        }
        int32_t s = vaddvq_s32(a);
        for (; n < N; n++) s += (int32_t)x[n] * w[n];
        y[m] = s;
    }
}

/* ---------- p2: int16 act, PACKED 2-bit weights ----------
 * Wp is column-major, N/4 bytes per column (4 codes/byte, LSB-first). One
 * 32-element block = 8 packed bytes per column + a vld4q_s16 of 32 acts whose
 * deinterleaved val[k] lines up with code stream k. */
static inline int32x4_t p2_accum(const uint8_t *p, int b,
                                  const int16x8x4_t *xq, int32x4_t acc, int8x16_t lut)
{
    const uint8x8_t m3 = vdup_n_u8(3);
    uint8x8_t pk = vld1_u8(p + b);                 /* 8 packed bytes = 32 codes */
    uint8x8_t c0 = vand_u8(pk, m3);                /* code stream k=0 (n%4==0) */
    uint8x8_t c1 = vand_u8(vshr_n_u8(pk, 2), m3);
    uint8x8_t c2 = vand_u8(vshr_n_u8(pk, 4), m3);
    uint8x8_t c3 = vshr_n_u8(pk, 6);
    int16x8_t w0 = vmovl_s8(vqtbl1_s8(lut, c0));   /* code -> {0,+1,0,-1} */
    int16x8_t w1 = vmovl_s8(vqtbl1_s8(lut, c1));
    int16x8_t w2 = vmovl_s8(vqtbl1_s8(lut, c2));
    int16x8_t w3 = vmovl_s8(vqtbl1_s8(lut, c3));
    acc = vmlal_s16(acc, vget_low_s16 (xq->val[0]), vget_low_s16 (w0));
    acc = vmlal_s16(acc, vget_high_s16(xq->val[0]), vget_high_s16(w0));
    acc = vmlal_s16(acc, vget_low_s16 (xq->val[1]), vget_low_s16 (w1));
    acc = vmlal_s16(acc, vget_high_s16(xq->val[1]), vget_high_s16(w1));
    acc = vmlal_s16(acc, vget_low_s16 (xq->val[2]), vget_low_s16 (w2));
    acc = vmlal_s16(acc, vget_high_s16(xq->val[2]), vget_high_s16(w2));
    acc = vmlal_s16(acc, vget_low_s16 (xq->val[3]), vget_low_s16 (w3));
    acc = vmlal_s16(acc, vget_high_s16(xq->val[3]), vget_high_s16(w3));
    return acc;
}

static void gemv_cols_p2(const uint8_t *restrict Wp, const int16_t *restrict x,
                         int32_t *restrict y, int N, int m0, int m1)
{
    static const int8_t lut_arr[16] = { 0, 1, 0, -1, 0,0,0,0, 0,0,0,0, 0,0,0,0 };
    int8x16_t lut = vld1q_s8(lut_arr);
    int Npb = N / 4;                                /* packed bytes per column */
    int m = m0;
    for (; m + 4 <= m1; m += 4) {
        const uint8_t *p0 = Wp + (size_t)(m+0)*Npb, *p1 = Wp + (size_t)(m+1)*Npb;
        const uint8_t *p2 = Wp + (size_t)(m+2)*Npb, *p3 = Wp + (size_t)(m+3)*Npb;
        int32x4_t a0 = vdupq_n_s32(0), a1 = a0, a2 = a0, a3 = a0;
        int n = 0, b = 0;
        for (; n + 32 <= N; n += 32, b += 8) {
            int16x8x4_t xq = vld4q_s16(x + n);       /* deinterleave 32 acts -> val[0..3] */
            a0 = p2_accum(p0, b, &xq, a0, lut);
            a1 = p2_accum(p1, b, &xq, a1, lut);
            a2 = p2_accum(p2, b, &xq, a2, lut);
            a3 = p2_accum(p3, b, &xq, a3, lut);
        }
        int32_t s0 = vaddvq_s32(a0), s1 = vaddvq_s32(a1), s2 = vaddvq_s32(a2), s3 = vaddvq_s32(a3);
        for (; n < N; n++) {                          /* N-tail (none for 370M shapes) */
            int32_t xn = x[n];
            s0 += xn*code_val((p0[n>>2] >> (2*(n&3))) & 3);
            s1 += xn*code_val((p1[n>>2] >> (2*(n&3))) & 3);
            s2 += xn*code_val((p2[n>>2] >> (2*(n&3))) & 3);
            s3 += xn*code_val((p3[n>>2] >> (2*(n&3))) & 3);
        }
        y[m+0] = s0; y[m+1] = s1; y[m+2] = s2; y[m+3] = s3;
    }
    for (; m < m1; m++) {                             /* M-tail: < 4 columns */
        const uint8_t *p = Wp + (size_t)m*Npb;
        int32x4_t a = vdupq_n_s32(0);
        int n = 0, b = 0;
        for (; n + 32 <= N; n += 32, b += 8) {
            int16x8x4_t xq = vld4q_s16(x + n);
            a = p2_accum(p, b, &xq, a, lut);
        }
        int32_t s = vaddvq_s32(a);
        for (; n < N; n++) s += (int32_t)x[n] * code_val((p[n>>2] >> (2*(n&3))) & 3);
        y[m] = s;
    }
}

/* ---------- OpenMP fan-out ---------- */
static void gemv_a8(const int8_t *W, const int8_t *x, int32_t *y, int N, int M) {
#ifdef _OPENMP
    #pragma omp parallel for schedule(static)
#endif
    for (int mb = 0; mb < M; mb += 4) gemv_cols_a8(W, x, y, N, mb, mb+4 <= M ? mb+4 : M);
}
static void gemv_a16(const int8_t *W, const int16_t *x, int32_t *y, int N, int M) {
#ifdef _OPENMP
    #pragma omp parallel for schedule(static)
#endif
    for (int mb = 0; mb < M; mb += 4) gemv_cols_a16(W, x, y, N, mb, mb+4 <= M ? mb+4 : M);
}
static void gemv_p2(const uint8_t *Wp, const int16_t *x, int32_t *y, int N, int M) {
#ifdef _OPENMP
    #pragma omp parallel for schedule(static)
#endif
    for (int mb = 0; mb < M; mb += 4) gemv_cols_p2(Wp, x, y, N, mb, mb+4 <= M ? mb+4 : M);
}

/* ---------- modes ---------- */
typedef enum { M_A8, M_A16, M_P2 } kmode_t;
typedef struct { kmode_t mode; const char *label; int act_bits; int wt_bytes_div; } modeinfo_t;
static const modeinfo_t MODES[] = {
    { M_A8,  "a8",  8,  1 },   /* int8 weights, 1 byte/weight */
    { M_A16, "a16", 16, 1 },
    { M_P2,  "p2",  16, 4 },   /* 2-bit weights, 1/4 byte/weight */
};

int main(void) {
    uint32_t reps   = env_u32("CPU_REPS", 5); if (!reps) reps = 1;
    int      verify = (int)env_u32("CPU_VERIFY", 1);
    uint32_t want_threads = env_u32("CPU_THREADS", 0);
    const char *mode_env = getenv("CPU_MODE");
    int threads = 1;
#ifdef _OPENMP
    if (want_threads) omp_set_num_threads((int)want_threads);
    #pragma omp parallel
    { _Pragma("omp single") threads = omp_get_num_threads(); }
#else
    (void)want_threads;
#endif

    /* pick modes */
    int run[3] = {1,1,1};
    if (mode_env && *mode_env && strcmp(mode_env, "all") != 0) {
        run[0] = run[1] = run[2] = 0;
        if      (!strcmp(mode_env, "a8"))  run[0] = 1;
        else if (!strcmp(mode_env, "a16")) run[1] = 1;
        else if (!strcmp(mode_env, "p2"))  run[2] = 1;
        else { fprintf(stderr, "CPU_MODE must be a8|a16|p2|all\n"); return 2; }
    }
    int need_packed = run[2];

    int csv = !isatty(STDOUT_FILENO);

    size_t maxN = 0, maxM = 0, maxWN = 0;
    for (size_t i = 0; i < sizeof(PROJS)/sizeof(PROJS[0]); i++) {
        if ((size_t)PROJS[i].N > maxN) maxN = PROJS[i].N;
        if ((size_t)PROJS[i].M > maxM) maxM = PROJS[i].M;
        size_t wn = (size_t)PROJS[i].N * PROJS[i].M;
        if (wn > maxWN) maxWN = wn;
    }
    int8_t  *W   = aligned_alloc(64, (maxWN + 63) & ~(size_t)63);
    uint8_t *Wp  = need_packed ? aligned_alloc(64, ((maxWN/4) + 63) & ~(size_t)63) : NULL;
    int8_t  *x8  = aligned_alloc(64, (maxN  + 63) & ~(size_t)63);
    int16_t *x16 = aligned_alloc(64, ((maxN*2) + 63) & ~(size_t)63);
    int32_t *y   = aligned_alloc(64, maxM * sizeof(int32_t));
    int32_t *yr  = verify ? malloc(maxM * sizeof(int32_t)) : NULL;
    if (!W || !x8 || !x16 || !y || (verify && !yr) || (need_packed && !Wp)) {
        fprintf(stderr, "alloc failed\n"); return 1;
    }

    printf("# cpu_bench: MMfreeLM-370M projections on A53 NEON\n");
    printf("# threads=%d  reps=%u (best-of)  verify=%d  acc=int32\n", threads, reps, verify);
    if (csv) printf("name,mode,N,M,us,gops,wt_GBps,errs\n");
    else {
        printf("\n%-10s %4s %5s %6s | %9s | %8s | %8s | %4s\n",
               "proj", "mode", "N", "M", "time_us", "GOPS", "wtGBps", "errs");
        printf("--------------------------------------------------------------------------\n");
    }

    double tok_us[3] = {0,0,0}; int tok_ok[3] = {1,1,1};

    for (size_t i = 0; i < sizeof(PROJS)/sizeof(PROJS[0]); i++) {
        int N = PROJS[i].N, M = PROJS[i].M;

        /* ternary weights (bench.c's 25/25/50 split), column-major int8 */
        uint64_t rng = ((uint64_t)N << 32) ^ (uint32_t)M ^ 0xC0FFEE00ULL;
        for (size_t k = 0; k < (size_t)N*M; k++) {
            switch (next_rand(&rng) & 0x3u) {
                case 1:  W[k] =  1; break;
                case 3:  W[k] = -1; break;
                default: W[k] =  0; break;
            }
        }
        if (need_packed) {                            /* pack each column's N codes -> N/4 bytes */
            int Npb = N / 4;
            for (int m = 0; m < M; m++) {
                const int8_t *col = W + (size_t)m*N;
                uint8_t *pc = Wp + (size_t)m*Npb;
                for (int k = 0; k < Npb; k++) {
                    uint8_t bb = 0;
                    for (int j = 0; j < 4; j++) bb |= (uint8_t)(ter_code(col[4*k+j]) << (2*j));
                    pc[k] = bb;
                }
            }
        }
        for (int n = 0; n < N; n++) {                 /* signed activations, full range */
            uint64_t r = next_rand(&rng);
            x8[n]  = (int8_t)(r & 0xFF);
            x16[n] = (int16_t)(r & 0xFFFF);
        }

        for (int mi = 0; mi < 3; mi++) {
            if (!run[mi]) continue;
            const modeinfo_t *mo = &MODES[mi];

            double best = 0;
            for (uint32_t r = 0; r < reps; r++) {
                double t0 = now_us();
                switch (mo->mode) {
                    case M_A8:  gemv_a8 (W,  x8,  y, N, M); break;
                    case M_A16: gemv_a16(W,  x16, y, N, M); break;
                    case M_P2:  gemv_p2 (Wp, x16, y, N, M); break;
                }
                double dt = now_us() - t0;
                if (r == 0 || dt < best) best = dt;
            }

            uint32_t errs = 0;
            if (verify) {
                for (int m = 0; m < M; m++) {
                    int32_t s = 0; const int8_t *w = W + (size_t)m*N;
                    for (int n = 0; n < N; n++)
                        s += (mo->act_bits == 8 ? (int32_t)x8[n] : (int32_t)x16[n]) * w[n];
                    if (y[m] != s) errs++;
                }
            }
            double gops    = 2.0 * (double)N * M / (best * 1e3);
            double wt_gbps = ((double)N * M / mo->wt_bytes_div) / (best * 1e3);
            if (errs) tok_ok[mi] = 0;
            tok_us[mi] += (double)PROJS[i].count * best;

            if (csv)
                printf("%s,%s,%d,%d,%.2f,%.3f,%.3f,%u\n", PROJS[i].name, mo->label, N, M, best, gops, wt_gbps, errs);
            else
                printf("%-10s %4s %5d %6d | %9.1f | %8.3f | %8.3f | %4u%s\n",
                       PROJS[i].name, mo->label, N, M, best, gops, wt_gbps, errs, errs ? "  FAIL" : "");
        }
    }

    printf("\n");
    for (int mi = 0; mi < 3; mi++)
        if (run[mi] && tok_ok[mi])
            printf("# per-token (%s): %.0f us -> %.1f tok/s\n",
                   MODES[mi].label, tok_us[mi], 1e6 / tok_us[mi]);

    free(W); free(Wp); free(x8); free(x16); free(y); free(yr);
    return 0;
}
