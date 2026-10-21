/*
 * mmfree_core.h — PS-side register map and instruction encoding for the Core IP.
 * Mirrors control/AxiInstructionHandler.scala (RegMap, Opcode, ErrorCode, StatusReg)
 * and control/Core.scala.
 *
 *   - Addresses are byte offsets from the IP's AXI4 base.
 *   - Instructions are single 128-bit beats to MMFREE_REG_INSTR (AWLEN/ARLEN = 0);
 *     the driver must use one 128-bit MMIO store (NEON vst1q on AArch64).
 *   - Status is a 32-bit word at MMFREE_REG_STATUS (a 128-bit read returns zeros above).
 *   - IRQ is level-high; write any value to MMFREE_REG_IRQ_ACK to clear.
 */

#ifndef MMFREE_CORE_H_
#define MMFREE_CORE_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---------- Register-map offsets (12-bit aperture, 16B-aligned) ---------- */

#define MMFREE_REG_INSTR        0x000u  /* W:   push 128-bit instruction beat */
#define MMFREE_REG_STATUS       0x010u  /* R:   packed 32-bit status word */
#define MMFREE_REG_IRQ_ACK      0x020u  /* W:   any value clears irqPending */

/* ---------- Opcodes (8-bit) ---------- */

#define MMFREE_OP_NOP           0x00u
#define MMFREE_OP_LOAD_ACT      0x01u  /* dim0 = #activations; ptr = src buf */
#define MMFREE_OP_COMPUTE_MM    0x02u  /* dim0 = rows; dim1 = cols; ptr = weights buf */
#define MMFREE_OP_STORE_OUT     0x03u  /* dim0 = #outputs; ptr = dst buf */

/* ---------- Error codes (8-bit) ---------- */

#define MMFREE_ERR_NONE             0x00u
#define MMFREE_ERR_INVALID_OPCODE   0x01u

/* ---------- Status word bit layout (32-bit, low-half of read data) -------- */

#define MMFREE_STATUS_BUSY_SHIFT        0
#define MMFREE_STATUS_BUSY_MASK         (1u << MMFREE_STATUS_BUSY_SHIFT)

#define MMFREE_STATUS_LAST_OP_SHIFT     8
#define MMFREE_STATUS_LAST_OP_MASK      (0xFFu << MMFREE_STATUS_LAST_OP_SHIFT)

#define MMFREE_STATUS_ERR_SHIFT         16
#define MMFREE_STATUS_ERR_MASK          (0xFFu << MMFREE_STATUS_ERR_SHIFT)

#define MMFREE_STATUS_IRQ_PEND_SHIFT    24
#define MMFREE_STATUS_IRQ_PEND_MASK     (1u << MMFREE_STATUS_IRQ_PEND_SHIFT)

#define MMFREE_STATUS_BUSY(s)       (((s) & MMFREE_STATUS_BUSY_MASK)     != 0)
#define MMFREE_STATUS_LAST_OP(s)    (((s) & MMFREE_STATUS_LAST_OP_MASK)  >> MMFREE_STATUS_LAST_OP_SHIFT)
#define MMFREE_STATUS_ERR(s)        (((s) & MMFREE_STATUS_ERR_MASK)      >> MMFREE_STATUS_ERR_SHIFT)
#define MMFREE_STATUS_IRQ_PEND(s)   (((s) & MMFREE_STATUS_IRQ_PEND_MASK) != 0)

/* ---------- Instruction encoding (128-bit beat, LSB-first) ----------------
 *
 *   [  7:  0] opcode  (8b)
 *   [ 47:  8] ptr     (40b — physical / SMMU-translated bus address)
 *   [ 79: 48] dim0    (32b)
 *   [111: 80] dim1    (32b)
 *   [127:112] flags   (16b; COMPUTE: active batch count, 0 = full batchSize)
 *
 * dim0 / dim1 semantics by opcode:
 *   LOAD_ACT      : dim0 = #activations, dim1 = 0
 *   COMPUTE_MM    : dim0 = rows (= activation length / inner dim),
 *                   dim1 = cols (= output length)
 *   STORE_OUT     : dim0 = #outputs, dim1 = 0
 */

typedef union {
    struct { uint64_t lo, hi; } u64;     /* two 64-bit halves */
    uint32_t                  u32[4];    /* four 32-bit halves (LSB-first) */
} mmfree_instr_t;

static inline mmfree_instr_t mmfree_encode(uint8_t  opcode,
                                           uint64_t ptr,    /* fits in 40 bits */
                                           uint32_t dim0,
                                           uint32_t dim1,
                                           uint16_t flags)
{
    mmfree_instr_t i;
    const uint64_t ptr40 = ptr & ((uint64_t)0xFFFFFFFFFFULL);
    /* lo: opcode[7:0] | ptr[47:8] | dim0[15:0] in [63:48]
     * hi: dim0[31:16] in [15:0] | dim1[47:16] | flags[63:48]
     */
    i.u64.lo = ((uint64_t)opcode)
             | (ptr40                            << 8)
             | (((uint64_t)dim0 & 0xFFFFu)       << 48);
    i.u64.hi = (((uint64_t)dim0 >> 16) & 0xFFFFu)
             | (((uint64_t)dim1)                 << 16)
             | (((uint64_t)flags)                << 48);
    return i;
}

static inline mmfree_instr_t mmfree_inst_load(uint64_t ptr, uint32_t n_activations)
{ return mmfree_encode(MMFREE_OP_LOAD_ACT, ptr, n_activations, 0, 0); }

/* COMPUTE: `batch` (active-row count b) rides the flags field; the Core decodes it
 * as cmpBatch and drains only b of B rows. batch==0 means the full compile-time
 * batchSize. Otherwise 1 <= batch <= bitstream batchSize. */
static inline mmfree_instr_t mmfree_inst_compute_b(uint64_t weights_ptr,
                                                   uint32_t rows, uint32_t cols,
                                                   uint16_t batch)
{ return mmfree_encode(MMFREE_OP_COMPUTE_MM, weights_ptr, rows, cols, batch); }

static inline mmfree_instr_t mmfree_inst_store(uint64_t ptr, uint32_t n_outputs)
{ return mmfree_encode(MMFREE_OP_STORE_OUT, ptr, n_outputs, 0, 0); }

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif /* MMFREE_CORE_H_ */
