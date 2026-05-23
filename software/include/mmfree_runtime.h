/*
 * mmfree_runtime.h — Userspace runtime for the mmfree Core IP on KRIA.
 *
 * Pulls together:
 *   - mmap of CoreTop's AXI4 register window (instruction MMIO + status + IRQ ack)
 *   - mmap of an AXI DMA IP for both s_axis (MM2S) and m_axis (S2MM)
 *   - udmabuf-backed contiguous physical buffers for activations / weights / outputs
 *   - UIO-based wait for Core's irq line
 *   - High-level mmfree_load / mmfree_compute / mmfree_store helpers that issue the
 *     instruction, kick the DMA, and wait for completion as one unit
 *
 * Assumes Ubuntu-on-KRIA or similar where:
 *   - /dev/udmabuf{N} provides contiguous physical buffers (from the udmabuf driver)
 *   - /dev/uio{N} is bound to Core's irq line (via uio_pdrv_genirq)
 *   - The Core and AXI DMA register windows are reachable through /dev/mem
 *
 * All accesses to MMIO must be 32-bit aligned for AXI DMA, but Core's INSTR register
 * is 128-bit. We use NEON vst1q on aarch64 so the single AXI beat is a single 128-bit
 * write (no cache-line tearing risk because of the volatile cast).
 */

#ifndef MMFREE_RUNTIME_H_
#define MMFREE_RUNTIME_H_

#include <stdint.h>
#include <stddef.h>

#include "mmfree_core.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Opaque handle. Caller treats as PoD. */
typedef struct mmfree_ctx {
    volatile void *core_regs;   /* mmap'd Core AXI4 register base */
    volatile void *dma_regs;    /* mmap'd AXI DMA register base */
    int            uio_fd;      /* /dev/uioN bound to Core irq */
    size_t         core_size;
    size_t         dma_size;
} mmfree_ctx_t;

/* A udmabuf-backed contiguous buffer. */
typedef struct mmfree_buf {
    void     *vaddr;            /* userspace pointer */
    uint64_t  paddr;            /* physical bus address (use in instructions / DMA) */
    size_t    size;             /* total bytes */
    int       fd;
} mmfree_buf_t;

/* ---------------------------------------------------------------------- */
/* Setup / teardown                                                       */
/* ---------------------------------------------------------------------- */

/* Open and mmap Core regs (via /dev/mem at `core_phys`, length core_size),
 * AXI DMA regs (via /dev/mem at `dma_phys`, length dma_size), and open the
 * UIO device at `uio_dev` (e.g. "/dev/uio0"). On any failure returns -errno
 * and leaves ctx in a partially-populated state — caller should still call
 * mmfree_close to clean up. */
int  mmfree_open(mmfree_ctx_t *ctx,
                 uint64_t core_phys, size_t core_size,
                 uint64_t dma_phys,  size_t dma_size,
                 const char *uio_dev);
void mmfree_close(mmfree_ctx_t *ctx);

/* Allocate a contiguous physical buffer via udmabuf. `udmabuf_dev` is the
 * path like "/dev/udmabuf0". The buffer's size is whatever the udmabuf was
 * created with — caller passes `size` to limit what mmfree maps. */
int  mmfree_buf_open(mmfree_buf_t *b, const char *udmabuf_dev, size_t size);
void mmfree_buf_close(mmfree_buf_t *b);

/* ---------------------------------------------------------------------- */
/* Status / IRQ                                                           */
/* ---------------------------------------------------------------------- */

static inline uint32_t mmfree_status(const mmfree_ctx_t *ctx) {
    return *(volatile uint32_t *)((char *)ctx->core_regs + MMFREE_REG_STATUS);
}

/* Block on Core IRQ (via uio read()). Returns 0 on irq, -1 on error.
 * After return, caller MUST also write irq_ack back to the UIO fd to
 * re-enable interrupts (uio_pdrv_genirq masks until the userspace process
 * acks). The runtime's mmfree_wait_done() does both. */
int  mmfree_wait_irq(mmfree_ctx_t *ctx);

/* Ack the Core's internal IRQ register. Separate from the UIO ack. */
static inline void mmfree_ack_irq(mmfree_ctx_t *ctx) {
    *(volatile uint32_t *)((char *)ctx->core_regs + MMFREE_REG_IRQ_ACK) = 1u;
}

/* ---------------------------------------------------------------------- */
/* Instruction issue (low-level)                                          */
/* ---------------------------------------------------------------------- */

/* Push a 128-bit instruction beat. Must be paired with the right DMA setup
 * BEFORE this is called (the DMA needs to be primed so it can supply or
 * consume beats as soon as Core moves into the active state). */
void mmfree_push_instr(mmfree_ctx_t *ctx, mmfree_instr_t inst);

/* Wait for Core's IRQ + ack both UIO and Core. Returns the status word read
 * just before the ack, so the caller can check err / last_op fields. */
uint32_t mmfree_wait_done(mmfree_ctx_t *ctx);

/* ---------------------------------------------------------------------- */
/* High-level operations                                                  */
/* ---------------------------------------------------------------------- */

/* LOAD activations from `buf` (paddr already populated) into Core's BRAM.
 * `n_activations` is the inner-dim length N. Bytes pushed = n_activations *
 * s_axis_bytes (= xDim*aWidth/8 per beat). For K26_Bench (xDim=4, aWidth=16):
 * 8 bytes/beat → bytes = 8 * N.
 *
 * Issues an AXI DMA MM2S kick first, then the LOAD_ACT instruction, then
 * waits for Core IRQ + DMA idle. */
int mmfree_load(mmfree_ctx_t *ctx, const mmfree_buf_t *buf, uint32_t n_activations);

/* COMPUTE rows x cols matmul. Weights buffer holds rows * cols * 2 bits, packed
 * as (xDim * nLanes * 2)-bit beats = 64-bit beats for K26_Bench (aWidth=16).
 * Total beats = (rows * cols) / outLanesPerTile, laid out col-tile-major
 * (see bench.c — Core consumes [t=0,n=0..N-1], [t=1,n=0..N-1], ...). */
int mmfree_compute(mmfree_ctx_t *ctx, const mmfree_buf_t *buf,
                   uint32_t rows, uint32_t cols);

/* STORE n_outputs accumulated lanes back to `buf`. For K26_Bench with
 * outBeatLanes=1 + outLaneWidth=32: bytes = n_outputs * 4. */
int mmfree_store(mmfree_ctx_t *ctx, const mmfree_buf_t *buf, uint32_t n_outputs);

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif /* MMFREE_RUNTIME_H_ */
