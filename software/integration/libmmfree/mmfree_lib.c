/*
 * mmfree_lib.c — implementation of mmfree_lib.h (Phase C).
 *
 * Thin orchestration layer over mmfree_runtime: it owns the resident udmabufs
 * and a small projection table, and turns one mmfree_bitlinear() call into the
 * LOAD_ACT -> COMPUTE_MM@offset -> STORE_OUT sequence the bench validated.
 */

#define _GNU_SOURCE
#include <errno.h>
#ifdef _OPENMP
#include <omp.h>            /* readback threading: default thread count = omp_get_max_threads */
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "mmfree_lib.h"
#include "mmfree_runtime.h"
#include "mmfree_dma.h"     /* mmfree_dma_{mm2s,s2mm}_{start,wait} for the seq primitives */

/* Monotonic nanosecond clock for the per-phase timers. Reads go through the
 * vDSO (no syscall), so the ~6 reads per bitlinear call are cheap relative to
 * the IRQ round-trips they bracket. */
static inline uint64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

/* ---- readback threading knobs (see readback_rows_at) ----
 *
 * DEFAULT ON as of 2026-08-06, all cores. Measured on the board at b=6 against the same
 * binary with it off — one variable, same session, POWER=1:
 *
 *            readback us/call    decode tok/s       SOM avg W        tok/J
 *   370M     79.4 -> 22.3        103.30 -> 124.39   5.501 -> 5.498   +20.5%
 *   2.7B    128.6 -> 34.8         34.71 ->  38.55   5.956 -> 6.245    +5.9%
 *
 * The loop speeds up 3.7x in both cases — that is a property of the loop. What differs is
 * what it buys: on 370M, COMPUTE fell 20% as well, because readback overrun had been
 * spilling into the engine wait; on 2.7B compute is 392 us/call and dwarfs readback, so
 * there was no overrun to recover and the gain is the readback line alone.
 *
 * PERF/W IS NOT THE THROUGHPUT DELTA. On 370M the four cores cost nothing measurable, but on
 * 2.7B they cost 289 mW — they stream DDR alongside a much busier engine — which eats half
 * the gain: +11.1% throughput, only +5.9% tok/J. Measure watts per model; do not infer them.
 *
 * Bit-exactness is not assumed. `make gate` with MMFREE_RB_MIN_ELEMS=1 — which forces every
 * projection through the threaded path, not just the large ones — passes on both geometries:
 * 1160 invocations on 370M, 1544 on 2.7B, 0 element diffs.
 *
 *     MMFREE_RB_THREADS=N     threads for the readback loop; 1 = serial. Default: all cores.
 *                             Set 1 to A/B against the serial path on the same binary.
 *     MMFREE_RB_MIN_ELEMS=N   below N = b*M elements, stay serial regardless (default 4096).
 *                             Set 1 to force the threaded path everywhere — that is how the
 *                             gate reaches small projections, which at b=1 all fall below
 *                             the threshold and would otherwise take the serial branch.
 *
 * The threshold is load-bearing: at b=1, M=1024 the whole call is 2.2 us and the parallel
 * region alone is ~1.8, so threading it measured 0.55x. Small projections must stay serial.
 *
 * Any board measurement taken BEFORE this flipped is 11-20% low at b=6, including the
 * five-pass set in reports/final/. Those numbers are not wrong, they are different software
 * — re-measure rather than comparing across the flip.
 *
 * Both knobs are read once and cached: getenv on every projection call would itself be a
 * cost in a phase measured in microseconds. */
#define RB_COL_BLOCK 512u

static int rb_threads(void) {
    static int t = -1;
    if (t < 0) {
        const char *e = getenv("MMFREE_RB_THREADS");
        if (e && *e) {
            const long v = strtol(e, NULL, 0);
            t = (v < 1) ? 1 : (int)v;      /* 0 and 1 both mean serial */
        } else {
#ifdef _OPENMP
            t = omp_get_max_threads();     /* 4 on the K26's A53 cluster */
#else
            t = 1;                         /* built without -fopenmp: no threaded path */
#endif
        }
    }
    return t;
}

static size_t rb_min_elems(void) {
    static size_t n = 0;
    if (n == 0) {
        const char *e = getenv("MMFREE_RB_MIN_ELEMS");
        long v = (e && *e) ? strtol(e, NULL, 0) : 4096;
        n = (v <= 0) ? 4096u : (size_t)v;
    }
    return n;
}

/* Open one resident udmabuf and name the failure precisely.
 *
 * These three failures need three different fixes and used to print one message
 * ("too small/failed"), whose advice — regenerate the overlay with a bigger wt buffer —
 * is wrong for two of them:
 *   ENOENT  the device node/sysfs entry is not there at all. The preset's DT overlay is
 *           not loaded (or u-dma-buf is not inserted). Nothing about size. This is the
 *           common one after a reboot, and it cost a debugging round trip on 2026-08-06.
 *   other   present but unopenable — permissions (needs sudo), or the driver is wedged.
 *   short   present and opened, genuinely smaller than the model needs. Only here does
 *           the "regenerate with UDMABUF_WT_SZ=..." advice apply, so print both sizes.
 */
static int open_buf_checked(mmfree_buf_t *b, const char *dev, size_t need, int cached,
                            const char *what, int port) {
    char tag[64];
    if (port >= 0) snprintf(tag, sizeof(tag), "%s buf port %d", what, port);
    else           snprintf(tag, sizeof(tag), "%s buf", what);

    int rc = mmfree_buf_open(b, dev, need, cached);
    if (rc == -ENOENT) {
        fprintf(stderr, "mmfree_lib_open: %s (%s) does not exist — the preset's DT overlay "
                        "is not loaded.\n  Load it first: sudo make deploy-hw PRESET=<preset>"
                        "  (overlays do not survive a reboot; fclk0 does, so a stale 250 MHz "
                        "clock is not evidence the overlay is up.)\n", tag, dev);
        return -1;
    }
    if (rc < 0) {
        fprintf(stderr, "mmfree_lib_open: %s (%s) failed to open: %s\n",
                tag, dev, strerror(-rc));
        return -1;
    }
    if (b->size < need) {
        fprintf(stderr, "mmfree_lib_open: %s (%s) too small: %zu B, model needs %zu B\n",
                tag, dev, b->size, need);
        return -1;
    }
    return 0;
}

/* ---- pure-software helpers (no hardware; unit-tested on the host) ---- */

/* Write one activation beat: signed value in the low `abytes` bytes (two's
 * complement, little-endian), rest of the port slice zeroed. Mirrors
 * write_act_beat in bench.c. `dst` may be Device-memory udmabuf — byte stores
 * are safe there (unlike glibc memset's dc-zva path). */
void mmfree_pack_act_beat(volatile uint8_t *dst, int32_t value,
                          uint32_t abytes, uint32_t nbytes) {
    uint32_t u = (uint32_t)value;
    for (uint32_t b = 0; b < nbytes; b++)
        dst[b] = (b < abytes) ? (uint8_t)((u >> (b * 8)) & 0xFFu) : 0u;
}

/* Pack one batched LOAD beat: `batch` activations, row r in bytes
 * [r*abytes, (r+1)*abytes) (two's complement, little-endian), the tail
 * [batch*abytes, nbytes) zeroed. Row layout mirrors Core.scala sLoad's
 * tdata((r+1)*aWidth-1, r*aWidth) unpack across the joined ports. Caller
 * zero-pads vals[] for unused rows. Requires batch*abytes <= nbytes (holds when
 * batch <= xDim). Pure / board-free — unit-tested in tests/test_helpers.c. */
void mmfree_pack_act_beat_batch(volatile uint8_t *dst, const int32_t *vals,
                                uint32_t batch, uint32_t abytes, uint32_t nbytes) {
    for (uint32_t r = 0; r < batch; r++) {
        uint32_t u = (uint32_t)vals[r];
        for (uint32_t k = 0; k < abytes; k++)
            dst[r * abytes + k] = (uint8_t)((u >> (k * 8)) & 0xFFu);
    }
    for (uint32_t k = batch * abytes; k < nbytes; k++) dst[k] = 0u;
}

/* Sign-extend an `accWidth`-bit accumulator that arrives right-aligned in an
 * outLaneWidth-bit container. Mirrors expand_acc in bench.c. */
int64_t mmfree_expand_acc(uint64_t raw, uint32_t accWidth) {
    uint64_t mask = (accWidth >= 64) ? ~0ull : ((1ull << accWidth) - 1u);
    uint64_t sign = 1ull << (accWidth - 1u);
    raw &= mask;
    return (int64_t)((raw ^ sign) - sign);
}

/* Copy `n` bytes into a (possibly Device-memory) udmabuf via volatile stores,
 * 4 bytes at a time with a byte tail. Avoids the memset/memcpy dc-zva SIGBUS. */
static void dev_copy(volatile uint8_t *dst, const uint8_t *src, size_t n) {
    size_t i = 0;
    for (; i + 4 <= n; i += 4) {
        uint32_t w;
        memcpy(&w, src + i, 4);                      /* src is normal memory */
        *(volatile uint32_t *)(dst + i) = w;
    }
    for (; i < n; i++) dst[i] = src[i];
}

static void dev_zero(volatile uint8_t *dst, size_t n) {
    size_t i = 0;
    for (; i + 4 <= n; i += 4) *(volatile uint32_t *)(dst + i) = 0u;
    for (; i < n; i++) dst[i] = 0u;
}

/* ---- handle ---- */

typedef struct { uint64_t byte_offset; uint32_t N, M, n_outputs; } proj_t;

struct mmfree_lib {
    mmfree_ctx_t ctx;
    mmfree_buf_t wt[MMFREE_MAX_DMA];   /* resident weights, per port */
    mmfree_buf_t act[MMFREE_MAX_DMA];  /* activations, per port */
    mmfree_buf_t out;                  /* output accumulators (port 0 S2MM) */
    proj_t  *projs;
    uint32_t nproj, max_proj;
    int      open_bufs;                /* how many wt/act ports were opened */
    int      out_open;
    mmfree_lib_stats_t stats;          /* per-phase timers (mmfree_bitlinear) */
};

void mmfree_lib_get_stats(const mmfree_lib_t *h, mmfree_lib_stats_t *out) {
    if (h && out) *out = h->stats;
}

void mmfree_lib_reset_stats(mmfree_lib_t *h) {
    if (h) memset(&h->stats, 0, sizeof(h->stats));
}

uint32_t mmfree_lib_num_ports(uint32_t aWidth, uint32_t xDim) {
    mmfree_geom_t g;
    if (mmfree_geom_init(&g, aWidth, xDim, 4096, 4096, 32000) < 0) return 0;
    return g.numPorts;
}

size_t mmfree_lib_cfg_sizeof(void) { return sizeof(mmfree_lib_cfg_t); }

mmfree_lib_t *mmfree_lib_open(const mmfree_lib_cfg_t *cfg) {
    if (!cfg) return NULL;
    mmfree_lib_t *h = calloc(1, sizeof(*h));
    if (!h) return NULL;

    if (mmfree_open(&h->ctx, cfg->core_phys, cfg->core_size,
                    cfg->dma_phys, cfg->num_dma, cfg->dma_size, cfg->uio_dev) < 0) {
        fprintf(stderr, "mmfree_lib_open: mmfree_open failed\n");
        goto fail;
    }
    /* Override the seeded default geometry with the real one. */
    if (mmfree_geom_init(&h->ctx.geom, cfg->aWidth, cfg->xDim,
                         cfg->maxAcc, cfg->maxN, cfg->maxM) < 0) {
        fprintf(stderr, "mmfree_lib_open: bad geometry aWidth=%u xDim=%u\n",
                cfg->aWidth, cfg->xDim);
        goto fail;
    }
    if (h->ctx.geom.numPorts != cfg->num_dma) {
        fprintf(stderr, "mmfree_lib_open: num_dma=%u but geometry needs %u ports\n",
                cfg->num_dma, h->ctx.geom.numPorts);
        goto fail;
    }
    /* Batched bitstream support: geom_init seeded batch=1. A batched engine packs
     * batch activations into the low batch*aWidth bits of each LOAD beat, so it
     * must fit one port's slice — i.e. batch <= xDim (port math is xDim-derived;
     * see mmfree_geom_init note). */
    h->ctx.geom.batch  = cfg->batch ? cfg->batch : 1u;
    h->ctx.geom.packed = cfg->packed ? 1u : 0u;
    if (h->ctx.geom.batch > cfg->xDim) {
        fprintf(stderr, "mmfree_lib_open: batch=%u must be <= xDim=%u\n",
                h->ctx.geom.batch, cfg->xDim);
        goto fail;
    }

    /* Cross-check geometry against the loaded bitstream's preset manifest
     * ($MMFREE_MANIFEST), if set. WARN-only unless MMFREE_STRICT=1 (then fail). */
    if (mmfree_geom_check_env(&h->ctx.geom) < 0) {
        fprintf(stderr, "mmfree_lib_open: MMFREE_STRICT geometry/bitstream mismatch\n");
        goto fail;
    }

    const uint32_t P = h->ctx.geom.numPorts;
    for (uint32_t p = 0; p < P; p++) {
        /* Weights are written once (cold path) and ports 1..P-1 carry only the
         * static zero slice — leave those non-cached. Only act port 0 (re-packed
         * every op) and the output (drained every op) are hot, so map those
         * cacheable and sync them per call in mmfree_bitlinear. */
        if (open_buf_checked(&h->wt[p], cfg->wt_dev[p], cfg->weight_bytes_per_port,
                             /*cached=*/0, "weight", (int)p) < 0)
            goto fail;
        if (open_buf_checked(&h->act[p], cfg->act_dev[p], cfg->act_bytes_per_port,
                             /*cached=*/p == 0, "act", (int)p) < 0)
            goto fail;
        /* Every activation port slice is zero outside the low aWidth bits, and
         * ports 1..P-1 are zero entirely — clear once. Port 0 is cacheable and
         * per op we only rewrite the low aWidth bytes of each beat, relying on
         * these high-byte zeros staying put, so flush them to DDR once now. */
        dev_zero((volatile uint8_t *)h->act[p].vaddr, h->act[p].size);
        if (p == 0) mmfree_buf_sync_for_device(&h->act[0], h->act[0].size);
        h->open_bufs = (int)(p + 1);
    }
    if (open_buf_checked(&h->out, cfg->out_dev, cfg->out_bytes, /*cached=*/1, "out", -1) < 0)
        goto fail;
    h->out_open = 1;

    h->max_proj = cfg->max_proj ? cfg->max_proj : 128u;
    h->projs = calloc(h->max_proj, sizeof(proj_t));
    if (!h->projs) goto fail;

    /* Record the readback configuration in every run's output. It changes decode by 11-20%
     * at b=6, so a log that does not state it cannot be compared with one that does — and a
     * config visible only in the source is one nobody can verify after the fact. That is
     * exactly how a whole afternoon of Xeon rows got taken on the wrong SIMD build on
     * 2026-08-06: the line that should have identified the build printed the same string
     * either way. Cheap insurance, printed once per process. */
    printf("# readback: %s",
           rb_threads() > 1 ? "threaded" : "serial (1 thread)");
    if (rb_threads() > 1)
        printf(" x%d threads, threshold %zu elements (b*M below it stays serial)",
               rb_threads(), rb_min_elems());
#ifndef _OPENMP
    printf("  [built WITHOUT OpenMP — threading unavailable]");
#endif
    printf("\n");
    return h;

fail:
    mmfree_lib_close(h);
    return NULL;
}

void mmfree_lib_close(mmfree_lib_t *h) {
    if (!h) return;
    if (h->out_open) mmfree_buf_close(&h->out);
    for (int p = 0; p < h->open_bufs; p++) {
        mmfree_buf_close(&h->wt[p]);
        mmfree_buf_close(&h->act[p]);
    }
    mmfree_close(&h->ctx);
    free(h->projs);
    free(h);
}

int mmfree_lib_load_weights(mmfree_lib_t *h, uint32_t port,
                            const void *blob, size_t nbytes) {
    if (!h || port >= h->ctx.geom.numPorts) return -EINVAL;
    if (nbytes > h->wt[port].size) {
        fprintf(stderr, "load_weights: port %u blob %zu > buf %zu\n",
                port, nbytes, h->wt[port].size);
        return -ENOSPC;
    }
    dev_copy((volatile uint8_t *)h->wt[port].vaddr, (const uint8_t *)blob, nbytes);
    return 0;
}

int mmfree_lib_load_weights_file(mmfree_lib_t *h, uint32_t port, const char *path) {
    if (!h || port >= h->ctx.geom.numPorts) return -EINVAL;
    FILE *f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "load_weights: open %s: %s\n", path, strerror(errno)); return -errno; }

    uint8_t buf[64 * 1024];
    size_t off = 0, n;
    int rc = 0;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
        if (off + n > h->wt[port].size) {
            fprintf(stderr, "load_weights: %s exceeds port %u buf (%zu)\n",
                    path, port, h->wt[port].size);
            rc = -ENOSPC;
            break;
        }
        dev_copy((volatile uint8_t *)h->wt[port].vaddr + off, buf, n);
        off += n;
    }
    if (ferror(f)) rc = -EIO;
    fclose(f);
    return rc;
}

int mmfree_register(mmfree_lib_t *h, uint64_t byte_offset, uint32_t N, uint32_t M) {
    if (!h) return -EINVAL;
    if (h->nproj >= h->max_proj) { fprintf(stderr, "register: table full (%u)\n", h->max_proj); return -ENOSPC; }
    const mmfree_geom_t *g = &h->ctx.geom;
    if (N == 0 || N > g->maxAcc) { fprintf(stderr, "register: N=%u out of range (maxAcc=%u)\n", N, g->maxAcc); return -EINVAL; }
    if (M == 0 || M > g->maxM)   { fprintf(stderr, "register: M=%u out of range (maxM=%u)\n", M, g->maxM); return -EINVAL; }

    uint32_t col_tiles = (M + g->outLanesPerTile - 1) / g->outLanesPerTile;
    uint32_t n_outputs = col_tiles * g->outLanesPerTile;
    /* Validate the projection's weight slice fits the resident buffer (honors packed). */
    size_t blob_bytes = mmfree_wt_port_bytes(g, N, M);
    if (byte_offset + blob_bytes > h->wt[0].size) {
        fprintf(stderr, "register: offset %llu + %zu > weight buf %zu\n",
                (unsigned long long)byte_offset, blob_bytes, h->wt[0].size);
        return -ENOSPC;
    }
    /* The engine drains all `batch` PE rows per STORE, so the out buffer must
     * hold batch * n_outputs lanes (batch-major). */
    if ((size_t)g->batch * n_outputs * g->outLaneBytes > h->out.size) {
        fprintf(stderr, "register: batch(%u) * n_outputs(%u) exceeds out buf\n",
                g->batch, n_outputs);
        return -ENOSPC;
    }

    int id = (int)h->nproj++;
    h->projs[id] = (proj_t){ .byte_offset = byte_offset, .N = N, .M = M, .n_outputs = n_outputs };
    return id;
}

/* Scatter b activation rows (x[r*N + n], int16) into port-0 ACT beats (no cache
 * sync — caller does it). Row r occupies bytes [r*abytes,(r+1)*abytes) of each
 * beat, matching Core.scala sLoad's tdata((r+1)*aWidth-1, r*aWidth) unpack.
 * Only the b active rows are written: rows [b,B) are never read back and the
 * engine's PE rows are independent, so their slots are don't-care AND stay at
 * the open-time zero (nothing writes them) — bit-identical to the old zero-fill,
 * but cuts the scatter B:b. Shared by mmfree_bitlinear_batch and the seq driver. */
/* Sign-expand b rows of M accumulators from the OUT udmabuf into `acc` (row-major,
 * n_outputs stride per drained row -> M-stride packed output). OUT is a cached mapping
 * already invalidated by sync_for_cpu before this runs, so the fast path reads it as
 * normal memory (non-volatile) with a branchless 32-bit arithmetic-shift sign-extend the
 * compiler can auto-vectorize (NEON) — the per-element volatile read + mmfree_expand_acc
 * call blocked that. Bit-identical to mmfree_expand_acc for accWidth<=32 (the left shift
 * discards the same high garbage the mask did; the arithmetic >> sign-extends). The
 * volatile fallback covers non-cached / wider-than-32-bit lane mappings. */
/* General form: expand `pr`'s b rows into `acc`, whose rows are `acc_stride` wide, placing
 * this projection's columns at `col0`. The un-split case is (acc_stride=pr->M, col0=0); a
 * column-tile chunk passes the FULL projection's M as the stride and its own first column,
 * so chunks scatter straight into the caller's single [b][M] accumulator with no
 * reassembly step. */
static void readback_rows_at(mmfree_lib_t *h, const proj_t *pr, int32_t *acc, uint32_t b,
                             uint32_t acc_stride, uint32_t col0) {
    const mmfree_geom_t *g = &h->ctx.geom;
    if (h->out.cached && g->outLaneBytes == 4 && g->accWidth <= 32) {
        const uint32_t sh = 32u - g->accWidth;
        const uint32_t *ob = (const uint32_t *)h->out.vaddr;
#ifdef _OPENMP
        /* Readback is the one phase that still scales ~linearly with batch (13.1 us/row on
         * 370M, 21.1 on 2.7B) and the only one left running on a single core while the other
         * three idle. Measured on the board (bench_readback.c, b=6, cold source):
         *
         *     M=1024   12.0 -> 6.4 us (1.9x)      M=8192   108.1 -> 29.9 us (3.6x)
         *     M=2816   33.0 -> 12.6 us (2.6x)     M=32000  387.4 -> 195.6 us (2.0x)
         *
         * One core sustains a flat ~4.0 GB/s on this loop at every shape; four reach 8-13,
         * so the ceiling is cluster memory bandwidth, not cores — hence 32000 tops out at
         * 2x while 8192 gets 3.6x.
         *
         * The threshold is not optional. At b=1, M=1024 the whole call is 2.2 us and the
         * parallel region alone costs ~1.8 us, so threading it is a 0.55x SLOWDOWN. Small
         * projections must stay serial.
         *
         * Partitioning is (row x column block), not rows alone: rows alone would leave b=1
         * fully serial and would not divide 6 rows over 4 cores, which is the same
         * ceil(b/4) wave problem the host vector ops have. */
        const int nt = rb_threads();
        if (nt > 1 && (size_t)b * pr->M >= rb_min_elems()) {
            const uint32_t nblk = (pr->M + RB_COL_BLOCK - 1u) / RB_COL_BLOCK;
#pragma omp parallel for collapse(2) schedule(static) num_threads(nt)
            for (uint32_t i = 0; i < b; i++) {
                for (uint32_t cb = 0; cb < nblk; cb++) {
                    const uint32_t m0 = cb * RB_COL_BLOCK;
                    const uint32_t m1 = (m0 + RB_COL_BLOCK < pr->M) ? m0 + RB_COL_BLOCK : pr->M;
                    const uint32_t *row = ob + (size_t)i * pr->n_outputs;
                    int32_t *ar = acc + (size_t)i * acc_stride + col0;
                    for (uint32_t m = m0; m < m1; m++)
                        ar[m] = (int32_t)(row[m] << sh) >> sh;
                }
            }
            return;
        }
#endif
        for (uint32_t i = 0; i < b; i++) {
            const uint32_t *row = ob + (size_t)i * pr->n_outputs;
            int32_t *ar = acc + (size_t)i * acc_stride + col0;
            for (uint32_t m = 0; m < pr->M; m++)
                ar[m] = (int32_t)(row[m] << sh) >> sh;
        }
        return;
    }
    const volatile uint32_t *ob = (const volatile uint32_t *)h->out.vaddr;
    for (uint32_t i = 0; i < b; i++) {
        const volatile uint32_t *row = ob + (size_t)i * pr->n_outputs;
        int32_t *ar = acc + (size_t)i * acc_stride + col0;
        for (uint32_t m = 0; m < pr->M; m++)
            ar[m] = (int32_t)mmfree_expand_acc(row[m], g->accWidth);
    }
}

static void readback_rows(mmfree_lib_t *h, const proj_t *pr, int32_t *acc, uint32_t b) {
    readback_rows_at(h, pr, acc, b, pr->M, 0u);
}

static void pack_act_port0(mmfree_lib_t *h, const int16_t *x, uint32_t N, uint32_t b) {
    const mmfree_geom_t *g = &h->ctx.geom;
    const uint32_t abytes = g->aWidth / 8u;
    volatile uint8_t *a0 = (volatile uint8_t *)h->act[0].vaddr;
    if (h->act[0].cached && abytes == 2) {
        /* act[0] is a cached normal-memory mapping flushed by an explicit
         * sync_for_device, so plain (non-volatile) stores let the compiler
         * pipeline/unroll the strided int16 scatter. */
        int16_t *a0w = (int16_t *)h->act[0].vaddr;
        const uint32_t stride = g->portBytes / 2u;   /* int16 slots per beat */
        for (uint32_t n = 0; n < N; n++) {
            int16_t *beat = a0w + (size_t)n * stride;
            for (uint32_t r = 0; r < b; r++)
                beat[r] = x[(size_t)r * N + n];
        }
    } else {
        int32_t vals[MMFREE_MAX_DMA * 64];   /* batch <= xDim; ample headroom */
        for (uint32_t n = 0; n < N; n++) {
            for (uint32_t r = 0; r < b; r++)
                vals[r] = (int32_t)x[(size_t)r * N + n];
            mmfree_pack_act_beat_batch(&a0[(size_t)n * g->portBytes], vals, b,
                                       abytes, g->portBytes);
        }
    }
}

int mmfree_bitlinear_batch(mmfree_lib_t *h, int proj_id,
                           const int16_t *x, int32_t *acc, uint32_t b) {
    if (!h || proj_id < 0 || (uint32_t)proj_id >= h->nproj || !x || !acc) return -EINVAL;
    const proj_t *pr = &h->projs[proj_id];
    const mmfree_geom_t *g = &h->ctx.geom;
    const uint32_t B = g->batch;            /* PE rows the bitstream loads/drains */
    if (b == 0 || b > B) {
        fprintf(stderr, "bitlinear_batch: b=%u out of range 1..%u\n", b, B);
        return -EINVAL;
    }

    uint64_t t0 = now_ns();

    /* 1. Pack the b active activation rows into port 0 (see pack_act_port0). */
    pack_act_port0(h, x, pr->N, b);
    uint64_t tps = now_ns();
    /* act port 0 is cacheable: clean the beats we just wrote so the MM2S DMA
     * reads them from DDR (no-op if the buffer is non-cached). */
    mmfree_buf_sync_for_device(&h->act[0], (size_t)pr->N * g->portBytes);
    uint64_t t1 = now_ns();

    /* 2. LOAD_ACT (N B-wide beats) -> 3. COMPUTE_MM (weights broadcast to all B
     *    rows) -> 4. STORE_OUT (B*n_outputs lanes — the engine drains every row).
     *    Each blocks on its own completion IRQ. */
    if (mmfree_load(&h->ctx, h->act, pr->N) < 0)                               return -EIO;
    uint64_t t2 = now_ns();
    /* flags=b: the engine drains only the b active rows, so a b<B call costs
     * ~B1 (the weight stream is batch-count-independent). Legacy bitstreams that
     * don't decode flags fall back to a full-B drain, which stays correct since
     * we store/read only the first b rows below. */
    if (mmfree_compute_off_b(&h->ctx, h->wt, pr->N, pr->M, pr->byte_offset, b) < 0) return -EIO;
    uint64_t t3 = now_ns();
    /* Drain wrote rows 0..b-1 (contiguous, row-major); store/read only those. */
    const uint32_t total_outputs = b * pr->n_outputs;
    if (mmfree_store(&h->ctx, &h->out, total_outputs) < 0)                     return -EIO;
    uint64_t tos = now_ns();
    /* Output is cacheable: invalidate the drained lanes so the readback sees the
     * S2MM result from DDR rather than stale cache. */
    mmfree_buf_sync_for_cpu(&h->out, (size_t)total_outputs * g->outLaneBytes);
    uint64_t t4 = now_ns();

    /* 5. Sign-expand the first M lanes of each requested row. Output is
     *    batch-major: row i occupies lanes [i*n_outputs, i*n_outputs + M)
     *    (Core.scala drain rowAddr = (batch*numColTiles + colTile)*outSubBeats). */
    readback_rows(h, pr, acc, b);
    uint64_t t5 = now_ns();

    h->stats.calls++;
    h->stats.pack_ns     += t1 - t0;
    h->stats.act_sync_ns += t1 - tps;
    h->stats.load_ns     += t2 - t1;
    h->stats.compute_ns  += t3 - t2;
    h->stats.store_ns    += t4 - t3;
    h->stats.out_sync_ns += t4 - tos;
    h->stats.readback_ns += t5 - t4;
    return 0;
}

int mmfree_bitlinear(mmfree_lib_t *h, int proj_id,
                     const int16_t *x, int32_t *acc) {
    return mmfree_bitlinear_batch(h, proj_id, x, acc, 1u);
}

/* ---- column-tile splitting -------------------------------------------------
 * A projection's weight blob is COL-TILE-MAJOR: per port, the byte at
 * (t*N + n)*portBytes carries the lanes of output columns [t*olt, (t+1)*olt) for
 * inner position n (see mmfree_pack's pack_projection). So a contiguous range of
 * output column tiles is a contiguous byte range, and a "chunk" is just this
 * projection with a shifted byte_offset and a smaller M — no re-packing, no new
 * blob, no manifest change.
 *
 * Why bother: every chunk reads the SAME activations, so one pack + one LOAD_ACT
 * serves them all (the RTL keeps actBram across COMPUTEs — CoreSpec "split:
 * per-column-tile-chunk COMPUTEs on one LOAD == the whole projection"). That turns a
 * single serialized projection into a self-pipeline: chunk j-1's readback runs on the
 * CPU while the engine computes chunk j. readback is ~28% of engine time on the big
 * projections and was pure dead time before.
 *
 * Chunk boundaries are constrained by the blob encoding, NOT chosen freely:
 *  - 2-bit: offset = t*N*portBytes. Always exact and beat-aligned. Any boundary works.
 *  - 1.6-bit: trits pack 5/byte over the flat [t][n][lane] stream, so tile t starts at
 *    trit t*N*olt -> byte t*N*olt/5. That is only a valid DMA start when it divides
 *    exactly AND lands on a wire beat (then per-port offset = flat/numPorts, since
 *    port 0 holds the beat LSBs). e.g. N=2560 gives 131072 B/tile — any boundary is
 *    fine; N=6912 only allows boundaries at multiples of 5 tiles. chunk_offset()
 *    returns <0 for an illegal boundary and the caller drops to fewer chunks.
 */
/* Defined with the seq primitives below (the split driver reuses them verbatim). */
static int compute_issue_pr(mmfree_lib_t *h, const proj_t *pr, uint32_t b);
static int store_issue_pr(mmfree_lib_t *h, const proj_t *pr, uint32_t b);
static int store_wait_pr(mmfree_lib_t *h, const proj_t *pr, uint32_t b, uint64_t *sync_ns);

static int chunk_offset(const mmfree_geom_t *g, uint32_t N, uint32_t t, uint64_t *out) {
    if (!g->packed) {
        *out = (uint64_t)t * N * g->portBytes;
        return 0;
    }
    uint64_t trits = (uint64_t)t * N * g->outLanesPerTile;
    if (trits % 5u) return -1;                       /* not a whole byte boundary */
    uint64_t flat = trits / 5u;
    if (flat % g->sAxisBytes) return -1;             /* not a wire-beat boundary */
    *out = flat / g->numPorts;
    return 0;
}

/* Build the proj_t view of tiles [t0, t0+tiles) of `pr`. M is clamped to the real
 * column count so the LAST chunk of a non-tile-aligned projection asks the engine for
 * exactly the columns that exist; n_outputs stays tile-rounded (what the engine drains). */
static int chunk_view(const mmfree_geom_t *g, const proj_t *pr,
                      uint32_t t0, uint32_t tiles, proj_t *out) {
    const uint32_t olt = g->outLanesPerTile;
    uint64_t off;
    if (chunk_offset(g, pr->N, t0, &off) < 0) return -1;
    uint32_t first = t0 * olt;
    uint32_t span = tiles * olt;
    out->byte_offset = pr->byte_offset + off;
    out->N = pr->N;
    out->M = (first + span <= pr->M) ? span : (pr->M - first);
    out->n_outputs = span;
    return 0;
}

/* Bounds the on-stack boundary array. The ladder only ever asks for <=8 and the k-sweep
 * never went past 16, so this is slack, not a real limit. */
#define SPLIT_MAX_CHUNKS 64u

/* Tile boundaries for a split, shared by the split driver and mmfree_split_plan so the plan
 * you can inspect is by construction the plan that runs. Fills bnd[0..return] with
 * bnd[0]=0 .. bnd[n]=tiles, strictly increasing; chunk j spans [bnd[j], bnd[j+1]).
 *
 * Each interior boundary starts at the EVEN split point `j*(tiles/want)` and, if that tile
 * is not a legal DMA start on this blob, SNAPS outward to the nearest legal tile. When every
 * even boundary is already legal the snap is a no-op at distance 0, so the 2-bit path and
 * 2.7B-packed are byte-identical to the previous even-split behaviour (their measured
 * numbers stay valid).
 *
 * Snapping replaces a halve-until-legal loop that gave up at k=1. It matters because with
 * olt=256 (== 1 mod 5) legality reduces to t*N == 0 mod 5, so a hidden dim not divisible by
 * 5 only permits t == 0 mod 5:
 *   370M gate_proj (N=1024, 22 tiles, want 2): even boundary 11 illegal -> was k=1, snaps to 10 -> k=2
 *   1.3B gate_proj (N=2048, 44 tiles, want 4): even 11/22/33 illegal   -> was k=1, snaps      -> k=4
 * lm_head is unaffected either way — 125/8 = 15 is already a multiple of 5 — and down_proj /
 * o_proj are k=1 by the ladder regardless, so gate_proj (~41% of 370M weight traffic) is
 * exactly what this recovers. Uneven chunks are already a supported case (CoreSpec pins
 * "deliberately UNEVEN chunks, since boundaries come from blob alignment not evenness").
 *
 * Returns the achieved chunk count, which can be less than `want` if snapping collapses
 * adjacent boundaries; 1 means no split. */
static uint32_t chunk_boundaries(const mmfree_geom_t *g, const proj_t *pr,
                                 uint32_t tiles, uint32_t want, uint32_t *bnd) {
    bnd[0] = 0;
    if (want > SPLIT_MAX_CHUNKS) want = SPLIT_MAX_CHUNKS;
    const uint32_t per = (want >= 2u) ? tiles / want : 0u;
    if (per == 0u) { bnd[1] = tiles; return 1u; }   /* fewer tiles than chunks, or want<2 */

    uint32_t n = 1;
    for (uint32_t j = 1; j < want; j++) {
        const uint32_t ideal = j * per;
        uint32_t found = 0;
        /* Search outward from the even point, nearer candidates first, so chunks stay as
         * even as legality permits. d==0 is the even point itself. */
        for (uint32_t d = 0; d < tiles && !found; d++) {
            uint32_t cand[2];
            uint32_t nc = 0;
            if (ideal >= d)                cand[nc++] = ideal - d;
            if (d != 0u && ideal + d < tiles) cand[nc++] = ideal + d;
            for (uint32_t c = 0; c < nc; c++) {
                const uint32_t t = cand[c];
                uint64_t dummy;
                if (t <= bnd[n - 1] || t >= tiles) continue;   /* keep strictly increasing */
                if (chunk_offset(g, pr->N, t, &dummy) < 0) continue;
                bnd[n++] = t;
                found = 1;
                break;
            }
        }
    }
    bnd[n] = tiles;
    return n;
}

int mmfree_split_plan(const mmfree_lib_t *h, int proj_id, uint32_t want_chunks,
                      mmfree_split_chunk_t *out, uint32_t max) {
    if (!h || proj_id < 0 || (uint32_t)proj_id >= h->nproj || !out || max == 0) return -EINVAL;
    const proj_t *pr = &h->projs[proj_id];
    const mmfree_geom_t *g = &h->ctx.geom;
    const uint32_t olt = g->outLanesPerTile;
    const uint32_t tiles = (pr->M + olt - 1) / olt;
    uint32_t bnd[SPLIT_MAX_CHUNKS + 1u];
    const uint32_t k = (want_chunks < 2u) ? 1u : chunk_boundaries(g, pr, tiles, want_chunks, bnd);

    uint32_t n = 0;
    for (uint32_t j = 0; j < k && n < max; j++) {
        uint32_t ct0 = (k < 2u) ? 0u : bnd[j];
        uint32_t ctiles = (k < 2u) ? tiles : (bnd[j + 1] - bnd[j]);
        proj_t cv;
        if (chunk_view(g, pr, ct0, ctiles, &cv) < 0) return -EINVAL;
        out[n].first_tile  = ct0;
        out[n].tiles       = ctiles;
        out[n].first_col   = ct0 * olt;
        out[n].M           = cv.M;
        out[n].byte_offset = cv.byte_offset;
        out[n].blob_bytes  = mmfree_wt_port_bytes(g, cv.N, cv.M);
        n++;
    }
    return (int)n;
}

int mmfree_bitlinear_batch_split(mmfree_lib_t *h, int proj_id, const int16_t *x,
                                 int32_t *acc, uint32_t b, uint32_t want_chunks) {
    return mmfree_bitlinear_batch_split_cb(h, proj_id, x, acc, b, want_chunks, NULL, NULL);
}

int mmfree_bitlinear_batch_split_cb(mmfree_lib_t *h, int proj_id, const int16_t *x,
                                int32_t *acc, uint32_t b, uint32_t want_chunks,
                                mmfree_chunk_cb_t cb, void *cb_ctx) {
    if (!h || proj_id < 0 || (uint32_t)proj_id >= h->nproj || !x || !acc) return -EINVAL;
    const proj_t *pr = &h->projs[proj_id];
    const mmfree_geom_t *g = &h->ctx.geom;
    if (b == 0 || b > g->batch) return -EINVAL;

    const uint32_t olt = g->outLanesPerTile;
    const uint32_t tiles = (pr->M + olt - 1) / olt;
    uint32_t bnd[SPLIT_MAX_CHUNKS + 1u];
    const uint32_t k = (want_chunks < 2u) ? 1u : chunk_boundaries(g, pr, tiles, want_chunks, bnd);
    /* Nothing to overlap with a single chunk — take the plain blocking path, which also
     * keeps the phase stats meaningful for the un-split case. The callback still fires (over
     * the whole column range) so the caller's work happens exactly once either way; it just
     * cannot be hidden here. */
    if (k < 2u) {
        int rc = mmfree_bitlinear_batch(h, proj_id, x, acc, b);
        if (rc == 0 && cb) cb(cb_ctx, 0u, pr->M);
        return rc;
    }

    uint64_t t0 = now_ns();
    /* ONE pack + ONE LOAD for every chunk: they all read the same activations. */
    pack_act_port0(h, x, pr->N, b);
    uint64_t tps = now_ns();
    mmfree_buf_sync_for_device(&h->act[0], (size_t)pr->N * g->portBytes);
    uint64_t t1 = now_ns();
    if (mmfree_load(&h->ctx, h->act, pr->N) < 0) return -EIO;
    uint64_t t2 = now_ns();

    uint64_t compute_ns = 0, store_ns = 0, readback_ns = 0, out_sync_ns = 0, hidden_ns = 0;
    proj_t cur, prev;
    uint32_t prev_first = 0, prev_cols = 0;
    int have_prev = 0;

    for (uint32_t j = 0; j < k; j++) {
        const uint32_t ct0 = bnd[j];
        const uint32_t ctiles = bnd[j + 1] - bnd[j];
        if (chunk_view(g, pr, ct0, ctiles, &cur) < 0) return -EINVAL;

        /* The COMPUTE window is issue -> wait-done. It is charged to compute_ns WHOLE, even
         * though the readback below runs inside it: that is the wall time spent waiting on
         * the engine, and it correctly absorbs the overrun when a readback outlasts the
         * COMPUTE it was meant to hide under. The hidden readback is therefore NOT added to
         * readback_ns (it costs nothing) — it goes to hidden_ns for tuning. This keeps
         * pack+load+compute+store+readback == wall time on both paths, so the split and
         * un-split phase tables stay directly comparable. */
        uint64_t c0 = now_ns();
        if (compute_issue_pr(h, &cur, b) < 0) return -EIO;

        /* --- CPU work hidden under this chunk's COMPUTE ---
         * Sign-expand the PREVIOUS chunk. OUT still holds its STORE (we have not issued
         * this chunk's STORE yet), and COMPUTE never touches OUT — the same invariant
         * matmul_seq relies on. This is the whole point of the split. */
        if (have_prev) {
            uint64_t r0 = now_ns();
            readback_rows_at(h, &prev, acc, b, pr->M, prev_first);
            /* ...and whatever else the caller can do with just those columns. Both are
             * charged to hidden_ns: they cost no wall time unless they outlast the COMPUTE,
             * in which case the window itself stretches and compute_ns picks up the excess. */
            if (cb) cb(cb_ctx, prev_first, prev_cols);
            hidden_ns += now_ns() - r0;
        }
        /* -------------------------------------------------- */

        if (mmfree_seq_compute_wait(h) < 0) return -EIO;
        compute_ns += now_ns() - c0;

        uint64_t s0 = now_ns();
        if (store_issue_pr(h, &cur, b) < 0) return -EIO;
        if (store_wait_pr(h, &cur, b, &out_sync_ns) < 0) return -EIO;
        store_ns += now_ns() - s0;

        prev = cur;
        prev_first = ct0 * olt;
        prev_cols = cur.M;
        have_prev = 1;
    }
    /* Last chunk: no COMPUTE left to hide its readback under, so this one IS paid for. */
    uint64_t r0 = now_ns();
    readback_rows_at(h, &prev, acc, b, pr->M, prev_first);
    if (cb) cb(cb_ctx, prev_first, prev_cols);
    readback_ns += now_ns() - r0;

    /* Count the projection as ONE call so us/call stays comparable to the un-split path;
     * the chunking shows up as compute/readback moving, not as inflated call counts. */
    h->stats.calls++;
    h->stats.pack_ns     += t1 - t0;
    h->stats.act_sync_ns += t1 - tps;
    h->stats.load_ns     += t2 - t1;
    h->stats.compute_ns  += compute_ns;
    h->stats.store_ns    += store_ns;
    h->stats.out_sync_ns += out_sync_ns;
    h->stats.readback_ns += readback_ns;
    h->stats.readback_hidden_ns += hidden_ns;
    return 0;
}

/* ---- pipelined cluster primitives ------------------------------------------
 * mmfree_bitlinear_batch split into non-blocking issue + blocking wait so the
 * caller (FpgaBackend::matmul_seq) can run CPU work — quant+pack the NEXT
 * projection, sign-expand the PREVIOUS one — inside the COMPUTE wait window,
 * while the engine is busy. Engine ordering stays strictly one-op-outstanding
 * (the handler's single irqPending bit + shared MM2S forbid more), so the only
 * thing overlapped is CPU vs engine. Single ACT region (LOAD drains it into
 * actBram before COMPUTE) and single OUT region (the caller reads OUT back
 * before issuing the next STORE) — no double-buffering needed. */

int mmfree_seq_pack(mmfree_lib_t *h, const int16_t *x, uint32_t N, uint32_t b) {
    if (!h || !x) return -EINVAL;
    const mmfree_geom_t *g = &h->ctx.geom;
    if (b == 0 || b > g->batch) return -EINVAL;
    pack_act_port0(h, x, N, b);
    mmfree_buf_sync_for_device(&h->act[0], (size_t)N * g->portBytes);
    return 0;
}

int mmfree_seq_load(mmfree_lib_t *h, uint32_t N) {
    if (!h) return -EINVAL;
    if (mmfree_load(&h->ctx, h->act, N) < 0) return -EIO;   /* blocking */
    return 0;
}

/* COMPUTE issue for an explicit projection view — the chunk-aware core of
 * mmfree_seq_compute_issue. A chunk is just a proj_t with a shifted byte_offset and a
 * smaller M (see chunk_view), so it needs no separate code path. */
static int compute_issue_pr(mmfree_lib_t *h, const proj_t *pr, uint32_t b) {
    const mmfree_geom_t *g = &h->ctx.geom;
    /* Per-port weight DMA length (batch-count-independent; honors packed). */
    uint32_t bytes = mmfree_wt_port_bytes(g, pr->N, pr->M);
    for (uint32_t i = 0; i < h->ctx.num_dma; i++)
        if (mmfree_dma_mm2s_start(h->ctx.dma_regs[i], h->wt[i].paddr + pr->byte_offset,
                                  bytes) < 0)
            return -EIO;
    /* flags=b: drain only the b active rows (see mmfree_inst_compute_b). */
    mmfree_push_instr(&h->ctx, mmfree_inst_compute_b(h->wt[0].paddr + pr->byte_offset,
                                                     pr->N, pr->M, (uint16_t)b));
    return 0;
}

int mmfree_seq_compute_issue(mmfree_lib_t *h, int proj_id, uint32_t b) {
    if (!h || proj_id < 0 || (uint32_t)proj_id >= h->nproj) return -EINVAL;
    const mmfree_geom_t *g = &h->ctx.geom;
    if (b == 0 || b > g->batch) return -EINVAL;
    return compute_issue_pr(h, &h->projs[proj_id], b);
}

int mmfree_seq_compute_wait(mmfree_lib_t *h) {
    if (!h) return -EINVAL;
    uint32_t st = mmfree_wait_done(&h->ctx);
    if (MMFREE_STATUS_ERR(st) != MMFREE_ERR_NONE) return -EIO;
    for (uint32_t i = 0; i < h->ctx.num_dma; i++)
        if (mmfree_dma_mm2s_wait(h->ctx.dma_regs[i]) < 0) return -EIO;
    return 0;
}

static int store_issue_pr(mmfree_lib_t *h, const proj_t *pr, uint32_t b) {
    const mmfree_geom_t *g = &h->ctx.geom;
    /* Store only the b rows the drain wrote (rows 0..b-1, row-major). */
    uint32_t total = b * pr->n_outputs;
    uint32_t bytes = total * g->outLaneBytes;
    if (mmfree_dma_s2mm_start(h->ctx.dma_regs[0], h->out.paddr, bytes) < 0) return -EIO;
    mmfree_push_instr(&h->ctx, mmfree_inst_store(h->out.paddr, total));
    return 0;
}

int mmfree_seq_store_issue(mmfree_lib_t *h, int proj_id, uint32_t b) {
    if (!h || proj_id < 0 || (uint32_t)proj_id >= h->nproj) return -EINVAL;
    if (b == 0 || b > h->ctx.geom.batch) return -EINVAL;
    return store_issue_pr(h, &h->projs[proj_id], b);
}

/* `sync_ns` (nullable) receives just the cache-invalidate portion, so the split driver can
 * keep out_sync_ns meaning the same thing it does on the blocking path (the sync alone, a
 * subset of store) rather than conflating it with the S2MM wait. */
static int store_wait_pr(mmfree_lib_t *h, const proj_t *pr, uint32_t b, uint64_t *sync_ns) {
    const mmfree_geom_t *g = &h->ctx.geom;
    uint32_t st = mmfree_wait_done(&h->ctx);
    if (MMFREE_STATUS_ERR(st) != MMFREE_ERR_NONE) return -EIO;
    if (mmfree_dma_s2mm_wait(h->ctx.dma_regs[0]) < 0) return -EIO;
    uint64_t s0 = sync_ns ? now_ns() : 0;
    mmfree_buf_sync_for_cpu(&h->out, (size_t)b * pr->n_outputs * g->outLaneBytes);
    if (sync_ns) *sync_ns += now_ns() - s0;
    return 0;
}

int mmfree_seq_store_wait(mmfree_lib_t *h, int proj_id, uint32_t b) {
    if (!h || proj_id < 0 || (uint32_t)proj_id >= h->nproj) return -EINVAL;
    if (b == 0 || b > h->ctx.geom.batch) return -EINVAL;
    return store_wait_pr(h, &h->projs[proj_id], b, NULL);
}

void mmfree_seq_readback(mmfree_lib_t *h, int proj_id, int32_t *acc, uint32_t b) {
    readback_rows(h, &h->projs[proj_id], acc, b);
}
