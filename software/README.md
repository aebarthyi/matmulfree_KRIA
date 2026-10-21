# software/

PS-side userspace stack for the matmulfree ternary-matmul Core IP.

## Layout

| path | role |
|---|---|
| `include/mmfree_core.h` | register map, opcode/error constants, status-word layout, 128-bit instruction encoders. Mirrors `src/main/scala/control/AxiInstructionHandler.scala`. **Treat as the source of truth for any PS-side driver.** |
| `include/mmfree_dma.h` | minimal direct-register Xilinx AXI DMA driver (simple mode, MM2S + S2MM) |
| `include/mmfree_runtime.h`, `bench/mmfree_runtime.c` | userspace runtime: maps the Core + DMA register windows via `/dev/mem`, the u-dma-buf buffers and the UIO IRQ, and issues LOAD / COMPUTE / STORE as one unit each |
| `bench/bench.c` | synthetic per-projection sweep (`make bench` on the board) |
| `Makefile` | builds `build/bench` — natively on the KRIA, or cross with `CC=aarch64-linux-gnu-gcc` |
| `integration/` | MMfreeLM weight packer, `libmmfree` engine API and the `fpga_runner` decode runner — see `integration/README.md` |

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

The runtime adds the DMA side: MM2S streams activations and weights into `s_axis`, S2MM
drains `m_axis` into the output buffer. By default it polls the status word for completion
rather than waiting on the UIO IRQ.

## Environment

`bench` and the `fpga_runner` take their geometry from `MMFREE_*` environment variables,
which must match the loaded bitstream. The board Makefile exports them from the preset manifest;
set them by hand only when running a binary directly.

| variable | used by | meaning |
|---|---|---|
| `MMFREE_AWIDTH` `XDIM` `MAXACC` `MAXN` `MAXM` `BATCH` `SIGNED` `PACKED_WEIGHTS` | bench, fpga_runner | geometry (from `preset.env`) |
| `MMFREE_MANIFEST`, `MMFREE_STRICT=1` | runtime | cross-check the geometry against a `preset.env`; warn, or abort with `STRICT` |
| `MMFREE_POLL=0` | runtime | wait on the UIO IRQ instead of polling (default: poll) |
| `MMFREE_TIMEOUT_MS` | runtime | completion timeout, default 5000; 0 = wait forever |
| `MMFREE_DMA<i>`, `MMFREE_ACT<i>`, `MMFREE_WT<i>` | bench, fpga_runner | per-port DMA base / udmabuf path overrides (multi-port presets append the port index to the act/wt paths) |
| `MMFREE_SHAPES` | bench | `370m` / `1.3b` / `2.7b` (that model's projections + per-token rollup), `pow2` (cross sweep), or a custom name with `MMFREE_HIDDEN` / `INTER` / `VOCAB` / `LAYERS` |
| `MMFREE_VERIFY=0`, `MMFREE_REPS`, `MMFREE_CSV`, `MMFREE_CLK_MHZ` | bench | skip the host reference, best-of-N reps, force CSV on/off, clock for the efficiency ceiling |

`bench` packs its synthetic weights 2-bit only, so it refuses a `MMFREE_PACKED_WEIGHTS=1`
bitstream; the shipped presets are exercised through the `fpga_runner` (`make gate` / `run`).
