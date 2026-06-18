/*
 * bench.c — Ternary matmul benchmark sweep for the mmfree Core on KRIA.
 *
 * Two sweep modes (MMFREE_SHAPES env, default per BENCH_PRESET):
 *   pow2 : power-of-two N x M cross sweep within maxN / maxM (legacy)
 *   370m : every distinct projection of MMfreeLM-370M (i/f/g/o_proj, fused
 *          gate_up, down_proj, lm_head), plus a per-token cost summary and
 *          compute-phase efficiency vs the HP-port stream peak
 *          (MMFREE_CLK_MHZ, default 100, sets the peak).
 *
 * For each shape:
 *   1. Pack random activations + ternary weights into the prepared udmabuf
 *      buffers.
 *   2. LOAD_ACT, COMPUTE_MM, STORE_OUT — each timed end-to-end via
 *      clock_gettime(CLOCK_MONOTONIC_RAW).
 *   3. Verify against a tiny reference matmul on the host CPU (optional —
 *      controlled by MMFREE_VERIFY=1).
 *   4. Emit one result row per shape: aligned table on a terminal, CSV when
 *      piped/redirected (force with MMFREE_CSV=1/0).
 *
 * Physical addresses + UIO node are passed as CLI args so this binary can move
 * between board configurations without recompiling:
 *
 *   bench  <core_phys> <dma_phys> <uio_dev> <udmabuf_act> <udmabuf_wt> <udmabuf_out>
 *
 * Example (matches the bring-up guide's recommended addr map):
 *   bench  0xA0010000 0xA0000000  /dev/uio0  /dev/udmabuf0 /dev/udmabuf1 /dev/udmabuf2
 *
 * Multi-HP-port geometries (xDim*aWidth > 128, e.g. k26_bench32/64) split the
 * input stream across N DMAs/buffers. The CLI args stay base values: DMA i
 * defaults to 0xA0020000 + (i-1)*0x10000 (the bd.tcl map) and the act/wt
 * udmabuf paths get an index appended (/dev/udmabuf-wt -> /dev/udmabuf-wt0,
 * -wt1, ... — the names gen_overlay.tcl emits for N>1). Override per port
 * with MMFREE_DMA<i> / MMFREE_ACT<i> / MMFREE_WT<i> env vars. N==1 keeps the
 * exact legacy invocation. N itself comes from the geometry, like the RTL.
 *
 * No PetaLinux dependencies — just a stock Ubuntu-on-KRIA rootfs with the
 * `u-dma-buf` module loaded and a UIO binding for the Core's IRQ.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "mmfree_runtime.h"

/* ---------- default geometry (overridable at runtime) ----------
 * The geometry is now resolved at runtime into an mmfree_geom_t (env vars
 * MMFREE_AWIDTH / MMFREE_XDIM / MMFREE_MAXACC / MMFREE_MAXN / MMFREE_MAXM), so
 * one binary adapts to any array size without recompiling. These -D values are
 * only the defaults when the corresponding env var is unset; the Makefile's
 * BENCH_PRESET knob still sets them so `make BENCH_PRESET=...` behaves as
 * before. The resolved geometry MUST match the loaded bitstream's CoreConfig
 * (a mismatch wedges the core — caught by the completion timeout). */
#ifndef BENCH_AWIDTH
#define BENCH_AWIDTH        16
#endif
#ifndef BENCH_XDIM
#define BENCH_XDIM          4
#endif
#ifndef BENCH_MAX_ACC
#define BENCH_MAX_ACC       4096
#endif
#ifndef BENCH_MAX_N
#define BENCH_MAX_N         1024
#endif
#ifndef BENCH_MAX_M
#define BENCH_MAX_M         1024
#endif

/* Default sweep mode: the k26_mmfree370m preset compiles in the 370M
 * projection sweep; everything else keeps the pow2 cross sweep. MMFREE_SHAPES
 * (="pow2" | "370m") overrides at runtime. */
#ifdef BENCH_SHAPES_370M
#define BENCH_SHAPES_DEFAULT "370m"
#else
#define BENCH_SHAPES_DEFAULT "pow2"
#endif

/* Signed-activation default: the aWidth=16 signed preset compiles BENCH_SIGNED=1.
 * MMFREE_SIGNED=0/1 overrides at runtime. MUST match the bitstream's signedAct. */
#ifdef BENCH_SIGNED
#define BENCH_SIGNED_DEFAULT 1
#else
#define BENCH_SIGNED_DEFAULT 0
#endif

/* PL clock the bitstream was built with — sets the eff%% column's bandwidth
 * ceiling. Per-preset via the Makefile (250 for k26_mmfree370m since the
 * 2026-06 PL_CLK_MHZ bump); MMFREE_CLK_MHZ overrides at runtime. */
#ifndef BENCH_CLK_MHZ
#define BENCH_CLK_MHZ       100
#endif

/* ---------- helpers ---------- */

/* Output mode: CSV when piped/redirected (e.g. `| tee bench.csv`), aligned
 * table on a terminal. Override with MMFREE_CSV=1 (force CSV) or =0 (table). */
static int g_csv;

/* Theoretical weight-stream peak: numPorts x portBytes x PL clock. This is the
 * ceiling for cmp_GBps — the eff%% column reports how close each shape gets. */
static double g_peak_gbps;

/* Signed activations: set for the aWidth=16 signed preset (BENCH_SIGNED compile
 * default, MMFREE_SIGNED env override). The loaded bitstream's CoreConfig must
 * have signedAct=true or results mismatch on negative activations. */
static int g_signed;

static double now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC_RAW, &ts);
    return ts.tv_sec * 1e6 + ts.tv_nsec / 1e3;
}

static uint64_t next_rand(uint64_t *s) {
    /* xorshift64* — small and fast, deterministic per-shape. */
    uint64_t x = *s;
    x ^= x >> 12; x ^= x << 25; x ^= x >> 27;
    *s = x;
    return x * 0x2545F4914F6CDD1DULL;
}

/* Pack 2-bit ternary weights {0, 1, 3} into one s_axis beat of `nbytes` bytes,
 * 4 lanes per byte. Lane l sits at bit l*2, matching Chisel's Core.scala:
 * weightVec(i) = s_axis.tdata((i+1)*2-1, i*2). Written byte-wise straight into
 * the (volatile, Device-memory) udmabuf — every byte of the beat is written so
 * there is no stale padding. There are exactly nbytes*4 = outLanesPerTile lanes
 * per beat, so the whole beat is meaningful weight data. */
static inline void pack_weights(const uint8_t *lanes /* outLanesPerTile vals in {0,1,3} */,
                                volatile uint8_t *dst, uint32_t nbytes) {
    for (uint32_t b = 0; b < nbytes; b++) {
        uint8_t v = 0;
        for (int k = 0; k < 4; k++) {
            uint32_t lane = b * 4u + (uint32_t)k;
            v |= (uint8_t)((lanes[lane] & 0x3u) << (k * 2));
        }
        dst[b] = v;
    }
}

/* Write one activation beat of `nbytes`: the activation occupies the low
 * `abytes` (= aWidth/8) bytes little-endian, the rest of the port slice is
 * zeroed. `value` is written as two's complement, so signed activations (the
 * aWidth=16 signed preset) land correctly; for the small positive values the
 * unsigned presets use, this is identical to the old single-byte write. */
static inline void write_act_beat(volatile uint8_t *dst, uint32_t value,
                                  uint32_t abytes, uint32_t nbytes) {
    for (uint32_t b = 0; b < nbytes; b++)
        dst[b] = (b < abytes) ? (uint8_t)((value >> (b * 8)) & 0xFFu) : 0u;
}

/* Sign-extend an `accWidth`-bit accumulator that arrives right-aligned in an
 * outLaneWidth-bit container. Returns it as a signed 64-bit value (handles
 * accWidth up to 63). */
static inline int64_t expand_acc(uint64_t raw, uint32_t accWidth) {
    uint64_t mask = (accWidth >= 64) ? ~0ull : ((1ull << accWidth) - 1u);
    uint64_t sign = 1ull << (accWidth - 1u);
    raw &= mask;
    return (int64_t)((raw ^ sign) - sign);
}

/* Decimal digit count, for auto-sizing the N/M table columns. */
static int ndigits(uint32_t x) {
    int n = 1;
    while (x >= 10u) { x /= 10u; n++; }
    return n;
}

/* Read an environment variable as uint32, falling back to `dflt` when unset
 * or empty. Accepts 0x.. hex and decimal. */
static uint32_t env_u32(const char *name, uint32_t dflt) {
    const char *e = getenv(name);
    if (!e || !*e) return dflt;
    return (uint32_t)strtoul(e, NULL, 0);
}

static uint64_t env_u64(const char *name, uint64_t dflt) {
    const char *e = getenv(name);
    if (!e || !*e) return dflt;
    return strtoull(e, NULL, 0);
}

/* Resolve the device path for port `i` of a multi-port resource: the
 * MMFREE_<PFX><i> env var wins, else `<base><i>` (matching gen_overlay.tcl's
 * udmabuf-act0/-wt0/... node names). Only used when numPorts > 1. */
static const char *port_dev_path(char *out, size_t outsz, const char *base,
                                 uint32_t i, const char *env_prefix) {
    char env_name[32];
    snprintf(env_name, sizeof(env_name), "MMFREE_%s%u", env_prefix, i);
    const char *e = getenv(env_name);
    if (e && *e) return e;
    snprintf(out, outsz, "%s%u", base, i);
    return out;
}

/* Open a udmabuf and verify it is big enough. mmfree_buf_open silently clamps
 * the mapping to the node's real size, so an undersized device-tree node would
 * otherwise SIGBUS halfway through a weight fill instead of failing here. */
static int buf_open_checked(mmfree_buf_t *b, const char *path, size_t need) {
    if (mmfree_buf_open(b, path, need) < 0) return -1;
    if (b->size < need) {
        fprintf(stderr, "%s: udmabuf is %zu bytes but this sweep needs %zu — "
                "rebuild the overlay with bigger u-dma-buf nodes\n",
                path, b->size, need);
        return -1;
    }
    return 0;
}

/* Reference matmul on the host CPU. Slow but only used on verify. */
static void ref_matmul(const int32_t *act,       /* len N (signed) */
                       const int8_t *wt,         /* shape (N, M) row-major, vals {-1,0,+1} */
                       int32_t      *out,        /* len M */
                       uint32_t      N, uint32_t M)
{
    memset(out, 0, M * sizeof(int32_t));
    for (uint32_t n = 0; n < N; n++) {
        int32_t a = act[n];
        for (uint32_t m = 0; m < M; m++) {
            out[m] += a * (int32_t)wt[n * M + m];
        }
    }
}

/* ---------- sweep shapes ---------- */

typedef struct {
    const char *name;   /* projection label; NULL for plain sweep shapes */
    uint32_t    N, M;   /* inner dim x output dim */
    uint32_t    count;  /* instances per forward pass (token); 0 = n/a */
} bench_shape_t;

/* MMfreeLM-370M (hidden=1024, GLU intermediate=2816, vocab=32000, 24 layers):
 * every distinct BitLinear shape for single-token inference. All M are
 * multiples of the k26_mmfree370m geometry's 256-lane col tile, so no shape
 * carries padding lanes.
 *   i/f/g/o_proj : 1024 x 1024   (4 per layer x 24 layers)
 *   gate_up      : 1024 x 5632   (fused gate+up = 2 x 2816)
 *   down_proj    : 2816 x 1024
 *   lm_head      : 1024 x 32000  (vocab; untied from the embedding) */
static const bench_shape_t shapes_370m[] = {
    { "ifgo_proj", 1024,  1024, 96 },
    { "gate_up",   1024,  5632, 24 },
    { "down_proj", 2816,  1024, 24 },
    { "lm_head",   1024, 32000,  1 },
};

/* ---------- core bench loop ---------- */

typedef struct {
    mmfree_ctx_t ctx;
    mmfree_buf_t act_buf[MMFREE_MAX_DMA];   /* [0] real values, 1..N-1 all-zero */
    mmfree_buf_t wt_buf[MMFREE_MAX_DMA];    /* per-port 128-bit beat slices */
    mmfree_buf_t out_buf;                   /* single — output stays on DMA 0 */
} bench_state_t;

static int run_shape(bench_state_t *bs, const bench_shape_t *sh, int verify,
                     uint64_t seed, int iw,
                     double *tot_us_ret, double *cmp_us_ret)
{
    const mmfree_geom_t *g = &bs->ctx.geom;
    const uint32_t N = sh->N, M = sh->M;
    const char *name = sh->name ? sh->name : "-";

    /* Generate activations. Unsigned mode: small 0..15 (legacy bring-up).
     * Signed mode (g_signed, the aWidth=16 signed preset): a signed range that
     * exercises sign-extension, kept modest so the accumulator can't overflow
     * even at large N. Stored as int32 so the same array serves int8/int16. */
    const uint32_t abytes = g->aWidth / 8u;          /* activation bytes/beat */
    int32_t *act_int = malloc((size_t)N * sizeof(int32_t));
    int8_t  *wt_int  = NULL;
    int32_t *ref_out = NULL;
    uint8_t *lanes   = malloc(g->outLanesPerTile);  /* one col-tile of codes */
    if (verify) {
        wt_int  = malloc((size_t)N * M);
        ref_out = malloc((size_t)M * sizeof(int32_t));
    }

    uint64_t rng = seed;

    /* Fill activation udmabuf (port 0 only — ports 1..N-1 were zero-filled
     * once at startup; the activation value sits in the low aWidth bits of
     * the wide beat, which is port 0's slice). One beat slice of portBytes
     * per activation. The whole slice is written (udmabuf is uninitialized
     * Device memory — see the memset gotcha; byte stores are fine, only glibc
     * memset's dc-zva path SIGBUSes). */
    volatile uint8_t *act_dma = (volatile uint8_t *)bs->act_buf[0].vaddr;
    for (uint32_t i = 0; i < N; i++) {
        int32_t v = g_signed ? ((int32_t)(next_rand(&rng) % 4096u) - 2048)  /* [-2048,2047] */
                             : (int32_t)(next_rand(&rng) & 0xFu);           /* 0..15 */
        act_int[i] = v;
        write_act_beat(&act_dma[(size_t)i * g->portBytes], (uint32_t)v, abytes, g->portBytes);
    }

    /* Fill weight udmabufs. Layout MUST match Core.scala's COMPUTE_MM
     * consumption order: col-tile-major. Core processes one col tile at a
     * time, streaming N s_axis beats per tile, so each buffer is laid out as
     *   [t=0, n=0..N-1], [t=1, n=0..N-1], ..., [t=T-1, n=0..N-1]
     * Each wide beat packs outLanesPerTile ternary codes (2 bits each); lane l
     * (LSB-first) corresponds to output column m = t * outLanesPerTile + l.
     * Port p's buffer carries lanes [p*lanes_per_port, (p+1)*lanes_per_port)
     * — the CoreTop join Cats port 0 into the least-significant bits. */
    uint32_t num_col_tiles = (M + g->outLanesPerTile - 1) / g->outLanesPerTile;
    uint32_t lanes_per_port = g->outLanesPerTile / g->numPorts;
    volatile uint8_t *wt_dma[MMFREE_MAX_DMA];
    for (uint32_t p = 0; p < g->numPorts; p++)
        wt_dma[p] = (volatile uint8_t *)bs->wt_buf[p].vaddr;

    for (uint32_t t = 0; t < num_col_tiles; t++) {
        for (uint32_t n = 0; n < N; n++) {
            for (uint32_t lane = 0; lane < g->outLanesPerTile; lane++) {
                uint32_t m = t * g->outLanesPerTile + lane;
                uint8_t code = 0;
                if (m < M) {
                    uint64_t r = next_rand(&rng) & 0x3u;
                    /* {0:hold(0), 1:+1, 2:hold(0), 3:-1} → ternary set */
                    static const uint8_t  c_map[4] = { 0, 1, 0, 3 };
                    static const int8_t   v_map[4] = { 0, 1, 0, -1 };
                    code = c_map[r];
                    /* wt_int is N*M — padding lanes (m >= M) must not write
                     * it. Possible since bench32/64: sweep shapes can be
                     * smaller than one col tile (M=64 < 128/256 lanes). */
                    if (verify) wt_int[(size_t)n * M + m] = v_map[r];
                }
                lanes[lane] = code;
            }
            for (uint32_t p = 0; p < g->numPorts; p++) {
                pack_weights(&lanes[p * lanes_per_port],
                             &wt_dma[p][((size_t)t * N + n) * g->portBytes],
                             g->portBytes);
            }
        }
    }

    /* Run + time. Short phases carry tens of µs of IRQ/scheduler jitter per
     * shot (observed swinging short-op totals 2-3x between runs), so each
     * shape runs MMFREE_REPS times and the best COHERENT run (min total, with
     * its own phase split) is reported — that's the hardware's capability;
     * the jitter belongs to Linux, not the datapath. Default: 5 reps for the
     * named model shapes, 1 for the pow2 sweep (legacy behavior). */
    uint32_t n_outputs = num_col_tiles * g->outLanesPerTile;  /* padded to tile */
    uint32_t reps = env_u32("MMFREE_REPS", sh->name ? 5 : 1);
    if (reps == 0) reps = 1;

    double us_total = 0, us_load = 0, us_compute = 0, us_store = 0;
    for (uint32_t rep = 0; rep < reps; rep++) {
        double t0 = now_us();
        if (mmfree_load(&bs->ctx, bs->act_buf, N) < 0) goto fail;
        double t_load = now_us();
        if (mmfree_compute(&bs->ctx, bs->wt_buf, N, M) < 0) goto fail;
        double t_compute = now_us();
        if (mmfree_store(&bs->ctx, &bs->out_buf, n_outputs) < 0) goto fail;
        double t_store = now_us();

        double tot = t_store - t0;
        if (rep == 0 || tot < us_total) {
            us_total   = tot;
            us_load    = t_load    - t0;
            us_compute = t_compute - t_load;
            us_store   = t_store   - t_compute;
        }
    }

    /* Verify (the data is identical every rep — checking the last suffices). */
    uint32_t errs = 0;
    if (verify) {
        ref_matmul(act_int, wt_int, ref_out, N, M);
        volatile uint8_t *ob = (volatile uint8_t *)bs->out_buf.vaddr;
        for (uint32_t m = 0; m < M; m++) {
            uint64_t raw = (g->outLaneBytes == 4)
                         ? ((volatile uint32_t *)ob)[m]
                         : ((volatile uint64_t *)ob)[m];
            int64_t got      = expand_acc(raw, g->accWidth);
            int64_t expected = ref_out[m];
            if (got != expected) {
                if (errs < 8) {
                    fprintf(stderr, "  mismatch m=%u  got=%" PRId64
                            "  expected=%" PRId64 "\n", m, got, expected);
                }
                errs++;
            }
        }
    }

    /* The compute kernel does N*M multiply-add equivalents. Ternary multiplies
     * are essentially add/sub, so report "ops/s" rather than GFLOPS. */
    double ops   = 2.0 * (double)N * (double)M;   /* one mul + one add per element */
    double gops  = ops / (us_total * 1e3);        /* ops / (us_total * 1000 ns) = ops/ns = Gops/s */
    double gops_compute = ops / (us_compute * 1e3);

    /* DRAM bandwidth per op (bytes / ns = GB/s). The compute weight stream is
     * the dominant DDR4 consumer — watch cmp_GBps to see when the stream, not
     * the array, is the bottleneck. */
    double ld_bytes  = (double)N * g->sAxisBytes;
    double cmp_bytes = (double)N * (double)num_col_tiles * g->sAxisBytes;
    double st_bytes  = (double)n_outputs * g->outLaneBytes;
    double gbps_ld  = ld_bytes  / (us_load    * 1e3);
    double gbps_cmp = cmp_bytes / (us_compute * 1e3);
    double gbps_st  = st_bytes  / (us_store   * 1e3);
    double eff_pct  = g_peak_gbps > 0 ? 100.0 * gbps_cmp / g_peak_gbps : 0.0;

    if (tot_us_ret) *tot_us_ret = us_total;
    if (cmp_us_ret) *cmp_us_ret = us_compute;

    if (g_csv) {
        printf("%s,%u,%u,%.2f,%.2f,%.2f,%.2f,%.3f,%.3f,%.3f,%.3f,%.3f,%.1f,%u\n",
               name, N, M, us_load, us_compute, us_store, us_total,
               gops, gops_compute, gbps_ld, gbps_cmp, gbps_st, eff_pct, errs);
    } else {
        printf("%-10s %*u %*u | %8.1f %8.1f %8.1f %9.1f | %7.3f %8.3f | "
               "%7.3f %7.3f %7.3f | %5.1f%% | %4u%s\n",
               name, iw, N, iw, M, us_load, us_compute, us_store, us_total,
               gops, gops_compute, gbps_ld, gbps_cmp, gbps_st, eff_pct,
               errs, errs ? "  <-- FAIL" : "");
    }
    fflush(stdout);

    free(act_int);
    free(wt_int);
    free(ref_out);
    free(lanes);
    return errs == 0 ? 0 : -1;

fail:
    /* -2 = an op timed out / errored (state dump already printed by the
     * runtime). The core is likely wedged mid-FSM; the caller should abort
     * the sweep rather than time out on every remaining shape. */
    free(act_int);
    free(wt_int);
    free(ref_out);
    free(lanes);
    return -2;
}

/* Port-order self-test (numPorts > 1 only). Streams a tiny matmul whose +1
 * weights live ONLY in the lanes of the most-significant port slice. If the
 * BD wires the DMAs to the wrong CoreTop s_axis indices (a reversed Cat),
 * the nonzero results land in the wrong output columns — caught here in a
 * few ms instead of as silent corruption halfway through the sweep.
 * Returns 0 on pass. */
static int cat_order_selftest(bench_state_t *bs) {
    const mmfree_geom_t *g = &bs->ctx.geom;
    uint32_t nports = g->numPorts;
    uint32_t lanes_per_port = g->outLanesPerTile / nports;
    uint32_t hi_base = (nports - 1u) * lanes_per_port;  /* first lane of top port */
    uint32_t N = 2, M = g->outLanesPerTile;             /* one col tile, 2 rows */

    uint8_t *lanes = malloc(g->outLanesPerTile);
    if (!lanes) return -1;

    volatile uint8_t *act_dma = (volatile uint8_t *)bs->act_buf[0].vaddr;
    for (uint32_t i = 0; i < N; i++)
        write_act_beat(&act_dma[(size_t)i * g->portBytes], 1u, g->aWidth / 8u, g->portBytes);

    for (uint32_t lane = 0; lane < g->outLanesPerTile; lane++)
        lanes[lane] = (lane >= hi_base) ? 1u : 0u;      /* +1 in top slice only */
    for (uint32_t n = 0; n < N; n++) {
        for (uint32_t p = 0; p < nports; p++) {
            volatile uint8_t *dst = (volatile uint8_t *)bs->wt_buf[p].vaddr;
            pack_weights(&lanes[p * lanes_per_port],
                         &dst[(size_t)n * g->portBytes], g->portBytes);
        }
    }

    int rc = -1;
    if (mmfree_load(&bs->ctx, bs->act_buf, N) < 0) goto out;
    if (mmfree_compute(&bs->ctx, bs->wt_buf, N, M) < 0) goto out;
    if (mmfree_store(&bs->ctx, &bs->out_buf, M) < 0) goto out;

    rc = 0;
    volatile uint8_t *ob = (volatile uint8_t *)bs->out_buf.vaddr;
    for (uint32_t m = 0; m < M; m++) {
        uint64_t raw = (g->outLaneBytes == 4)
                     ? ((volatile uint32_t *)ob)[m]
                     : ((volatile uint64_t *)ob)[m];
        int64_t got      = expand_acc(raw, g->accWidth);
        int64_t expected = (m >= hi_base) ? (int64_t)N : 0;
        if (got != expected) {
            if (rc == 0)
                fprintf(stderr, "PORT-ORDER SELF-TEST FAILED: out[%u]=%" PRId64
                        " expected %" PRId64 " — DMA<->s_axis_i wiring or "
                        "MMFREE_WT<i> order is wrong\n", m, got, expected);
            rc = -1;
        }
    }
out:
    free(lanes);
    return rc;
}

/* ---------- main ---------- */

int main(int argc, char **argv) {
    if (argc != 7) {
        fprintf(stderr, "usage: %s <core_phys> <dma_phys> <uio_dev> <act_udma> <wt_udma> <out_udma>\n", argv[0]);
        return 2;
    }
    uint64_t    core_phys = strtoull(argv[1], NULL, 0);
    uint64_t    dma_phys  = strtoull(argv[2], NULL, 0);
    const char *uio_dev   = argv[3];
    const char *act_udma  = argv[4];
    const char *wt_udma   = argv[5];
    const char *out_udma  = argv[6];

    int verify = 1;
    const char *v = getenv("MMFREE_VERIFY");
    if (v && (v[0] == '0')) verify = 0;

    g_csv = !isatty(STDOUT_FILENO);
    const char *c = getenv("MMFREE_CSV");
    if (c) g_csv = (c[0] != '0');

    g_signed = (int)env_u32("MMFREE_SIGNED", BENCH_SIGNED_DEFAULT);

    /* Resolve the runtime geometry. Env vars override the compiled defaults
     * (which the Makefile's BENCH_PRESET still sets), so one binary targets any
     * array size — but it MUST match the loaded bitstream's CoreConfig. */
    mmfree_geom_t geom;
    if (mmfree_geom_init(&geom,
                         env_u32("MMFREE_AWIDTH", BENCH_AWIDTH),
                         env_u32("MMFREE_XDIM",   BENCH_XDIM),
                         env_u32("MMFREE_MAXACC", BENCH_MAX_ACC),
                         env_u32("MMFREE_MAXN",   BENCH_MAX_N),
                         env_u32("MMFREE_MAXM",   BENCH_MAX_M)) < 0) {
        fprintf(stderr, "bad geometry: xDim*aWidth must be a whole number of "
                        "bytes and no dimension may be zero\n");
        return 2;
    }

    /* Build the sweep up front — the udmabuf sizes below derive from it. */
    bench_shape_t sweep[128]; size_t nsweep = 0;
    const char *mode = getenv("MMFREE_SHAPES");
    if (!mode || !*mode) mode = BENCH_SHAPES_DEFAULT;
    int model_mode = (strcmp(mode, "370m") == 0);
    if (model_mode) {
        for (size_t k = 0; k < sizeof(shapes_370m) / sizeof(shapes_370m[0]); k++) {
            const bench_shape_t *s = &shapes_370m[k];
            if (s->N > geom.maxN || s->N > geom.maxAcc || s->M > geom.maxM) {
                fprintf(stderr, "skipping %s (%ux%u): exceeds geometry "
                        "maxN=%u maxAcc=%u maxM=%u\n", s->name, s->N, s->M,
                        geom.maxN, geom.maxAcc, geom.maxM);
                continue;
            }
            sweep[nsweep++] = *s;
        }
        if (nsweep == 0) {
            fprintf(stderr, "no 370m projection fits this geometry — use the "
                    "k26_mmfree370m preset (maxN=4096 maxM=32000)\n");
            return 2;
        }
    } else {
        /* Power-of-two cross sweep up to maxN x maxM (legacy behavior). */
        uint32_t ns[16], ms[16]; size_t nn = 0, nm = 0;
        for (uint32_t s = 64; s <= geom.maxN && nn < 16; s <<= 1) ns[nn++] = s;
        for (uint32_t s = 64; s <= geom.maxM && nm < 16; s <<= 1) ms[nm++] = s;
        if (nn == 0) ns[nn++] = geom.maxN;   /* tiny arrays: at least one */
        if (nm == 0) ms[nm++] = geom.maxM;
        for (size_t i = 0; i < nn; i++)
            for (size_t j = 0; j < nm && nsweep < 128; j++)
                sweep[nsweep++] = (bench_shape_t){ NULL, ns[i], ms[j], 0 };
    }

    /* Buffer requirements = worst case over the sweep, NOT over maxN x maxM —
     * the 370m sweep's weight worst case (lm_head: 2 MiB/port) is 4x smaller
     * than the geometry's theoretical maximum. */
    uint32_t act_beats = 0, wt_slices = 0, out_tiles = 0;
    for (size_t i = 0; i < nsweep; i++) {
        uint32_t t = (sweep[i].M + geom.outLanesPerTile - 1) / geom.outLanesPerTile;
        if (sweep[i].N > act_beats)         act_beats = sweep[i].N;
        if (sweep[i].N * t > wt_slices)     wt_slices = sweep[i].N * t;
        if (t > out_tiles)                  out_tiles = t;
    }

    /* Compute-phase bandwidth ceiling: every port streams portBytes per cycle. */
    uint32_t clk_mhz = env_u32("MMFREE_CLK_MHZ", BENCH_CLK_MHZ);
    g_peak_gbps = (double)geom.numPorts * geom.portBytes * clk_mhz / 1000.0;

    bench_state_t bs;
    memset(&bs, 0, sizeof(bs));
    /* fd=0 (stdin) is a valid descriptor — mark every buffer unopened so the
     * cleanup path can't close fds it doesn't own. */
    for (uint32_t p = 0; p < MMFREE_MAX_DMA; p++) {
        bs.act_buf[p].fd = -1;
        bs.wt_buf[p].fd  = -1;
    }
    bs.out_buf.fd = -1;
    int failed = 0;

    /* DMA register bases, one per s_axis port. Port 0 comes from the CLI;
     * ports 1..N-1 default to the bd.tcl address map (0xA0020000 + 0x10000
     * per extra DMA), overridable with MMFREE_DMA<i>. */
    uint32_t nports = geom.numPorts;
    uint64_t dma_phys_arr[MMFREE_MAX_DMA] = {0};
    dma_phys_arr[0] = dma_phys;
    for (uint32_t p = 1; p < nports; p++) {
        char env_name[32];
        snprintf(env_name, sizeof(env_name), "MMFREE_DMA%u", p);
        dma_phys_arr[p] = env_u64(env_name, 0xA0020000ull + (p - 1u) * 0x10000ull);
    }

    /* Core register window: 4 KiB is plenty (12-bit aperture).
     * AXI DMA register window: 64 KiB is a safe default for the Xilinx IP. */
    if (mmfree_open(&bs.ctx, core_phys, 0x1000, dma_phys_arr, nports, 0x10000, uio_dev) < 0) {
        fprintf(stderr, "mmfree_open failed\n");
        return 1;
    }
    bs.ctx.geom = geom;   /* the byte-count math + run_shape read this */

    /* Per-port act (act_beats beat slices) + wt (wt_slices beat slices)
     * buffers, sized to the sweep's worst shape. N==1 uses the CLI paths
     * as-is; N>1 appends the port index (matching gen_overlay.tcl's node
     * names) unless MMFREE_ACT<i> / MMFREE_WT<i> override. */
    for (uint32_t p = 0; p < nports; p++) {
        char apath[256], wpath[256];
        const char *ap = act_udma, *wp = wt_udma;
        if (nports > 1) {
            ap = port_dev_path(apath, sizeof(apath), act_udma, p, "ACT");
            wp = port_dev_path(wpath, sizeof(wpath), wt_udma,  p, "WT");
        }
        if (buf_open_checked(&bs.act_buf[p], ap, (size_t)act_beats * geom.portBytes) < 0) { failed = 1; goto out; }
        if (buf_open_checked(&bs.wt_buf[p],  wp, (size_t)wt_slices * geom.portBytes) < 0) { failed = 1; goto out; }
    }
    /* output buffer: out_tiles * outLanesPerTile lanes */
    if (buf_open_checked(&bs.out_buf, out_udma, (size_t)out_tiles * geom.outLanesPerTile * geom.outLaneBytes) < 0) { failed = 1; goto out; }

    /* Activation slices for ports 1..N-1 carry zeros forever (LOAD data lives
     * in the wide beat's low bits = port 0) — fill once. Word stores, NOT
     * glibc memset (dc-zva SIGBUSes on Device memory). */
    for (uint32_t p = 1; p < nports; p++) {
        volatile uint32_t *z = (volatile uint32_t *)bs.act_buf[p].vaddr;
        for (size_t k = 0; k < bs.act_buf[p].size / 4u; k++) z[k] = 0;
    }

    /* Resolved geometry — must match the loaded bitstream's CoreConfig. A
     * mismatch hangs the core mid-op (beat-count disagreement), so make it
     * easy to eyeball against the deployed preset. */
    printf("# bench geometry: aWidth=%u xDim=%u maxAcc=%u maxN=%u maxM=%u\n",
           geom.aWidth, geom.xDim, geom.maxAcc, geom.maxN, geom.maxM);
    printf("#   s_axis beat=%uB (%u port%s x %uB)  %u lanes/tile  accWidth=%u  out lane=%uB\n",
           geom.sAxisBytes, nports, nports == 1 ? "" : "s", geom.portBytes,
           geom.outLanesPerTile, geom.accWidth, geom.outLaneBytes);
    for (uint32_t p = 0; p < nports; p++) {
        printf("# dma[%u]=0x%lx  act paddr=0x%lx size=%zu  wt paddr=0x%lx size=%zu\n",
               p, dma_phys_arr[p],
               bs.act_buf[p].paddr, bs.act_buf[p].size,
               bs.wt_buf[p].paddr,  bs.wt_buf[p].size);
    }
    printf("# out_buf  paddr=0x%lx size=%zu\n", bs.out_buf.paddr, bs.out_buf.size);
    printf("# sweep=%s  PL clk=%u MHz  stream peak=%.2f GB/s (%u port%s x %u B/cycle)\n",
           mode, clk_mhz, g_peak_gbps, nports, nports == 1 ? "" : "s", geom.portBytes);
    printf("# verify=%d  activations=%s  timing=best-of-%u rep%s\n", verify,
           g_signed ? "signed" : "unsigned",
           env_u32("MMFREE_REPS", model_mode ? 5 : 1),
           env_u32("MMFREE_REPS", model_mode ? 5 : 1) == 1 ? "" : "s");

    /* For split streams, prove the DMA->s_axis_i wiring/order before the
     * sweep: a reversed port Cat corrupts results silently rather than
     * hanging, so it deserves a dedicated cheap check. */
    if (nports > 1) {
        if (cat_order_selftest(&bs) < 0) { failed = 1; goto out; }
        printf("# port-order self-test: PASS (%u ports)\n", nports);
    }

    /* N/M column width auto-sizes to the largest dimension so big arrays don't
     * blow the alignment. */
    int iw = 4;
    for (size_t i = 0; i < nsweep; i++) {
        uint32_t d = sweep[i].N > sweep[i].M ? sweep[i].N : sweep[i].M;
        if (ndigits(d) > iw) iw = ndigits(d);
    }

    if (g_csv) {
        printf("name,N,M,us_load,us_compute,us_store,us_total,"
               "gops_total,gops_compute,ld_GBps,cmp_GBps,st_GBps,cmp_eff_pct,errs\n");
    } else {
        printf("\n");
        printf("%-10s %*s %*s | %8s %8s %8s %9s | %7s %8s | %7s %7s %7s | %6s | %4s\n",
               "proj", iw, "N", iw, "M", "load_us", "comp_us", "store_us", "total_us",
               "GOPS", "GOPScmp", "ldGBps", "cmpGBps", "stGBps", "cmpEff", "errs");
        printf("%.*s\n", iw * 2 + 109,
               "--------------------------------------------------------------"
               "--------------------------------------------------------------"
               "--------------------------------------------------------------");
    }

    /* Per-token rollup (370m mode): time for ALL projections of one forward
     * pass = sum of count_i * t_i over the distinct shapes. */
    double tok_us = 0, tok_cmp_us = 0, tok_wt_bytes = 0;
    int tok_valid = model_mode;

    uint32_t prevN = 0;
    for (size_t i = 0; i < nsweep; i++) {
        if (!g_csv && i > 0 && sweep[i].N != prevN)
            printf("\n");   /* visual break between N groups */
        prevN = sweep[i].N;
        uint64_t seed = ((uint64_t)sweep[i].N << 32) ^ sweep[i].M ^ 0xC0FFEE00ULL;
        double us_tot = 0, us_cmp = 0;
        int rc = run_shape(&bs, &sweep[i], verify, seed, iw, &us_tot, &us_cmp);
        if (rc < 0) { failed++; tok_valid = 0; }
        if (rc == -2) {
            /* Op hang/error — the core is wedged; the remaining shapes
             * would each burn a full timeout. Bail with the dump above. */
            fprintf(stderr, "aborting sweep at N=%u M=%u (core wedged)\n",
                    sweep[i].N, sweep[i].M);
            goto out;
        }
        if (model_mode && rc == 0) {
            uint32_t t = (sweep[i].M + geom.outLanesPerTile - 1) / geom.outLanesPerTile;
            tok_us       += (double)sweep[i].count * us_tot;
            tok_cmp_us   += (double)sweep[i].count * us_cmp;
            tok_wt_bytes += (double)sweep[i].count * sweep[i].N * t * geom.sAxisBytes;
        }
    }

    if (tok_valid && tok_us > 0) {
        /* Floor = time to stream one token's weights at the HP-port peak. */
        double floor_us = tok_wt_bytes / (g_peak_gbps * 1e3);
        printf("\n# 370M per-token projection cost: %.0f us total, %.0f us compute-only"
               " -> %.1f tok/s (%.1f on compute alone)\n",
               tok_us, tok_cmp_us, 1e6 / tok_us, 1e6 / tok_cmp_us);
        printf("# weight traffic %.1f MiB/token; floor at %.2f GB/s stream peak = %.0f us"
               " -> token-level stream efficiency %.1f%% (compute phases only: %.1f%%)\n",
               tok_wt_bytes / (1024.0 * 1024.0), g_peak_gbps, floor_us,
               100.0 * floor_us / tok_us, 100.0 * floor_us / tok_cmp_us);
    }

out:
    mmfree_buf_close(&bs.out_buf);
    for (uint32_t p = 0; p < MMFREE_MAX_DMA; p++) {
        mmfree_buf_close(&bs.wt_buf[p]);
        mmfree_buf_close(&bs.act_buf[p]);
    }
    mmfree_close(&bs.ctx);
    return failed;
}
