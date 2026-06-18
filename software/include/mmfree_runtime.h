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

/* Systolic-array geometry. Must match the loaded bitstream's CoreConfig — a
 * mismatch makes the Core wait forever on s_axis/m_axis (caught only by the
 * completion timeout). The inputs (top half) are supplied by the caller; the
 * derived fields (bottom half) are filled by mmfree_geom_init. Everything that
 * used to be a compile-time -DBENCH_* constant now reads off this struct, so
 * one binary adapts to any array size without recompiling. */
typedef struct mmfree_geom {
    /* inputs (mirror CoreConfig) */
    uint32_t aWidth;            /* activation bit width */
    uint32_t xDim;              /* systolic array columns */
    uint32_t maxAcc;            /* max accumulation cycles (bounds N) */
    uint32_t maxN;              /* max inner dim swept */
    uint32_t maxM;              /* max output dim swept */
    /* derived */
    uint32_t nLanes;            /* aWidth / 2 (ternary lanes per PE) */
    uint32_t outLanesPerTile;   /* xDim * nLanes = output cols per col-tile */
    uint32_t sAxisBytes;        /* xDim * aWidth / 8 = bytes per s_axis beat */
    uint32_t accWidth;          /* log2Up(maxAcc) + aWidth = real acc bits */
    uint32_t outLaneWidth;      /* nextPow2(accWidth) = m_axis lane bits */
    uint32_t outLaneBytes;      /* outLaneWidth / 8 = bytes per output lane */
    uint32_t numPorts;          /* s_axis port count = min(4, ceil(bits/128)) — mirrors CoreConfig */
    uint32_t portBytes;         /* sAxisBytes / numPorts = bytes per port per beat */
} mmfree_geom_t;

/* Fill the derived fields of `g` from the five inputs. Returns 0 on success,
 * -1 if the geometry is unrepresentable (xDim*aWidth not a whole number of
 * bytes, or a zero dimension). */
int mmfree_geom_init(mmfree_geom_t *g, uint32_t aWidth, uint32_t xDim,
                     uint32_t maxAcc, uint32_t maxN, uint32_t maxM);

/* Max parallel DMAs/HP ports the input stream can be split across (HP0..HP3). */
#define MMFREE_MAX_DMA 4

/* Opaque handle. Caller treats as PoD. */
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
} mmfree_buf_t;

/* ---------------------------------------------------------------------- */
/* Setup / teardown                                                       */
/* ---------------------------------------------------------------------- */

/* Open and mmap Core regs (via /dev/mem at `core_phys`, length core_size),
 * `num_dma` AXI DMA register windows (via /dev/mem at dma_phys[0..num_dma-1],
 * each dma_size long; DMA 0 must be the full MM2S+S2MM engine, 1..N-1 are
 * MM2S-only), and open the UIO device at `uio_dev` (e.g. "/dev/uio0"). On any
 * failure returns -errno and leaves ctx in a partially-populated state —
 * caller should still call mmfree_close to clean up. */
int  mmfree_open(mmfree_ctx_t *ctx,
                 uint64_t core_phys, size_t core_size,
                 const uint64_t *dma_phys, uint32_t num_dma, size_t dma_size,
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

/* Block on Core IRQ (via uio read()), bounded by MMFREE_TIMEOUT_MS (default
 * 5000; 0 = wait forever). Returns 0 on irq, -ETIMEDOUT on timeout, negative
 * on other errors. After return, caller MUST also write irq_ack back to the
 * UIO fd to re-enable interrupts (uio_pdrv_genirq masks until the userspace
 * process acks). The runtime's mmfree_wait_done() does both. */
int  mmfree_wait_irq(mmfree_ctx_t *ctx);

/* Print core status + both DMA channel states to stderr, with hints mapping
 * the fingerprint to a likely root cause. Called automatically on any
 * timeout / error inside the high-level ops; also useful standalone. */
void mmfree_dump_state(mmfree_ctx_t *ctx, const char *what);

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

/* LOAD activations into Core's BRAM. `bufs` is an array of ctx->num_dma
 * buffers, one per s_axis port (port 0 = real activation slices, ports 1..N-1
 * = all-zero — the value sits in the low aWidth bits of the wide beat, which
 * is port 0's slice). `n_activations` is the inner-dim length N. Each port
 * pushes n_activations beats of geom.portBytes — every port MUST push the
 * same beat count or the CoreTop join deadlocks.
 *
 * Kicks all N MM2S engines first, then the LOAD_ACT instruction, then waits
 * for Core IRQ + all DMAs idle. */
int mmfree_load(mmfree_ctx_t *ctx, const mmfree_buf_t *bufs, uint32_t n_activations);

/* COMPUTE rows x cols matmul. `bufs` is an array of ctx->num_dma weight
 * buffers, one per s_axis port: port p carries bit-slice [p*128, (p+1)*128)
 * of each wide beat = ternary lanes [p*64, (p+1)*64), col-tile-major (see
 * bench.c — Core consumes [t=0,n=0..N-1], [t=1,n=0..N-1], ...). Per-port
 * bytes = rows * ceil(cols/outLanesPerTile) * geom.portBytes. */
int mmfree_compute(mmfree_ctx_t *ctx, const mmfree_buf_t *bufs,
                   uint32_t rows, uint32_t cols);

/* As mmfree_compute, but each port's MM2S DMA starts `byte_offset` bytes into
 * its weight buffer — lets many projections share one resident weight udmabuf
 * (Phase C / libmmfree), each computed in place at its packed offset. The Core
 * addresses the weight stream via the DMA source register, so the offset is
 * applied there (and mirrored into the instruction ptr, which the Core ignores
 * for streaming ops). byte_offset=0 is identical to mmfree_compute. */
int mmfree_compute_off(mmfree_ctx_t *ctx, const mmfree_buf_t *bufs,
                       uint32_t rows, uint32_t cols, uint64_t byte_offset);

/* STORE n_outputs accumulated lanes back to `buf`. For K26_Bench with
 * outBeatLanes=1 + outLaneWidth=32: bytes = n_outputs * 4. */
int mmfree_store(mmfree_ctx_t *ctx, const mmfree_buf_t *buf, uint32_t n_outputs);

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif /* MMFREE_RUNTIME_H_ */
