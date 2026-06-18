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

#include "mmfree_lib.h"
#include "mmfree_runtime.h"

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
};

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

    const uint32_t P = h->ctx.geom.numPorts;
    for (uint32_t p = 0; p < P; p++) {
        if (mmfree_buf_open(&h->wt[p], cfg->wt_dev[p], cfg->weight_bytes_per_port) < 0 ||
            h->wt[p].size < cfg->weight_bytes_per_port) {
            fprintf(stderr, "mmfree_lib_open: weight buf port %u (%s) too small/failed\n",
                    p, cfg->wt_dev[p]);
            goto fail;
        }
        if (mmfree_buf_open(&h->act[p], cfg->act_dev[p], cfg->act_bytes_per_port) < 0 ||
            h->act[p].size < cfg->act_bytes_per_port) {
            fprintf(stderr, "mmfree_lib_open: act buf port %u (%s) too small/failed\n",
                    p, cfg->act_dev[p]);
            goto fail;
        }
        /* Every activation port slice is zero outside the low aWidth bits, and
         * ports 1..P-1 are zero entirely — clear once; port 0 is rewritten per
         * op (the whole portBytes slice, so no stale carry). */
        dev_zero((volatile uint8_t *)h->act[p].vaddr, h->act[p].size);
        h->open_bufs = (int)(p + 1);
    }
    if (mmfree_buf_open(&h->out, cfg->out_dev, cfg->out_bytes) < 0 ||
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
    if ((size_t)n_outputs * g->outLaneBytes > h->out.size) {
        fprintf(stderr, "register: n_outputs %u exceeds out buf\n", n_outputs);
        return -ENOSPC;
    }

    int id = (int)h->nproj++;
    h->projs[id] = (proj_t){ .byte_offset = byte_offset, .N = N, .M = M, .n_outputs = n_outputs };
    return id;
}

int mmfree_bitlinear(mmfree_lib_t *h, int proj_id,
                     const int16_t *x, int32_t *acc) {
    if (!h || proj_id < 0 || (uint32_t)proj_id >= h->nproj || !x || !acc) return -EINVAL;
    const proj_t *pr = &h->projs[proj_id];
    const mmfree_geom_t *g = &h->ctx.geom;
    const uint32_t abytes = g->aWidth / 8u;

    /* 1. Write activations into port 0 (ports 1..P-1 stay zero from open). */
    volatile uint8_t *a0 = (volatile uint8_t *)h->act[0].vaddr;
    for (uint32_t n = 0; n < pr->N; n++)
        mmfree_pack_act_beat(&a0[(size_t)n * g->portBytes], (int32_t)x[n], abytes, g->portBytes);

    /* 2. LOAD_ACT -> 3. COMPUTE_MM at this projection's resident offset
     *    -> 4. STORE_OUT. */
    if (mmfree_load(&h->ctx, h->act, pr->N) < 0)                               return -EIO;
    if (mmfree_compute_off(&h->ctx, h->wt, pr->N, pr->M, pr->byte_offset) < 0) return -EIO;
    if (mmfree_store(&h->ctx, &h->out, pr->n_outputs) < 0)                     return -EIO;

    /* 5. Sign-expand the first M lanes (the rest are col-tile padding). */
    const volatile uint8_t *ob = (const volatile uint8_t *)h->out.vaddr;
    for (uint32_t m = 0; m < pr->M; m++) {
        uint32_t raw = ((const volatile uint32_t *)ob)[m];   /* outLaneBytes == 4 */
        acc[m] = (int32_t)mmfree_expand_acc(raw, g->accWidth);
    }
    return 0;
}
