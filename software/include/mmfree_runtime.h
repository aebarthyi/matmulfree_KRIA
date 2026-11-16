/*
 * mmfree_runtime.h — userspace runtime for the mmfree Core IP on KRIA.
 *
 * Core + AXI DMA register windows are mmap'd through /dev/mem, DMA buffers come
 * from u-dma-buf (/dev/udmabuf*), and Core completion is a UIO IRQ (uio_pdrv_genirq)
 * or a status-word spin. The high-level ops issue the instruction, kick the DMAs
 * and wait for completion as one unit.
 */

#ifndef MMFREE_RUNTIME_H_
#define MMFREE_RUNTIME_H_

#include <stdint.h>
#include <stddef.h>

#include "mmfree_core.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Systolic-array geometry. Must match the loaded bitstream's CoreConfig — a
 * mismatch makes the Core wait forever on s_axis/m_axis (caught only by the
 * completion timeout). Inputs are set by the caller; derived fields by
 * mmfree_geom_init. */
typedef struct mmfree_geom {
    /* inputs (mirror CoreConfig) */
    uint32_t aWidth;            /* activation bit width */
    uint32_t xDim;              /* systolic array columns */
    uint32_t maxAcc;            /* max accumulation cycles (bounds N) */
    uint32_t maxN;              /* max inner dim swept */
    uint32_t maxM;              /* max output dim swept */
    uint32_t batch;             /* CoreConfig.batchSize (yDim); 1 = unbatched; must be <= xDim */
    /* derived */
    uint32_t nLanes;            /* aWidth / 2 (ternary lanes per PE) */
    uint32_t outLanesPerTile;   /* xDim * nLanes = output cols per col-tile */
    uint32_t sAxisBytes;        /* xDim * aWidth / 8 = bytes per s_axis beat */
    uint32_t accWidth;          /* log2Up(maxAcc) + aWidth = real acc bits */
    uint32_t outLaneWidth;      /* nextPow2(accWidth) = m_axis lane bits */
    uint32_t outLaneBytes;      /* outLaneWidth / 8 = bytes per output lane */
    uint32_t numPorts;          /* s_axis port count = min(4, ceil(bits/128)) — mirrors CoreConfig */
    uint32_t portBytes;         /* sAxisBytes / numPorts = bytes per port per beat */
    uint32_t packed;            /* 1 = 1.6-bit weight stream (packedWeights bitstream); geom_init seeds 0 */
} mmfree_geom_t;

/* Fill the derived fields of `g` from the five inputs; seeds batch=1, packed=0
 * (callers set those afterward). Returns -1 if xDim*aWidth is not whole bytes or a
 * dimension is zero. batch must stay <= xDim: numPorts/portBytes are derived from
 * xDim, but the s_axis width is max(xDim,batch)*aWidth. */
int mmfree_geom_init(mmfree_geom_t *g, uint32_t aWidth, uint32_t xDim,
                     uint32_t maxAcc, uint32_t maxN, uint32_t maxM);

/* Per-port COMPUTE weight DMA length for a (rows x cols) projection; mirrors
 * mmfree_pack's Geometry.port_blob_bytes. 2-bit: rows*col_tiles*portBytes. Packed:
 * trits 5/byte, padded to a whole wire beat (exactly what the gearbox consumes). */
uint32_t mmfree_wt_port_bytes(const mmfree_geom_t *g, uint32_t rows, uint32_t cols);

/* Cross-check `g` against a preset.env manifest (core.EmitCore) for the loaded
 * bitstream. NULL/empty/unreadable path skips the check (returns 0). Returns the
 * number of mismatched fields, each WARNed to stderr; with MMFREE_STRICT=1 any
 * mismatch returns -1. mmfree_geom_check_env takes the path from $MMFREE_MANIFEST. */
int mmfree_geom_check_manifest(const mmfree_geom_t *g, const char *path);
int mmfree_geom_check_env(const mmfree_geom_t *g);

/* Max parallel DMAs/HP ports the input stream can be split across (HP0..HP3). */
#define MMFREE_MAX_DMA 4

/* Runtime handle (plain data). */
typedef struct mmfree_ctx {
    volatile void *core_regs;   /* mmap'd Core AXI4 register base */
    volatile void *dma_regs[MMFREE_MAX_DMA]; /* mmap'd AXI DMA bases; [0] has S2MM, 1..N-1 are MM2S-only */
    uint32_t       num_dma;     /* DMAs in use — must equal geom.numPorts */
    int            uio_fd;      /* /dev/uioN bound to Core irq */
    size_t         core_size;
    size_t         dma_size;
    mmfree_geom_t  geom;        /* array geometry the byte-count math reads */
} mmfree_ctx_t;

/* A udmabuf-backed contiguous buffer. */
typedef struct mmfree_buf {
    void     *vaddr;            /* userspace pointer */
    uint64_t  paddr;            /* physical bus address (use in instructions / DMA) */
    size_t    size;             /* total bytes */
    int       fd;
    int       cached;           /* mapped write-back cacheable (manual sync required) */
    int       sync_size_fd;     /* sysfs sync_size (-1 if not cached) */
    int       sync_cpu_fd;      /* sysfs sync_for_cpu  (invalidate, post-S2MM) */
    int       sync_dev_fd;      /* sysfs sync_for_device (clean, pre-MM2S) */
} mmfree_buf_t;

/* ---- setup / teardown ---- */

/* mmap the Core regs and `num_dma` AXI DMA windows via /dev/mem (DMA 0 is the full
 * MM2S+S2MM engine, 1..N-1 are MM2S-only), open `uio_dev`, and reset the DMAs.
 * Seeds a default geometry; callers overwrite ctx->geom. On failure returns
 * -errno with ctx partially populated — still call mmfree_close. */
int  mmfree_open(mmfree_ctx_t *ctx,
                 uint64_t core_phys, size_t core_size,
                 const uint64_t *dma_phys, uint32_t num_dma, size_t dma_size,
                 const char *uio_dev);
void mmfree_close(mmfree_ctx_t *ctx);

/* Map a u-dma-buf node (e.g. "/dev/udmabuf-act0"); the mapping is clamped to the
 * node's real size, so callers must check b->size. `cached`=0 maps it O_SYNC
 * (Device memory: slow, no cache management, and glibc memset/memcpy can SIGBUS on
 * it — use word stores). `cached`=1 maps it write-back cacheable and every DMA must
 * be bracketed with mmfree_buf_sync_for_device (before the engine reads) and
 * mmfree_buf_sync_for_cpu (after it writes); non-coherent nodes only. */
int  mmfree_buf_open(mmfree_buf_t *b, const char *udmabuf_dev, size_t size, int cached);
void mmfree_buf_close(mmfree_buf_t *b);

/* Cache sync over the first `nbytes` of a cached buffer (no-op if non-cached).
 * _for_device cleans (CPU writes -> DDR); _for_cpu invalidates (DDR -> CPU reads). */
void mmfree_buf_sync_for_device(mmfree_buf_t *b, size_t nbytes);
void mmfree_buf_sync_for_cpu(mmfree_buf_t *b, size_t nbytes);

/* ---- status / IRQ ---- */

static inline uint32_t mmfree_status(const mmfree_ctx_t *ctx) {
    return *(volatile uint32_t *)((char *)ctx->core_regs + MMFREE_REG_STATUS);
}

/* Block on the Core IRQ via UIO read(), bounded by MMFREE_TIMEOUT_MS (ms, default
 * 5000, 0 = forever). Returns 0, -ETIMEDOUT, or another negative error. The caller
 * must then re-arm the UIO fd (uio_pdrv_genirq masks until acked);
 * mmfree_wait_done() does that. */
int  mmfree_wait_irq(mmfree_ctx_t *ctx);

/* Print Core status + every DMA channel state to stderr with root-cause hints.
 * Called automatically on timeout/error in the high-level ops. */
void mmfree_dump_state(mmfree_ctx_t *ctx, const char *what);

/* Ack the Core's internal IRQ register (separate from the UIO re-arm). */
static inline void mmfree_ack_irq(mmfree_ctx_t *ctx) {
    *(volatile uint32_t *)((char *)ctx->core_regs + MMFREE_REG_IRQ_ACK) = 1u;
}

/* ---- instruction issue (low-level) ---- */

/* Push a 128-bit instruction beat. Start the matching DMA(s) first, so beats can
 * flow as soon as the Core goes active. */
void mmfree_push_instr(mmfree_ctx_t *ctx, mmfree_instr_t inst);

/* Wait for completion (status spin, or UIO IRQ with MMFREE_POLL=0) and ack it.
 * Returns the status word read before the ack, or 0xFFFFFFFF on timeout/error. */
uint32_t mmfree_wait_done(mmfree_ctx_t *ctx);

/* ---- high-level operations ---- */

/* LOAD `n_activations` beats into the Core's activation BRAM. `bufs` has one
 * buffer per port; activations live in port 0's slice (the low bits of the wide
 * beat), ports 1..N-1 stream zeros. Every port must push the same beat count or
 * the CoreTop join deadlocks. */
int mmfree_load(mmfree_ctx_t *ctx, const mmfree_buf_t *bufs, uint32_t n_activations);

/* COMPUTE a rows x cols matmul. `bufs` has one weight buffer per port; port p
 * carries bits [p*128, (p+1)*128) of each wide beat. Beats are col-tile-major:
 * [t=0, n=0..N-1], [t=1, n=0..N-1], ... Length per port = mmfree_wt_port_bytes. */
int mmfree_compute(mmfree_ctx_t *ctx, const mmfree_buf_t *bufs,
                   uint32_t rows, uint32_t cols);

/* As mmfree_compute, but each port's DMA starts `byte_offset` bytes into its
 * weight buffer, so projections can sit resident in one shared udmabuf. The Core
 * ignores the instruction ptr for streaming ops; only the DMA address matters. */
int mmfree_compute_off(mmfree_ctx_t *ctx, const mmfree_buf_t *bufs,
                       uint32_t rows, uint32_t cols, uint64_t byte_offset);

/* As mmfree_compute_off, with the active-row count `batch` in the COMPUTE flags
 * (see mmfree_inst_compute_b; 0 = full batchSize). The weight stream length does
 * not depend on batch. */
int mmfree_compute_off_b(mmfree_ctx_t *ctx, const mmfree_buf_t *bufs,
                         uint32_t rows, uint32_t cols, uint64_t byte_offset,
                         uint32_t batch);

/* STORE `n_outputs` accumulator lanes to `buf` via DMA 0's S2MM
 * (n_outputs * geom.outLaneBytes bytes). */
int mmfree_store(mmfree_ctx_t *ctx, const mmfree_buf_t *buf, uint32_t n_outputs);

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif /* MMFREE_RUNTIME_H_ */
