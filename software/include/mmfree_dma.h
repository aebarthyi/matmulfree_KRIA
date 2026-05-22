/*
 * mmfree_dma.h — Minimal direct-register driver for Xilinx AXI DMA (PG021) in
 * SIMPLE (non-scatter-gather) mode. Supports both MM2S (memory → stream) and
 * S2MM (stream → memory) channels.
 *
 * Used by mmfree_runtime.c to push activations / weights into Core's s_axis
 * and pull outputs out of Core's m_axis. The IP is assumed configured at 32-bit
 * data width (matches K26_Bench preset) with 40-bit address support enabled.
 *
 * This is intentionally bare: no SG, no IRQ wiring (we poll DMASR.Idle). The
 * Core IRQ is used for matmul completion; DMA completion is implied by the
 * single-tlast packet boundary.
 */

#ifndef MMFREE_DMA_H_
#define MMFREE_DMA_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Register offsets — see PG021 Table 2-1 / 2-3. */
#define AXI_DMA_MM2S_DMACR      0x00u
#define AXI_DMA_MM2S_DMASR      0x04u
#define AXI_DMA_MM2S_SA         0x18u  /* low 32 bits of source addr */
#define AXI_DMA_MM2S_SA_MSB     0x1Cu  /* upper bits when addr width > 32 */
#define AXI_DMA_MM2S_LENGTH     0x28u

#define AXI_DMA_S2MM_DMACR      0x30u
#define AXI_DMA_S2MM_DMASR      0x34u
#define AXI_DMA_S2MM_DA         0x48u
#define AXI_DMA_S2MM_DA_MSB     0x4Cu
#define AXI_DMA_S2MM_LENGTH     0x58u

/* DMACR bits */
#define DMACR_RS                (1u << 0)   /* run/stop: 1 = run */
#define DMACR_RESET             (1u << 2)   /* soft reset (self-clearing) */
#define DMACR_IOC_IRQ_EN        (1u << 12)
#define DMACR_ERR_IRQ_EN        (1u << 14)

/* DMASR bits */
#define DMASR_HALTED            (1u << 0)
#define DMASR_IDLE              (1u << 1)
#define DMASR_DMA_INT_ERR       (1u << 4)
#define DMASR_DMA_SLV_ERR       (1u << 5)
#define DMASR_DMA_DEC_ERR       (1u << 6)
#define DMASR_IOC_IRQ           (1u << 12)
#define DMASR_ERR_IRQ           (1u << 14)

#define DMASR_ANY_ERR \
    (DMASR_DMA_INT_ERR | DMASR_DMA_SLV_ERR | DMASR_DMA_DEC_ERR | DMASR_ERR_IRQ)

/* ---------------------------------------------------------------- */
/* Inline helpers. `regs` is the mmap'd base of the AXI DMA IP.     */
/* ---------------------------------------------------------------- */

static inline uint32_t mmfree_dma_rd(volatile void *regs, uint32_t off) {
    return *(volatile uint32_t *)((char *)regs + off);
}
static inline void mmfree_dma_wr(volatile void *regs, uint32_t off, uint32_t v) {
    *(volatile uint32_t *)((char *)regs + off) = v;
}

/* Soft-reset both channels. Spin until RESET bit self-clears. */
static inline void mmfree_dma_reset(volatile void *regs) {
    mmfree_dma_wr(regs, AXI_DMA_MM2S_DMACR, DMACR_RESET);
    while (mmfree_dma_rd(regs, AXI_DMA_MM2S_DMACR) & DMACR_RESET) { }
    mmfree_dma_wr(regs, AXI_DMA_S2MM_DMACR, DMACR_RESET);
    while (mmfree_dma_rd(regs, AXI_DMA_S2MM_DMACR) & DMACR_RESET) { }
}

/* Start an MM2S transfer of `bytes` from physical address `pa`. Returns 0 on ok.
 * After this returns, the engine pulls beats into the stream until LENGTH
 * bytes are drained or upstream tready falls. Poll mmfree_dma_mm2s_wait() for
 * idle. */
static inline int mmfree_dma_mm2s_start(volatile void *regs, uint64_t pa, uint32_t bytes) {
    /* Bring channel out of halt by setting RS. */
    mmfree_dma_wr(regs, AXI_DMA_MM2S_DMACR, DMACR_RS);
    /* Wait for !halted. */
    while (mmfree_dma_rd(regs, AXI_DMA_MM2S_DMASR) & DMASR_HALTED) { }

    mmfree_dma_wr(regs, AXI_DMA_MM2S_SA,     (uint32_t)(pa & 0xFFFFFFFFu));
    mmfree_dma_wr(regs, AXI_DMA_MM2S_SA_MSB, (uint32_t)(pa >> 32));
    /* Writing LENGTH last *commits* the transfer. */
    mmfree_dma_wr(regs, AXI_DMA_MM2S_LENGTH, bytes);
    return 0;
}

/* Wait for MM2S idle. Returns 0 on clean completion, -1 on DMA error. */
static inline int mmfree_dma_mm2s_wait(volatile void *regs) {
    uint32_t sr;
    do {
        sr = mmfree_dma_rd(regs, AXI_DMA_MM2S_DMASR);
        if (sr & DMASR_ANY_ERR) return -1;
    } while (!(sr & DMASR_IDLE));
    return 0;
}

/* Same pair for S2MM. */
static inline int mmfree_dma_s2mm_start(volatile void *regs, uint64_t pa, uint32_t bytes) {
    mmfree_dma_wr(regs, AXI_DMA_S2MM_DMACR, DMACR_RS);
    while (mmfree_dma_rd(regs, AXI_DMA_S2MM_DMASR) & DMASR_HALTED) { }

    mmfree_dma_wr(regs, AXI_DMA_S2MM_DA,     (uint32_t)(pa & 0xFFFFFFFFu));
    mmfree_dma_wr(regs, AXI_DMA_S2MM_DA_MSB, (uint32_t)(pa >> 32));
    mmfree_dma_wr(regs, AXI_DMA_S2MM_LENGTH, bytes);
    return 0;
}

static inline int mmfree_dma_s2mm_wait(volatile void *regs) {
    uint32_t sr;
    do {
        sr = mmfree_dma_rd(regs, AXI_DMA_S2MM_DMASR);
        if (sr & DMASR_ANY_ERR) return -1;
    } while (!(sr & DMASR_IDLE));
    return 0;
}

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif /* MMFREE_DMA_H_ */
