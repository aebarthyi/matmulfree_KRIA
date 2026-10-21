/*
 * mmfree_dma.h — minimal direct-register driver for Xilinx AXI DMA (PG021) in simple
 * (non-SG) mode, MM2S and S2MM. Data width is set per preset by vivado/bd.tcl; 40-bit
 * addressing. Completion is polled via DMASR.Idle (the Core IRQ signals op completion).
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

/* Inline helpers. `regs` is the mmap'd base of the AXI DMA IP. */

static inline uint32_t mmfree_dma_rd(volatile void *regs, uint32_t off) {
    return *(volatile uint32_t *)((char *)regs + off);
}
static inline void mmfree_dma_wr(volatile void *regs, uint32_t off, uint32_t v) {
    *(volatile uint32_t *)((char *)regs + off) = v;
}

/* Bound on every register spin (~1 us per /dev/mem read, so ~10 s), so a wedged
 * engine or unprogrammed PL returns an error instead of hanging. */
#define MMFREE_DMA_SPIN_LIMIT   10000000u

/* Soft-reset the MM2S channel only — for the MM2S-only DMAs 1..N-1 (no S2MM
 * register block). Returns 0 on ok, -1 on timeout. */
static inline int mmfree_dma_mm2s_reset(volatile void *regs) {
    uint32_t spins;
    mmfree_dma_wr(regs, AXI_DMA_MM2S_DMACR, DMACR_RESET);
    for (spins = 0; mmfree_dma_rd(regs, AXI_DMA_MM2S_DMACR) & DMACR_RESET; spins++)
        if (spins >= MMFREE_DMA_SPIN_LIMIT) return -1;
    return 0;
}

/* Soft-reset both channels. Returns 0 on ok, -1 if a channel never left reset. */
static inline int mmfree_dma_reset(volatile void *regs) {
    uint32_t spins;
    if (mmfree_dma_mm2s_reset(regs) < 0) return -1;
    mmfree_dma_wr(regs, AXI_DMA_S2MM_DMACR, DMACR_RESET);
    for (spins = 0; mmfree_dma_rd(regs, AXI_DMA_S2MM_DMACR) & DMACR_RESET; spins++)
        if (spins >= MMFREE_DMA_SPIN_LIMIT) return -1;
    return 0;
}

/* Start an MM2S transfer of `bytes` from physical address `pa`; poll
 * mmfree_dma_mm2s_wait() for idle. Returns 0 on ok, -1 if the channel stays halted. */
static inline int mmfree_dma_mm2s_start(volatile void *regs, uint64_t pa, uint32_t bytes) {
    uint32_t spins;
    mmfree_dma_wr(regs, AXI_DMA_MM2S_DMACR, DMACR_RS);
    for (spins = 0; mmfree_dma_rd(regs, AXI_DMA_MM2S_DMASR) & DMASR_HALTED; spins++)
        if (spins >= MMFREE_DMA_SPIN_LIMIT) return -1;

    mmfree_dma_wr(regs, AXI_DMA_MM2S_SA,     (uint32_t)(pa & 0xFFFFFFFFu));
    mmfree_dma_wr(regs, AXI_DMA_MM2S_SA_MSB, (uint32_t)(pa >> 32));
    /* Writing LENGTH last *commits* the transfer. */
    mmfree_dma_wr(regs, AXI_DMA_MM2S_LENGTH, bytes);
    return 0;
}

/* Wait for MM2S idle. Returns 0 on clean completion, -1 on DMA error,
 * -2 on timeout (engine still busy — upstream/downstream stall). */
static inline int mmfree_dma_mm2s_wait(volatile void *regs) {
    uint32_t sr, spins = 0;
    do {
        sr = mmfree_dma_rd(regs, AXI_DMA_MM2S_DMASR);
        if (sr & DMASR_ANY_ERR) return -1;
        if (spins++ >= MMFREE_DMA_SPIN_LIMIT) return -2;
    } while (!(sr & DMASR_IDLE));
    return 0;
}

/* S2MM equivalents. */
static inline int mmfree_dma_s2mm_start(volatile void *regs, uint64_t pa, uint32_t bytes) {
    uint32_t spins;
    mmfree_dma_wr(regs, AXI_DMA_S2MM_DMACR, DMACR_RS);
    for (spins = 0; mmfree_dma_rd(regs, AXI_DMA_S2MM_DMASR) & DMASR_HALTED; spins++)
        if (spins >= MMFREE_DMA_SPIN_LIMIT) return -1;

    mmfree_dma_wr(regs, AXI_DMA_S2MM_DA,     (uint32_t)(pa & 0xFFFFFFFFu));
    mmfree_dma_wr(regs, AXI_DMA_S2MM_DA_MSB, (uint32_t)(pa >> 32));
    mmfree_dma_wr(regs, AXI_DMA_S2MM_LENGTH, bytes);
    return 0;
}

static inline int mmfree_dma_s2mm_wait(volatile void *regs) {
    uint32_t sr, spins = 0;
    do {
        sr = mmfree_dma_rd(regs, AXI_DMA_S2MM_DMASR);
        if (sr & DMASR_ANY_ERR) return -1;
        if (spins++ >= MMFREE_DMA_SPIN_LIMIT) return -2;
    } while (!(sr & DMASR_IDLE));
    return 0;
}

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif /* MMFREE_DMA_H_ */
