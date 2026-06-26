/*
 * mmfree_lib.c — implementation of mmfree_lib.h (Phase C).
 *
 * Thin orchestration layer over mmfree_runtime: it owns the resident udmabufs
 * and a small projection table, and turns one mmfree_bitlinear() call into the
 * LOAD_ACT -> COMPUTE_MM@offset -> STORE_OUT sequence the bench validated.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "mmfree_lib.h"
#include "mmfree_runtime.h"

/* Monotonic nanosecond clock for the per-phase timers. Reads go through the
 * vDSO (no syscall), so the ~6 reads per bitlinear call are cheap relative to
 * the IRQ round-trips they bracket. */
static inline uint64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
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
    h->ctx.geom.batch = cfg->batch ? cfg->batch : 1u;
    if (h->ctx.geom.batch > cfg->xDim) {
        fprintf(stderr, "mmfree_lib_open: batch=%u must be <= xDim=%u\n",
                h->ctx.geom.batch, cfg->xDim);
        goto fail;
    }

    const uint32_t P = h->ctx.geom.numPorts;
    for (uint32_t p = 0; p < P; p++) {
        /* Weights are written once (cold path) and ports 1..P-1 carry only the
         * static zero slice — leave those non-cached. Only act port 0 (re-packed
         * every op) and the output (drained every op) are hot, so map those
         * cacheable and sync them per call in mmfree_bitlinear. */
        if (mmfree_buf_open(&h->wt[p], cfg->wt_dev[p], cfg->weight_bytes_per_port, 0) < 0 ||
            h->wt[p].size < cfg->weight_bytes_per_port) {
            fprintf(stderr, "mmfree_lib_open: weight buf port %u (%s) too small/failed\n",
                    p, cfg->wt_dev[p]);
            goto fail;
        }
        if (mmfree_buf_open(&h->act[p], cfg->act_dev[p], cfg->act_bytes_per_port,
                            /*cached=*/p == 0) < 0 ||
            h->act[p].size < cfg->act_bytes_per_port) {
            fprintf(stderr, "mmfree_lib_open: act buf port %u (%s) too small/failed\n",
                    p, cfg->act_dev[p]);
            goto fail;
        }
        /* Every activation port slice is zero outside the low aWidth bits, and
         * ports 1..P-1 are zero entirely — clear once. Port 0 is cacheable and
         * per op we only rewrite the low aWidth bytes of each beat, relying on
         * these high-byte zeros staying put, so flush them to DDR once now. */
        dev_zero((volatile uint8_t *)h->act[p].vaddr, h->act[p].size);
        if (p == 0) mmfree_buf_sync_for_device(&h->act[0], h->act[0].size);
        h->open_bufs = (int)(p + 1);
    }
    if (mmfree_buf_open(&h->out, cfg->out_dev, cfg->out_bytes, /*cached=*/1) < 0 ||
        h->out.size < cfg->out_bytes) {
        fprintf(stderr, "mmfree_lib_open: out buf (%s) too small/failed\n", cfg->out_dev);
        goto fail;
    }
    h->out_open = 1;

    h->max_proj = cfg->max_proj ? cfg->max_proj : 128u;
    h->projs = calloc(h->max_proj, sizeof(proj_t));
    if (!h->projs) goto fail;
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
    /* Validate the projection's weight slice fits the resident buffer. */
    size_t blob_bytes = (size_t)col_tiles * N * g->portBytes;
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

int mmfree_bitlinear_batch(mmfree_lib_t *h, int proj_id,
                           const int16_t *x, int32_t *acc, uint32_t b) {
    if (!h || proj_id < 0 || (uint32_t)proj_id >= h->nproj || !x || !acc) return -EINVAL;
    const proj_t *pr = &h->projs[proj_id];
    const mmfree_geom_t *g = &h->ctx.geom;
    const uint32_t abytes = g->aWidth / 8u;
    const uint32_t B = g->batch;            /* PE rows the bitstream loads/drains */
    if (b == 0 || b > B) {
        fprintf(stderr, "bitlinear_batch: b=%u out of range 1..%u\n", b, B);
        return -EINVAL;
    }

    uint64_t t0 = now_ns();

    /* 1. Pack the B activation rows into port 0 (ports 1..P-1 stay zero from
     *    open). Row r occupies bytes [r*abytes, (r+1)*abytes) of each beat slice,
     *    matching Core.scala sLoad's tdata((r+1)*aWidth-1, r*aWidth) unpack; rows
     *    [b, B) are zero-padded (their outputs are ignored). B*abytes <= portBytes
     *    since batch <= xDim. Fast path (cached + int16) writes only those low
     *    B*abytes bytes, relying on the open-time zeroing of the rest; the
     *    fallback rewrites the whole slice. */
    volatile uint8_t *a0 = (volatile uint8_t *)h->act[0].vaddr;
    if (h->act[0].cached && abytes == 2) {
        /* act[0] is a *cached* normal-memory mapping flushed by the explicit
         * sync_for_device below, so these stores need no `volatile` — it only
         * defeats coalescing/vectorization. This strided int16 scatter was the
         * dominant per-call cost in profiling (~30%); writing through a plain
         * pointer lets the compiler pipeline/unroll it. Semantics unchanged. */
        int16_t *a0w = (int16_t *)h->act[0].vaddr;
        const uint32_t stride = g->portBytes / 2u;   /* int16 slots per beat */
        for (uint32_t n = 0; n < pr->N; n++) {
            int16_t *beat = a0w + (size_t)n * stride;
            for (uint32_t r = 0; r < B; r++)
                beat[r] = (int16_t)((r < b) ? x[(size_t)r * pr->N + n] : 0);
        }
    } else {
        int32_t vals[MMFREE_MAX_DMA * 64];   /* batch <= xDim; ample headroom */
        for (uint32_t n = 0; n < pr->N; n++) {
            for (uint32_t r = 0; r < B; r++)
                vals[r] = (r < b) ? (int32_t)x[(size_t)r * pr->N + n] : 0;
            mmfree_pack_act_beat_batch(&a0[(size_t)n * g->portBytes], vals, B,
                                       abytes, g->portBytes);
        }
    }
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
    if (mmfree_compute_off(&h->ctx, h->wt, pr->N, pr->M, pr->byte_offset) < 0) return -EIO;
    uint64_t t3 = now_ns();
    const uint32_t total_outputs = B * pr->n_outputs;
    if (mmfree_store(&h->ctx, &h->out, total_outputs) < 0)                     return -EIO;
    uint64_t tos = now_ns();
    /* Output is cacheable: invalidate the drained lanes so the readback sees the
     * S2MM result from DDR rather than stale cache. */
    mmfree_buf_sync_for_cpu(&h->out, (size_t)total_outputs * g->outLaneBytes);
    uint64_t t4 = now_ns();

    /* 5. Sign-expand the first M lanes of each requested row. Output is
     *    batch-major: row i occupies lanes [i*n_outputs, i*n_outputs + M)
     *    (Core.scala drain rowAddr = (batch*numColTiles + colTile)*outSubBeats). */
    const volatile uint32_t *ob = (const volatile uint32_t *)h->out.vaddr;
    for (uint32_t i = 0; i < b; i++) {
        const volatile uint32_t *row = ob + (size_t)i * pr->n_outputs;
        for (uint32_t m = 0; m < pr->M; m++)
            acc[(size_t)i * pr->M + m] = (int32_t)mmfree_expand_acc(row[m], g->accWidth);
    }
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
