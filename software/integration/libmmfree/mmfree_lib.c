/* mmfree_lib.c — implementation of mmfree_lib.h: owns the resident udmabufs and
 * the projection table over mmfree_runtime. */

#define _GNU_SOURCE
#include <errno.h>
#ifdef _OPENMP
#include <omp.h>
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#if defined(__aarch64__) && defined(__ARM_NEON)
#include <arm_neon.h>
#define MMFREE_PACK_HAVE_NEON 1
#endif

#include "mmfree_lib.h"
#include "mmfree_runtime.h"
#include "mmfree_dma.h"

/* Monotonic ns clock for the phase timers (vDSO, no syscall). */
static inline uint64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

/* Readback threading (readback_rows_at), read once and cached:
 *   MMFREE_RB_THREADS=N    threads for the readback loop; 1 = serial (default: all cores)
 *   MMFREE_RB_MIN_ELEMS=N  b*M below N stays serial (default 4096; 1 forces threading)
 * The threshold matters: on small projections the parallel-region overhead exceeds
 * the whole loop. */
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
            t = omp_get_max_threads();
#else
            t = 1;
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

/* Activation pack path (pack_act_port0), read once and cached:
 *   MMFREE_PACK=neon|tiled|legacy   (default: neon on aarch64, else tiled)
 * legacy reads the b rows in lockstep, 2N bytes apart. At N=2048 that is exactly one page,
 * so all b loads share a page offset and the A53's physically indexed 4-way L1D set is
 * picked by PA[12] alone: more than 4 rows of one parity thrash, and the parity is the page
 * allocator's draw per process. That was 1.3B's bimodal b=6..8 cells (the same b=8 scatter
 * measured 90-442 us across fresh processes). tiled and neon read each row's tile start to
 * finish, so placement cannot matter; neon also transposes 8 lanes at a time (~4x faster
 * than legacy's best case). legacy is kept for in-session A/B only. */
enum { PACK_LEGACY, PACK_TILED, PACK_NEON };
#define PACK_TILE 32u              /* int16 per row per tile: one 64 B line */

static int pack_sel = -1;          /* file scope so the host test can force each path */

static int pack_mode(void) {
    if (pack_sel < 0) {
#ifdef MMFREE_PACK_HAVE_NEON
        pack_sel = PACK_NEON;
#else
        pack_sel = PACK_TILED;
#endif
        const char *e = getenv("MMFREE_PACK");
        if (e && *e) {
            if (!strcmp(e, "legacy"))     pack_sel = PACK_LEGACY;
            else if (!strcmp(e, "tiled")) pack_sel = PACK_TILED;
            else if (strcmp(e, "neon"))
                fprintf(stderr, "warning: ignoring MMFREE_PACK=%s (neon|tiled|legacy)\n", e);
        }
    }
    return pack_sel;
}

/* neon needs a 16 B beat (8 int16 slots = one q register); anything else runs tiled. */
static int pack_path(const mmfree_geom_t *g) {
    const int m = pack_mode();
    return (m == PACK_NEON && g->portBytes != 16u) ? PACK_TILED : m;
}

static const char *pack_name(int m) {
    return m == PACK_NEON ? "neon 8x8" : m == PACK_TILED ? "tiled" : "legacy";
}

/* Open one resident udmabuf, distinguishing the three failures: missing (overlay
 * not loaded), unopenable (permissions / driver), and too small (resize overlay). */
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

/* ---- pure-software helpers ---- */

/* Pack one batched LOAD beat: row r in bytes [r*abytes, (r+1)*abytes) (two's
 * complement, little-endian), tail zeroed (Core.scala sLoad: row r =
 * tdata((r+1)*aWidth-1, r*aWidth)). Byte stores are safe on Device memory.
 * Requires batch*abytes <= nbytes. */
void mmfree_pack_act_beat_batch(volatile uint8_t *dst, const int32_t *vals,
                                uint32_t batch, uint32_t abytes, uint32_t nbytes) {
    for (uint32_t r = 0; r < batch; r++) {
        uint32_t u = (uint32_t)vals[r];
        for (uint32_t k = 0; k < abytes; k++)
            dst[r * abytes + k] = (uint8_t)((u >> (k * 8)) & 0xFFu);
    }
    for (uint32_t k = batch * abytes; k < nbytes; k++) dst[k] = 0u;
}

/* Sign-extend a right-aligned `accWidth`-bit accumulator from its output lane. */
int64_t mmfree_expand_acc(uint64_t raw, uint32_t accWidth) {
    uint64_t mask = (accWidth >= 64) ? ~0ull : ((1ull << accWidth) - 1u);
    uint64_t sign = 1ull << (accWidth - 1u);
    raw &= mask;
    return (int64_t)((raw ^ sign) - sign);
}

/* Copy/zero a possibly Device-memory udmabuf with volatile word stores: glibc
 * memset/memcpy use dc zva, which SIGBUSes there. */
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
    /* batch activations share one port's slice of each LOAD beat: batch <= xDim. */
    h->ctx.geom.batch  = cfg->batch ? cfg->batch : 1u;
    h->ctx.geom.packed = cfg->packed ? 1u : 0u;
    if (h->ctx.geom.batch > cfg->xDim) {
        fprintf(stderr, "mmfree_lib_open: batch=%u must be <= xDim=%u\n",
                h->ctx.geom.batch, cfg->xDim);
        goto fail;
    }

    if (mmfree_geom_check_env(&h->ctx.geom) < 0) {
        fprintf(stderr, "mmfree_lib_open: MMFREE_STRICT geometry/bitstream mismatch\n");
        goto fail;
    }

    const uint32_t P = h->ctx.geom.numPorts;
    for (uint32_t p = 0; p < P; p++) {
        /* Only act port 0 (packed every op) and out (read every op) are hot: map
         * those cacheable and sync per call. Weights and act ports 1..P-1 stay
         * non-cached. */
        if (open_buf_checked(&h->wt[p], cfg->wt_dev[p], cfg->weight_bytes_per_port,
                             /*cached=*/0, "weight", (int)p) < 0)
            goto fail;
        if (open_buf_checked(&h->act[p], cfg->act_dev[p], cfg->act_bytes_per_port,
                             /*cached=*/p == 0, "act", (int)p) < 0)
            goto fail;
        /* Zero once: per op only port 0's active-row bytes are rewritten, so the
         * rest must stay zero (and port 0's zeros must be flushed to DDR now). */
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

    /* Log the readback configuration: it changes throughput, so every run's
     * output must state it. */
    printf("# readback: %s",
           rb_threads() > 1 ? "threaded" : "serial (1 thread)");
    if (rb_threads() > 1)
        printf(" x%d threads, threshold %zu elements (b*M below it stays serial)",
               rb_threads(), rb_min_elems());
#ifndef _OPENMP
    printf("  [built WITHOUT OpenMP — threading unavailable]");
#endif
    printf("\n");
    /* Same for the activation pack: legacy's cost depends on page placement. */
    if (h->act[0].cached && h->ctx.geom.aWidth == 16u)
        printf("# act pack: %s\n", pack_name(pack_path(&h->ctx.geom)));
    else
        printf("# act pack: per-beat (uncached ACT or aWidth != 16)\n");
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
    /* The weight slice must fit the resident buffer (honors packed). */
    size_t blob_bytes = mmfree_wt_port_bytes(g, N, M);
    if (byte_offset + blob_bytes > h->wt[0].size) {
        fprintf(stderr, "register: offset %llu + %zu > weight buf %zu\n",
                (unsigned long long)byte_offset, blob_bytes, h->wt[0].size);
        return -ENOSPC;
    }
    /* OUT must hold batch * n_outputs lanes (batch-major). */
    if ((size_t)g->batch * n_outputs * g->outLaneBytes > h->out.size) {
        fprintf(stderr, "register: batch(%u) * n_outputs(%u) exceeds out buf\n",
                g->batch, n_outputs);
        return -ENOSPC;
    }

    int id = (int)h->nproj++;
    h->projs[id] = (proj_t){ .byte_offset = byte_offset, .N = N, .M = M, .n_outputs = n_outputs };
    return id;
}

/* Sign-expand b rows of `pr` from OUT (row stride n_outputs) into `acc` rows of
 * `acc_stride`, at column `col0`. Un-split: (pr->M, 0); a chunk passes the full
 * projection's M and its first column. OUT is cached and already invalidated, so the
 * fast path reads it as normal memory with a shift-pair sign extend (vectorizable,
 * same result as mmfree_expand_acc for accWidth <= 32); otherwise volatile reads. */
static void readback_rows_at(mmfree_lib_t *h, const proj_t *pr, int32_t *acc, uint32_t b,
                             uint32_t acc_stride, uint32_t col0) {
    const mmfree_geom_t *g = &h->ctx.geom;
    if (h->out.cached && g->outLaneBytes == 4 && g->accWidth <= 32) {
        const uint32_t sh = 32u - g->accWidth;
        const uint32_t *ob = (const uint32_t *)h->out.vaddr;
#ifdef _OPENMP
        /* Partition (row x column block), not rows alone, so b=1 still threads and
         * b need not divide evenly over the cores. */
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

/* Columns [n0, N) of the b rows into beats of `stride` int16 slots, one PACK_TILE-column
 * tile at a time; within a tile each row's slice is read start to finish. */
static void pack_rows_tiled(int16_t *a0w, const int16_t *x, uint32_t N, uint32_t b,
                            uint32_t stride, uint32_t n0) {
    for (; n0 < N; n0 += PACK_TILE) {
        const uint32_t n1 = (n0 + PACK_TILE < N) ? n0 + PACK_TILE : N;
        for (uint32_t r = 0; r < b; r++) {
            const int16_t *src = x + (size_t)r * N;
            int16_t *dst = a0w + r;
            for (uint32_t n = n0; n < n1; n++)
                dst[(size_t)n * stride] = src[n];
        }
    }
}

#ifdef MMFREE_PACK_HAVE_NEON
static inline int16x8_t trn1_s64(int32x4_t a, int32x4_t b) {
    return vreinterpretq_s16_s64(vtrn1q_s64(vreinterpretq_s64_s32(a), vreinterpretq_s64_s32(b)));
}
static inline int16x8_t trn2_s64(int32x4_t a, int32x4_t b) {
    return vreinterpretq_s16_s64(vtrn2q_s64(vreinterpretq_s64_s32(a), vreinterpretq_s64_s32(b)));
}

/* v[i] <- column i of the 8x8 int16 matrix whose rows are v[0..7]. */
static inline void transpose8x8_s16(int16x8_t v[8]) {
    const int16x8_t t0 = vtrn1q_s16(v[0], v[1]), t1 = vtrn2q_s16(v[0], v[1]);
    const int16x8_t t2 = vtrn1q_s16(v[2], v[3]), t3 = vtrn2q_s16(v[2], v[3]);
    const int16x8_t t4 = vtrn1q_s16(v[4], v[5]), t5 = vtrn2q_s16(v[4], v[5]);
    const int16x8_t t6 = vtrn1q_s16(v[6], v[7]), t7 = vtrn2q_s16(v[6], v[7]);
#define S32(q) vreinterpretq_s32_s16(q)
    const int32x4_t u0 = vtrn1q_s32(S32(t0), S32(t2)), u2 = vtrn2q_s32(S32(t0), S32(t2));
    const int32x4_t u1 = vtrn1q_s32(S32(t1), S32(t3)), u3 = vtrn2q_s32(S32(t1), S32(t3));
    const int32x4_t u4 = vtrn1q_s32(S32(t4), S32(t6)), u6 = vtrn2q_s32(S32(t4), S32(t6));
    const int32x4_t u5 = vtrn1q_s32(S32(t5), S32(t7)), u7 = vtrn2q_s32(S32(t5), S32(t7));
#undef S32
    v[0] = trn1_s64(u0, u4); v[4] = trn2_s64(u0, u4);
    v[1] = trn1_s64(u1, u5); v[5] = trn2_s64(u1, u5);
    v[2] = trn1_s64(u2, u6); v[6] = trn2_s64(u2, u6);
    v[3] = trn1_s64(u3, u7); v[7] = trn2_s64(u3, u7);
}

/* 16 B beats: 8 consecutive beats are the 8x8 transpose of 8 columns of the 8 rows. Each
 * row's tile is first copied to a contiguous scratch (its source line read once, in order);
 * scratch rows [b,8) stay zero, so those slots are written as zero. The N % PACK_TILE tail
 * goes through the tiled path. */
static void pack_rows_neon8(int16_t *a0w, const int16_t *x, uint32_t N, uint32_t b) {
    int16_t tile[8][PACK_TILE] __attribute__((aligned(64)));
    memset(tile, 0, sizeof(tile));
    const uint32_t Nt = N & ~(PACK_TILE - 1u);
    for (uint32_t n0 = 0; n0 < Nt; n0 += PACK_TILE) {
        for (uint32_t r = 0; r < b; r++) {
            const int16_t *s = x + (size_t)r * N + n0;
            for (uint32_t k = 0; k < PACK_TILE; k += 8u)
                vst1q_s16(&tile[r][k], vld1q_s16(s + k));
        }
        for (uint32_t k = 0; k < PACK_TILE; k += 8u) {
            int16x8_t v[8];
            for (uint32_t r = 0; r < 8u; r++) v[r] = vld1q_s16(&tile[r][k]);
            transpose8x8_s16(v);
            for (uint32_t i = 0; i < 8u; i++)
                vst1q_s16(a0w + (size_t)(n0 + k + i) * 8u, v[i]);
        }
    }
    pack_rows_tiled(a0w, x, N, b, 8u, Nt);
}
#endif

/* Scatter b activation rows (x[r*N + n]) into port-0 ACT beats, row r at bytes
 * [r*abytes, (r+1)*abytes). Rows [b,B) are never drained: legacy and tiled leave them
 * as they were, neon writes zero. No cache sync — the caller does it. */
static void pack_act_port0(mmfree_lib_t *h, const int16_t *x, uint32_t N, uint32_t b) {
    const mmfree_geom_t *g = &h->ctx.geom;
    const uint32_t abytes = g->aWidth / 8u;
    volatile uint8_t *a0 = (volatile uint8_t *)h->act[0].vaddr;
    if (h->act[0].cached && abytes == 2) {
        /* Cached mapping (flushed by sync_for_device): plain stores are fine. */
        int16_t *a0w = (int16_t *)h->act[0].vaddr;
        const uint32_t stride = g->portBytes / 2u;   /* int16 slots per beat */
        switch (pack_path(g)) {
#ifdef MMFREE_PACK_HAVE_NEON
        case PACK_NEON:  pack_rows_neon8(a0w, x, N, b);           return;
#endif
        case PACK_TILED: pack_rows_tiled(a0w, x, N, b, stride, 0u); return;
        default:         break;
        }
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

    pack_act_port0(h, x, pr->N, b);
    uint64_t tps = now_ns();
    mmfree_buf_sync_for_device(&h->act[0], (size_t)pr->N * g->portBytes);
    uint64_t t1 = now_ns();

    if (mmfree_load(&h->ctx, h->act, pr->N) < 0)                               return -EIO;
    uint64_t t2 = now_ns();
    /* flags=b: the engine drains only the b active rows (rows 0..b-1). */
    if (mmfree_compute_off_b(&h->ctx, h->wt, pr->N, pr->M, pr->byte_offset, b) < 0) return -EIO;
    uint64_t t3 = now_ns();
    const uint32_t total_outputs = b * pr->n_outputs;
    if (mmfree_store(&h->ctx, &h->out, total_outputs) < 0)                     return -EIO;
    uint64_t tos = now_ns();
    mmfree_buf_sync_for_cpu(&h->out, (size_t)total_outputs * g->outLaneBytes);
    uint64_t t4 = now_ns();

    /* Batch-major: row i occupies lanes [i*n_outputs, i*n_outputs + M). */
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

/* ---- column-tile splitting ----
 * A blob is col-tile-major per port, so a contiguous range of output column tiles
 * is a contiguous byte range: a chunk is the same projection with a shifted
 * byte_offset and smaller M, and all chunks share one pack + LOAD (actBram persists
 * across COMPUTEs). Legal chunk starts depend on the encoding:
 *  - 2-bit: offset = t*N*portBytes; any tile boundary works.
 *  - 1.6-bit: tile t starts at trit t*N*olt, byte t*N*olt/5. Legal only if that
 *    divides exactly AND lands on a wire beat; per-port offset = flat/numPorts.
 * chunk_offset() returns <0 for an illegal boundary.
 */
static int compute_issue_pr(mmfree_lib_t *h, const proj_t *pr, uint32_t b);
static int store_issue_pr(mmfree_lib_t *h, const proj_t *pr, uint32_t b);
static int store_wait_pr(mmfree_lib_t *h, const proj_t *pr, uint32_t b, uint64_t *sync_ns);

static int chunk_offset(const mmfree_geom_t *g, uint32_t N, uint32_t t, uint64_t *out) {
    if (!g->packed) {
        *out = (uint64_t)t * N * g->portBytes;
        return 0;
    }
    uint64_t trits = (uint64_t)t * N * g->outLanesPerTile;
    if (trits % 5u) return -1;                       /* not a whole byte */
    uint64_t flat = trits / 5u;
    if (flat % g->sAxisBytes) return -1;             /* not a wire-beat boundary */
    *out = flat / g->numPorts;
    return 0;
}

/* proj_t view of tiles [t0, t0+tiles) of `pr`. M is clamped to the real column
 * count; n_outputs stays tile-rounded (what the engine drains). */
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

/* Bounds the on-stack boundary array. */
#define SPLIT_MAX_CHUNKS 64u

/* Tile boundaries for a split, shared by the split driver and mmfree_split_plan so
 * the inspectable plan is the one that runs. Fills bnd[0]=0 .. bnd[n]=tiles, strictly
 * increasing. Each interior boundary starts at the even point j*(tiles/want) and snaps
 * outward to the nearest legal tile (a no-op when it is already legal). Returns the
 * achieved chunk count, which can be < want; 1 means no split. */
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
        /* Search outward from the even point, nearest first. */
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
    /* One chunk: plain blocking path; the callback still fires once over all columns. */
    if (k < 2u) {
        int rc = mmfree_bitlinear_batch(h, proj_id, x, acc, b);
        if (rc == 0 && cb) cb(cb_ctx, 0u, pr->M);
        return rc;
    }

    uint64_t t0 = now_ns();
    /* ONE pack + ONE LOAD serves every chunk. */
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

        /* compute_ns takes the whole issue->wait window (absorbing any readback overrun);
         * the hidden readback goes to hidden_ns, so the phase sum stays wall time. */
        uint64_t c0 = now_ns();
        if (compute_issue_pr(h, &cur, b) < 0) return -EIO;

        /* Under this chunk's COMPUTE: read back the PREVIOUS chunk. OUT still holds its
         * STORE (this chunk's STORE is not issued yet) and COMPUTE never touches OUT. */
        if (have_prev) {
            uint64_t r0 = now_ns();
            readback_rows_at(h, &prev, acc, b, pr->M, prev_first);
            if (cb) cb(cb_ctx, prev_first, prev_cols);
            hidden_ns += now_ns() - r0;
        }

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
    /* Last chunk: nothing left to hide its readback under. */
    uint64_t r0 = now_ns();
    readback_rows_at(h, &prev, acc, b, pr->M, prev_first);
    if (cb) cb(cb_ctx, prev_first, prev_cols);
    readback_ns += now_ns() - r0;

    /* One call per projection, as on the un-split path. */
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

/* ---- pipelined primitives (see mmfree_lib.h; driven by FpgaBackend::matmul_seq) ---- */

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

/* COMPUTE issue for a projection or chunk view (see chunk_view). */
static int compute_issue_pr(mmfree_lib_t *h, const proj_t *pr, uint32_t b) {
    const mmfree_geom_t *g = &h->ctx.geom;
    uint32_t bytes = mmfree_wt_port_bytes(g, pr->N, pr->M);
    for (uint32_t i = 0; i < h->ctx.num_dma; i++)
        if (mmfree_dma_mm2s_start(h->ctx.dma_regs[i], h->wt[i].paddr + pr->byte_offset,
                                  bytes) < 0)
            return -EIO;
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
    /* Store only the b rows the drain wrote. */
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

/* `sync_ns` (nullable) accumulates just the cache-invalidate time. */
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
