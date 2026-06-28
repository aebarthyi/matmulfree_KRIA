# software/

PS-side artifacts for the matmulfree ternary-matmul Core IP.

## Layout

- `include/mmfree_core.h` — single-header register map, opcode/error constants,
  status-word layout, and a 128-bit instruction encoder. Mirrors
  `src/main/scala/control/AxiInstructionHandler.scala`. **Treat as the
  source of truth for any PS-side driver.**

The host-side encoder check (`tests/encode_sanity.c`), the libmmfree helper unit
test, and the board smoke test live on the **`validation`** branch.

## Driver usage sketch

```c
#include "mmfree_core.h"

/* `base` points at the IP's AXI4 base address (mmapped to userspace, or the
 * device-tree register region in a kernel driver). All accesses must be
 * 128-bit single-beat for INSTR; 32-bit single-beat is fine for STATUS/ACK. */

mmfree_instr_t inst = mmfree_inst_load(buf_phys_addr, n_activations);
/* 128-bit MMIO store. On AArch64, use NEON: */
vst1q_u64((uint64_t *)(base + MMFREE_REG_INSTR), vld1q_u64(&inst.u64.lo));

/* Wait for IRQ, then check status. */
uint32_t s = *(volatile uint32_t *)(base + MMFREE_REG_STATUS);
if (MMFREE_STATUS_ERR(s) != MMFREE_ERR_NONE) { /* handle */ }

/* Ack the IRQ. */
*(volatile uint32_t *)(base + MMFREE_REG_IRQ_ACK) = 1;
```

A real driver should also walk the AXI-Stream DMA setup (S2MM for activations
and weights, MM2S for outputs); that lives outside this header for now.
