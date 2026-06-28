/*
 * smoke_test.c — on-board end-to-end check of libmmfree (Phase C).
 *
 * Proves the resident-weights + per-projection-offset path that Phase D depends
 * on, WITHOUT any Python: pack a few small projections into per-port blobs, load
 * them ONCE into the resident weight udmabufs, register each at its byte offset,
 * run mmfree_bitlinear, and compare every output against a CPU reference matmul.
 * Exercises int16 signed activations, ternary weights, col-tile padding (odd M),
 * and — the whole point — two+ projections sharing one resident buffer.
 *
 * CLI mirrors bench.c so the same launch args/device paths work:
 *   smoke_test <core_phys> <dma_phys> <uio_dev> <act_udma> <wt_udma> <out_udma>
 * Multi-port geometry appends the port index to act/wt paths (-wt0.., -act0..)
 * and derives DMA i from 0xA0020000 + (i-1)*0x10000 (override MMFREE_DMA<i>),
 * exactly as bench.c does. Geometry defaults to the a16 engine; override with
 * MMFREE_AWIDTH / MMFREE_XDIM / MMFREE_MAXACC / MMFREE_MAXN / MMFREE_MAXM.
 *
 * Build natively on the KRIA:
 *   cd software && make lib
 *   cc -Iinclude -Iintegration/libmmfree integration/libmmfree/tests/smoke_test.c \
 *      -Lbuild -lmmfree -Wl,-rpath,'$ORIGIN' -o build/smoke_test
 *   sudo ./build/smoke_test 0xA0010000 0xA0000000 /dev/uio4 \
 *        /dev/udmabuf-act /dev/udmabuf-wt /dev/udmabuf-out
 */

#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "mmfree_runtime.h"   /* mmfree_geom_init, mmfree_geom_t */
#include "mmfree_lib.h"

/* ---- helpers ---- */

static uint32_t env_u32(const char *name, uint32_t dflt) {
    const char *e = getenv(name);
    return (e && *e) ? (uint32_t)strtoul(e, NULL, 0) : dflt;
}
static uint64_t env_u64(const char *name, uint64_t dflt) {
    const char *e = getenv(name);
    return (e && *e) ? strtoull(e, NULL, 0) : dflt;
}

/* tiny LCG — deterministic across runs (no Date/rand dependency) */
static uint64_t rng_state = 0x12345678u;
static uint32_t nextr(void) { rng_state = rng_state * 6364136223846793005ull + 1u; return (uint32_t)(rng_state >> 33); }

/* Pack 2-bit ternary codes {0,1,3}, 4 lanes/byte — identical to bench.c. */
static void pack_weights(const uint8_t *lanes, uint8_t *dst, uint32_t nbytes) {
    for (uint32_t b = 0; b < nbytes; b++) {
        uint8_t v = 0;
        for (int k = 0; k < 4; k++) v |= (uint8_t)((lanes[b * 4u + k] & 0x3u) << (k * 2));
        dst[b] = v;
    }
}

/* Resolve port path "<base><i>" unless MMFREE_<PFX><i> overrides — like bench.c. */
static const char *port_path(char *out, size_t n, const char *base, uint32_t i, const char *pfx) {
    char env[32];
    snprintf(env, sizeof(env), "MMFREE_%s%u", pfx, i);
    const char *e = getenv(env);
    if (e && *e) return e;
    snprintf(out, n, "%s%u", base, i);
    return out;
}

/* ---- projection set: small, incl. padded M and non-pow2 N ---- */
typedef struct { const char *name; uint32_t N, M; } shape_t;
static const shape_t SHAPES[] = {
    { "p0_exact",   64, 256 },   /* 1 col-tile, offset 0           */
    { "p1_padded", 100, 300 },   /* 2 tiles, 212 pad lanes, odd N  */
    { "p2_2tile",  128, 512 },   /* 2 exact tiles                  */
};
#define NSHAPES (sizeof(SHAPES) / sizeof(SHAPES[0]))

int main(int argc, char **argv) {
    if (argc != 7) {
        fprintf(stderr, "usage: %s <core_phys> <dma_phys> <uio_dev> <act_udma> <wt_udma> <out_udma>\n", argv[0]);
        return 2;
    }
    uint64_t    core_phys = strtoull(argv[1], NULL, 0);
    uint64_t    dma_phys0 = strtoull(argv[2], NULL, 0);
    const char *uio_dev   = argv[3];
    const char *act_udma  = argv[4];
    const char *wt_udma   = argv[5];
    const char *out_udma  = argv[6];

    mmfree_geom_t g;
    if (mmfree_geom_init(&g,
                         env_u32("MMFREE_AWIDTH", 16), env_u32("MMFREE_XDIM", 32),
                         env_u32("MMFREE_MAXACC", 4096), env_u32("MMFREE_MAXN", 4096),
                         env_u32("MMFREE_MAXM", 32000)) < 0) {
        fprintf(stderr, "bad geometry\n");
        return 2;
    }
    const uint32_t P = g.numPorts;
    const uint32_t olt = g.outLanesPerTile, lpp = olt / P, pb = g.portBytes;

    /* ---- compute per-projection offsets + total resident size ---- */
    uint64_t off[NSHAPES];
    size_t   blob[NSHAPES];
    uint64_t total = 0;
    uint32_t max_act_beats = 0, max_outlanes = 0;
    for (size_t i = 0; i < NSHAPES; i++) {
        uint32_t T = (SHAPES[i].M + olt - 1) / olt;
        off[i]  = total;
        blob[i] = (size_t)T * SHAPES[i].N * pb;
        total  += blob[i];
        if (SHAPES[i].N > max_act_beats)  max_act_beats = SHAPES[i].N;
        if (T * olt > max_outlanes)       max_outlanes  = T * olt;
    }

    /* ---- build cfg (mirror bench.c address/path conventions) ---- */
    mmfree_lib_cfg_t cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.aWidth = g.aWidth; cfg.xDim = g.xDim;
    cfg.maxAcc = g.maxAcc; cfg.maxN = g.maxN; cfg.maxM = g.maxM;
    cfg.core_phys = core_phys; cfg.core_size = 0x1000;
    cfg.num_dma = P;          cfg.dma_size  = 0x10000;
    cfg.uio_dev = uio_dev;
    cfg.dma_phys[0] = dma_phys0;
    for (uint32_t p = 1; p < P; p++) {
        char env[32];
        snprintf(env, sizeof(env), "MMFREE_DMA%u", p);
        cfg.dma_phys[p] = env_u64(env, 0xA0020000ull + (p - 1u) * 0x10000ull);
    }
    static char apath[MMFREE_MAX_DMA][256], wpath[MMFREE_MAX_DMA][256];
    for (uint32_t p = 0; p < P; p++) {
        cfg.act_dev[p] = (P > 1) ? port_path(apath[p], 256, act_udma, p, "ACT") : act_udma;
        cfg.wt_dev[p]  = (P > 1) ? port_path(wpath[p], 256, wt_udma,  p, "WT")  : wt_udma;
    }
    cfg.out_dev = out_udma;
    cfg.weight_bytes_per_port = total;
    cfg.act_bytes_per_port    = (size_t)max_act_beats * pb;
    cfg.out_bytes             = (size_t)max_outlanes * g.outLaneBytes;
    cfg.max_proj              = NSHAPES;

    printf("# smoke: aWidth=%u xDim=%u ports=%u lanes/tile=%u portBytes=%u accWidth=%u\n",
           g.aWidth, g.xDim, P, olt, pb, g.accWidth);
    printf("# resident weights: %llu B/port (%zu B total)\n",
           (unsigned long long)total, (size_t)total * P);

    mmfree_lib_t *h = mmfree_lib_open(&cfg);
    if (!h) { fprintf(stderr, "mmfree_lib_open failed\n"); return 1; }

    /* ---- generate weights, pack per-port host blobs, keep an int8 ref ---- */
    uint8_t  *host[MMFREE_MAX_DMA];
    int8_t   *wref[NSHAPES];
    for (uint32_t p = 0; p < P; p++) host[p] = calloc(1, total);
    uint8_t lanes[olt];   /* C99 VLA; olt is small */

    for (size_t s = 0; s < NSHAPES; s++) {
        uint32_t N = SHAPES[s].N, M = SHAPES[s].M, T = (M + olt - 1) / olt;
        wref[s] = malloc((size_t)N * M);
        for (uint32_t t = 0; t < T; t++) {
            for (uint32_t n = 0; n < N; n++) {
                for (uint32_t lane = 0; lane < olt; lane++) {
                    uint32_t m = t * olt + lane;
                    uint8_t code = 0;
                    if (m < M) {
                        uint32_t r = nextr() & 0x3u;       /* {0:0, 1:+1, 2:0, 3:-1} */
                        static const uint8_t c_map[4] = { 0, 1, 0, 3 };
                        static const int8_t  v_map[4] = { 0, 1, 0, -1 };
                        code = c_map[r];
                        wref[s][(size_t)n * M + m] = v_map[r];
                    }
                    lanes[lane] = code;
                }
                for (uint32_t p = 0; p < P; p++)
                    pack_weights(&lanes[p * lpp],
                                 &host[p][off[s] + (size_t)(t * N + n) * pb], pb);
            }
        }
    }

    /* ---- load resident weights ONCE, register each projection ---- */
    for (uint32_t p = 0; p < P; p++) {
        if (mmfree_lib_load_weights(h, p, host[p], total) < 0) {
            fprintf(stderr, "load_weights port %u failed\n", p);
            return 1;
        }
    }
    int id[NSHAPES];
    for (size_t s = 0; s < NSHAPES; s++) {
        id[s] = mmfree_register(h, off[s], SHAPES[s].N, SHAPES[s].M);
        if (id[s] < 0) { fprintf(stderr, "register %s failed\n", SHAPES[s].name); return 1; }
    }

    /* ---- run each projection twice (back-to-back resident reuse) + verify ---- */
    int total_errs = 0;
    int16_t *x   = malloc((size_t)max_act_beats * sizeof(int16_t));
    int32_t *acc = malloc((size_t)g.maxM * sizeof(int32_t));
    for (int rep = 0; rep < 2; rep++) {
        for (size_t s = 0; s < NSHAPES; s++) {
            uint32_t N = SHAPES[s].N, M = SHAPES[s].M;
            for (uint32_t n = 0; n < N; n++)
                x[n] = (int16_t)((int32_t)(nextr() % 4096u) - 2048);   /* [-2048,2047] */

            if (mmfree_bitlinear(h, id[s], x, acc) < 0) {
                fprintf(stderr, "%s: mmfree_bitlinear failed\n", SHAPES[s].name);
                total_errs++;
                continue;
            }
            int errs = 0; int64_t worst = 0;
            for (uint32_t m = 0; m < M; m++) {
                int64_t ref = 0;
                for (uint32_t n = 0; n < N; n++) ref += (int32_t)x[n] * (int32_t)wref[s][(size_t)n * M + m];
                int64_t diff = (int64_t)acc[m] - ref;
                if (diff) { errs++; if (llabs(diff) > llabs(worst)) worst = diff; }
            }
            printf("  [rep %d] %-10s N=%-4u M=%-4u off=%-7" PRIu64 " errs=%d%s\n",
                   rep, SHAPES[s].name, N, M, off[s], errs,
                   errs ? "   <-- FAIL" : "  ok");
            if (errs) { fprintf(stderr, "    worst diff=%" PRId64 "\n", worst); total_errs += errs; }
        }
    }

    printf("%s: %d total error(s)\n", total_errs ? "SMOKE FAIL" : "SMOKE PASS", total_errs);

    free(x); free(acc);
    for (uint32_t p = 0; p < P; p++) free(host[p]);
    for (size_t s = 0; s < NSHAPES; s++) free(wref[s]);
    mmfree_lib_close(h);
    return total_errs ? 1 : 0;
}
