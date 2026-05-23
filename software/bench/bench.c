/*
 * bench.c — Ternary matmul benchmark sweep for the mmfree Core on KRIA.
 *
 * Sweeps power-of-two shapes (N rows × M cols) within the K26_Bench config's
 * maxN / maxM bounds. For each shape:
 *   1. Pack random activations + ternary weights into the prepared udmabuf
 *      buffers.
 *   2. LOAD_ACT, COMPUTE_MM, STORE_OUT — each timed end-to-end via
 *      clock_gettime(CLOCK_MONOTONIC_RAW).
 *   3. Verify against a tiny reference matmul on the host CPU (optional —
 *      controlled by MMFREE_VERIFY=1).
 *   4. Emit one CSV row: shape, cycles, microseconds, GOPS, error_count.
 *
 * Physical addresses + UIO node are passed as CLI args so this binary can move
 * between board configurations without recompiling:
 *
 *   bench  <core_phys> <dma_phys> <uio_dev> <udmabuf_act> <udmabuf_wt> <udmabuf_out>
 *
 * Example (matches the bring-up guide's recommended addr map):
 *   bench  0xA0010000 0xA0000000  /dev/uio0  /dev/udmabuf0 /dev/udmabuf1 /dev/udmabuf2
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

#include "mmfree_runtime.h"

/* ---------- K26_Bench constants (must match CoreConfig) ---------- */
#define BENCH_AWIDTH        16
#define BENCH_BATCH         1
#define BENCH_XDIM          4
#define BENCH_MAX_ACC       4096
#define BENCH_NLANES        (BENCH_AWIDTH / 2)         /* 8 */
#define BENCH_OUT_LANES_PER_TILE  (BENCH_XDIM * BENCH_NLANES)         /* 32 */
#define BENCH_S_AXIS_BYTES        ((BENCH_XDIM * BENCH_AWIDTH) / 8)   /* 8  → 64-bit s_axis */
#define BENCH_OUT_ACC_WIDTH       (12 + BENCH_AWIDTH)                 /* log2(maxAcc) + aWidth = 28 */
#define BENCH_OUT_LANE_BYTES      4                                   /* outLaneWidth pads 28→32 */
#define BENCH_MAX_N         1024
#define BENCH_MAX_M         1024

/* DMA writes BENCH_OUT_LANE_BYTES per accumulator lane (32-bit beats since
 * outLaneWidth = nextPow2(outAccWidth) = 32). Each beat is a sign-extended
 * BENCH_OUT_ACC_WIDTH-bit accumulator. */
typedef int32_t bench_acc_t;

/* ---------- helpers ---------- */

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

/* Pack a 2-bit ternary weight {0, 1, 3} into the position (lane) of a 64-bit beat.
 * outLanesPerTile lanes per beat (xDim*nLanes; 32 at aWidth=16). Lane 0 sits at
 * the LSB, matching Chisel's Core.scala line ~235: weightVec(i) =
 *   s_axis.tdata((i+1)*2-1, i*2). */
static inline uint64_t pack_weights(const uint8_t *lanes /* outLanesPerTile vals in {0,1,3} */) {
    uint64_t w = 0;
    for (int i = 0; i < BENCH_OUT_LANES_PER_TILE; i++) {
        w |= ((uint64_t)(lanes[i] & 0x3u)) << (i * 2);
    }
    return w;
}

/* Cast 32-bit DMA accumulator beat to a signed value, sign-extending the
 * BENCH_OUT_ACC_WIDTH-bit accumulator that lives in the low bits. */
static inline int32_t expand_acc(uint32_t raw) {
    const int sh = 32 - BENCH_OUT_ACC_WIDTH;          /* 4 at aWidth=16 */
    int32_t v = (int32_t)(raw << sh) >> sh;
    return v;
}

/* Reference matmul on the host CPU. Slow but only used on verify. */
static void ref_matmul(const int8_t *act,        /* len N */
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

/* ---------- core bench loop ---------- */

typedef struct {
    mmfree_ctx_t ctx;
    mmfree_buf_t act_buf;
    mmfree_buf_t wt_buf;
    mmfree_buf_t out_buf;
} bench_state_t;

static int run_shape(bench_state_t *bs, uint32_t N, uint32_t M, int verify,
                     uint64_t seed)
{
    /* Generate activations: random 0..15 (smallish so overflow is impossible
     * even at N=1024 inner dim). */
    int8_t *act_int = malloc(N);
    int8_t *wt_int  = NULL;
    int32_t *ref_out = NULL;
    if (verify) {
        wt_int  = malloc((size_t)N * M);
        ref_out = malloc((size_t)M * sizeof(int32_t));
    }

    uint64_t rng = seed;

    /* Fill activation udmabuf. AXI DMA at 64 bits beat width, so each beat
     * is 8 bytes carrying one batchSize*aWidth = 2-byte activation in the low
     * two bytes (Chisel reads tdata(aWidth-1, 0)). Padding ignored. */
    volatile uint64_t *act_dma = (volatile uint64_t *)bs->act_buf.vaddr;
    for (uint32_t i = 0; i < N; i++) {
        uint16_t v = (uint16_t)(next_rand(&rng) & 0xFu);   /* keep small (0..15) for headroom */
        act_int[i] = (int8_t)v;
        act_dma[i] = (uint64_t)v;
    }

    /* Fill weight udmabuf. Layout MUST match Core.scala's COMPUTE_MM consumption
     * order: col-tile-major. Core processes one col tile at a time, streaming
     * N s_axis beats per tile, so the buffer is laid out as
     *   [t=0, n=0..N-1], [t=1, n=0..N-1], ..., [t=T-1, n=0..N-1]
     * Each beat is 64 bits packing outLanesPerTile=32 ternary codes; lane l
     * (LSBs) corresponds to output column m = t * outLanesPerTile + l. */
    uint32_t num_col_tiles = (M + BENCH_OUT_LANES_PER_TILE - 1) / BENCH_OUT_LANES_PER_TILE;
    volatile uint64_t *wt_dma = (volatile uint64_t *)bs->wt_buf.vaddr;

    uint8_t lanes[BENCH_OUT_LANES_PER_TILE];
    for (uint32_t t = 0; t < num_col_tiles; t++) {
        for (uint32_t n = 0; n < N; n++) {
            for (int lane = 0; lane < BENCH_OUT_LANES_PER_TILE; lane++) {
                uint32_t m = t * BENCH_OUT_LANES_PER_TILE + lane;
                uint8_t code; int8_t signed_val;
                if (m < M) {
                    uint64_t r = next_rand(&rng) & 0x3u;
                    /* {0:hold(0), 1:+1, 2:hold(0), 3:-1} → ternary set */
                    static const uint8_t  c_map[4] = { 0, 1, 0, 3 };
                    static const int8_t   v_map[4] = { 0, 1, 0, -1 };
                    code       = c_map[r];
                    signed_val = v_map[r];
                } else {
                    code = 0; signed_val = 0;
                }
                lanes[lane] = code;
                if (verify) wt_int[(size_t)n * M + m] = signed_val;
            }
            wt_dma[t * N + n] = pack_weights(lanes);
        }
    }

    /* Run + time. */
    uint32_t n_outputs = num_col_tiles * BENCH_OUT_LANES_PER_TILE;  /* padded to tile */

    double t0 = now_us();
    if (mmfree_load(&bs->ctx, &bs->act_buf, N) < 0) goto fail;
    double t_load = now_us();
    if (mmfree_compute(&bs->ctx, &bs->wt_buf, N, M) < 0) goto fail;
    double t_compute = now_us();
    if (mmfree_store(&bs->ctx, &bs->out_buf, n_outputs) < 0) goto fail;
    double t_store = now_us();

    /* Verify. */
    uint32_t errs = 0;
    if (verify) {
        ref_matmul(act_int, wt_int, ref_out, N, M);
        uint32_t *out_dma = (uint32_t *)bs->out_buf.vaddr;
        for (uint32_t m = 0; m < M; m++) {
            int32_t got      = expand_acc(out_dma[m]);
            int32_t expected = ref_out[m];
            if (got != expected) {
                if (errs < 8) {
                    fprintf(stderr, "  mismatch m=%u  got=%d  expected=%d\n",
                            m, got, expected);
                }
                errs++;
            }
        }
    }

    /* The compute kernel does N*M multiply-add equivalents. Ternary multiplies
     * are essentially add/sub, so report "ops/s" rather than GFLOPS. */
    double us_total   = t_store   - t0;
    double us_load    = t_load    - t0;
    double us_compute = t_compute - t_load;
    double us_store   = t_store   - t_compute;

    double ops   = 2.0 * (double)N * (double)M;   /* one mul + one add per element */
    double gops  = ops / (us_total * 1e3);        /* ops / (us_total * 1000 ns) = ops/ns = Gops/s */
    double gops_compute = ops / (us_compute * 1e3);

    printf("%4u,%4u,%9.2f,%9.2f,%9.2f,%9.2f,%8.3f,%8.3f,%u\n",
           N, M, us_load, us_compute, us_store, us_total,
           gops, gops_compute, errs);
    fflush(stdout);

    free(act_int);
    free(wt_int);
    free(ref_out);
    return errs == 0 ? 0 : -1;

fail:
    free(act_int);
    free(wt_int);
    free(ref_out);
    return -1;
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

    bench_state_t bs;
    memset(&bs, 0, sizeof(bs));
    int failed = 0;

    /* Core register window: 4 KiB is plenty (12-bit aperture).
     * AXI DMA register window: 64 KiB is a safe default for the Xilinx IP. */
    if (mmfree_open(&bs.ctx, core_phys, 0x1000, dma_phys, 0x10000, uio_dev) < 0) {
        fprintf(stderr, "mmfree_open failed\n");
        return 1;
    }
    if (mmfree_buf_open(&bs.act_buf, act_udma, BENCH_MAX_N * BENCH_S_AXIS_BYTES) < 0) goto out;
    /* weight buffer: maxN * numColTiles(maxM) * 4 bytes */
    uint32_t max_col_tiles = (BENCH_MAX_M + BENCH_OUT_LANES_PER_TILE - 1) / BENCH_OUT_LANES_PER_TILE;
    if (mmfree_buf_open(&bs.wt_buf, wt_udma,  BENCH_MAX_N * max_col_tiles * BENCH_S_AXIS_BYTES) < 0) goto out;
    /* output buffer: numColTiles * outLanesPerTile * 4 bytes */
    if (mmfree_buf_open(&bs.out_buf, out_udma, max_col_tiles * BENCH_OUT_LANES_PER_TILE * BENCH_OUT_LANE_BYTES) < 0) goto out;

    printf("# act_buf  paddr=0x%lx size=%zu\n", bs.act_buf.paddr, bs.act_buf.size);
    printf("# wt_buf   paddr=0x%lx size=%zu\n", bs.wt_buf.paddr,  bs.wt_buf.size);
    printf("# out_buf  paddr=0x%lx size=%zu\n", bs.out_buf.paddr, bs.out_buf.size);
    printf("# verify=%d\n", verify);
    printf("N,M,us_load,us_compute,us_store,us_total,gops_total,gops_compute,errs\n");

    /* Power-of-two sweep within the K26_Bench bounds. */
    static const uint32_t shapes[] = { 64, 128, 256, 512, 1024 };
    for (size_t i = 0; i < sizeof(shapes) / sizeof(shapes[0]); i++) {
        for (size_t j = 0; j < sizeof(shapes) / sizeof(shapes[0]); j++) {
            uint32_t N = shapes[i], M = shapes[j];
            if (N > BENCH_MAX_N || M > BENCH_MAX_M) continue;
            uint64_t seed = ((uint64_t)N << 32) ^ M ^ 0xC0FFEE00ULL;
            if (run_shape(&bs, N, M, verify, seed) < 0) failed++;
        }
    }

out:
    mmfree_buf_close(&bs.out_buf);
    mmfree_buf_close(&bs.wt_buf);
    mmfree_buf_close(&bs.act_buf);
    mmfree_close(&bs.ctx);
    return failed;
}
