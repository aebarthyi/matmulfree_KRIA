# matmulfree_KRIA

A Chisel3 hardware implementation of a ternary-accumulating systolic array for matmulfree LLM inference, packaged as an AXI4 IP for the Xilinx KRIA SoM (KV260 / KR260) with userspace driver and benchmark.

> "Matmulfree" here means weights are restricted to the ternary set `{-1, 0, +1}`, so every multiply collapses to an add, subtract, or hold. No DSP slices are consumed by the multiply itself — the array is built entirely from adders.

## Architecture

```
        ┌──────────────────────────────────────────────────────────┐
        │                       CoreTop (IP)                       │
        │                                                          │
        │   AXI4 slave  ──► AxiInstructionHandler ──► Core FSM     │
        │   (s_axi)                                      │         │
        │                                                ▼         │
        │   AXI-Stream  ──► [LOAD] ──► actBram ──► SystolicArray   │
        │   (s_axis)        [COMPUTE] ──► weights ──►   ▲          │
        │                                               │ accum    │
        │   AXI-Stream  ◄──── outBram ◄── per-tile-pass │          │
        │   (m_axis)        [STORE]                                │
        │                                                          │
        │   irq         ──► PS IRQ (pl_ps_irq)                     │
        └──────────────────────────────────────────────────────────┘
```

The Core exposes three external interfaces:
- **`s_axi`** — AXI4 slave for 128-bit instruction MMIO + status reads + IRQ ack.
- **`s_axis`** — AXI4-Stream slave; time-multiplexed between **activations** during `LOAD_ACT` and **weights** during `COMPUTE_MM`. Activations land in on-chip BRAM and stay there; weights stream from DRAM each cycle.
- **`m_axis`** — AXI4-Stream master; emits accumulator lanes during `STORE_OUT`.

The compute primitive is a `yDim × xDim` grid of ternary-MAC PEs. Each PE holds `aWidth/2` lane-accumulators; per cycle it consumes `xDim·aWidth/2` ternary weights (broadcast across the batch dim) + one activation per batch row, producing `outLanesPerTile = xDim · aWidth/2` accumulators per array pass. The Core tiles the output column dimension `M` across multiple array passes, reading the same activation BRAM for each tile.

See `src/main/scala/control/CoreConfig.scala` for the full parameter set and the named presets.

## Repository layout

```
src/main/scala/
  systolic/          PE.scala, SystolicArray.scala — the compute primitive
  control/           Core.scala (FSM + BRAMs), CoreTop.scala (Vivado-facing wrapper),
                     AxiInstructionHandler.scala (instruction MMIO),
                     CoreConfig.scala (parameter holder + presets),
                     Emit*.scala (Verilog emission entry points)
  vector/            Vector.scala — vector-of-PEs building block

src/test/scala/
  systolic/PESpec.scala, vector/VectorSpec.scala — unit tests for the compute primitives
  control/{CoreSpec, AxiInstructionHandlerSpec, BenchMirrorSpec, CoreConfigSpec}.scala

software/            PS-side userspace stack
  include/           Single-header register / instruction / runtime API
  bench/             bench.c (sweep harness), mmfree_runtime.c (driver glue)
  tests/             encode_sanity.c (host-side encoder check)
  Makefile           Native build on the KRIA (or cross-compile via CC=)

scripts/
  BOARD_BRINGUP.md   End-to-end Vivado → KRIA bring-up walkthrough
  core_bench_overlay.dts.in  Device-tree overlay template
  gen_overlay.tcl    xsct script: .xsa → .dtbo + .bit + shell.json
  gen_overlay.sh     Convenience wrapper around gen_overlay.tcl
  ooc_synth.tcl      Out-of-context synthesis driver for each preset

generated/<preset>/  Verilog output of EmitCore (one dir per preset)
build/               Build artifacts (Mill, OOC synth, overlay outputs)
```

## Prerequisites

**Host (Chisel build + sim):**
- JDK 11 or newer
- Verilator (`sudo apt install verilator`) — for ChiselSim
- No need to install sbt or Mill; the repo ships a `./mill` bootstrap

**Host (KRIA bring-up):**
- Xilinx Vivado / Vitis 2024.x (for `vivado`, `xsct`, IP packaging, bitstream gen)
- `dtc` (`sudo apt install device-tree-compiler`)
- `unzip`

**KRIA target:**
- Ubuntu-on-KRIA (22.04 or 24.04 LTS Xilinx image)
- `u-dma-buf` kernel module loaded
- `xmutil` (ships with the Xilinx Ubuntu image)

## Quickstart

### 1. Clone

```bash
git clone git@github.com:aebarthyi/matmulfree_KRIA.git
cd matmulfree_KRIA
```

### 2. Run the test suite

```bash
./mill matmulfree_KRIA.test          # all suites
./mill matmulfree_KRIA.test.testOnly control.CoreSpec        # one suite
./mill matmulfree_KRIA.test.testOnly control.BenchMirrorSpec # bench/Core agreement test
```

SBT works too if you prefer it:
```bash
sbt test
```

Expected: ~100 tests across `PESpec`, `VectorSpec`, `CoreSpec`, `AxiInstructionHandlerSpec`, `BenchMirrorSpec`, `CoreConfigSpec`. First run is slow (Verilator compiles each DUT); subsequent runs reuse the cache.

### 3. Emit SystemVerilog for a preset

```bash
./mill matmulfree_KRIA.runMain control.EmitCore k26_bench
# → generated/k26_bench/{CoreTop.sv, Core.sv, ...}
```

Available presets (see `CoreConfig.scala:87-150`):

| Preset       | xDim | batch | aWidth | maxN | maxM  | s_axis | m_axis  | Notes |
|--------------|------|-------|--------|------|-------|--------|---------|-------|
| `default`    |  8   |   1   |   8    | 4096 |  1024 |  64 b  | 1024 b  | General-purpose |
| `tiny`       |  2   |   2   |   4    |    8 |    16 |   8 b  |   32 b  | Fast simulation |
| `k26_small`  |  4   |   1   |   8    |  256 |   256 |  32 b  |  512 b  | KRIA tiny |
| `k26_medium` |  8   |   1   |   8    |  512 |   512 |  64 b  | 1024 b  | KRIA mid |
| `k26_large`  | 16   |   1   |   8    | 1024 |  1024 | 128 b  | 2048 b  | KRIA full |
| `k26_bench`  |  4   |   1   |  16    | 1024 |  1024 |  64 b  |   32 b  | First-board bring-up (current target) |
| `k26_lm_head`| 64   |   1   |   8    | 4096 | 32000 | 512 b  | 1024 b  | LM head for matmulfree HGRN |

`s_axis` width is auto-derived as `max(xDim, batchSize) × aWidth`. `m_axis` width is `outBeatLanes × outLaneWidth`, where `outBeatLanes` defaults to `outLanesPerTile` (no chunking) but can be set explicitly to chunk wide outputs into multiple smaller beats — `k26_bench` and `k26_lm_head` both use chunking.

The default preset directory is `generated/<preset_name>/`; override with `EmitCore <preset> <dir>`.

### 4. (Optional) Out-of-context synthesis

To get LUT / FF / BRAM numbers without a full board build:

```bash
vivado -mode batch -source scripts/ooc_synth.tcl -tclargs k26_bench
# → build/vivado-ooc/k26_bench/
```

## KRIA board bring-up

Full walkthrough is in `scripts/BOARD_BRINGUP.md`. High-level flow:

### 1. Package and build in Vivado

1. **Package IP**: `Tools → Create and Package IP → Package a specified directory` pointing at `generated/k26_bench/`. Top module is `CoreTop`. Vivado auto-infers the AXI4 / AXI-Stream / clock / reset interfaces.
2. **Block design**: PS + AXI DMA + your packaged CoreTop, wired per `BOARD_BRINGUP.md` §2. Important DMA settings for `k26_bench`:
   - MM2S stream width: **64 bits** (matches `s_axis`)
   - S2MM stream width: **32 bits** (matches `m_axis`)
   - Buffer Length Register Width: **23 bits**
3. **Generate bitstream** and export hardware as `.xsa` with bitstream included.

### 2. Generate the overlay + bitstream package

After sourcing your Vivado settings:

```bash
source /tools/Xilinx/Vivado/2024.1/settings64.sh
./scripts/gen_overlay.sh path/to/system.xsa build/overlay
```

This produces `build/overlay/{core_bench.bit, core_bench.dtbo, core_bench.dts, shell.json}`. The `.dts` is kept around for inspection.

Override flags (when auto-detection misses):
- `-ip <name>` — BD cell name for CoreTop
- `-addr <hex>` — base address override
- `-irq <n>` — GIC SPI number (default 89)

### 3. Deploy on the KRIA

```bash
scp build/overlay/{core_bench.bit,core_bench.dtbo,shell.json} kria:/tmp/
ssh kria
sudo mkdir -p /lib/firmware/xilinx/core_bench
sudo cp /tmp/core_bench.{bit,dtbo} /tmp/shell.json /lib/firmware/xilinx/core_bench/
sudo xmutil unloadapp || true
sudo xmutil loadapp core_bench

ls /dev/udmabuf*     # → /dev/udmabuf-act /dev/udmabuf-wt /dev/udmabuf-out
ls /dev/uio*         # → /dev/uio0  (CoreTop)
```

### 4. Build and run the benchmark on the board

```bash
ssh kria
cd ~/matmulfree_KRIA/software
make
sudo ./build/bench  <CORE_PHYS> <DMA_PHYS> \
                    /dev/uio0 \
                    /dev/udmabuf-act /dev/udmabuf-wt /dev/udmabuf-out \
                    | tee bench.csv
```

`<CORE_PHYS>` and `<DMA_PHYS>` are the addresses Vivado assigned in the block design's Address Editor (typically in the `0xA000_0000–0xBFFF_FFFF` range). `sudo` is required because the runtime opens `/dev/mem`.

The bench sweeps `(N, M) ∈ {64, 128, 256, 512, 1024}²`. Set `MMFREE_VERIFY=1` (default) for first-run correctness checks; `MMFREE_VERIFY=0` skips the on-host reference for the perf sweep.

## Where to read next

- **`scripts/BOARD_BRINGUP.md`** — full Vivado IP-packaging + block-design + KRIA deployment recipe, including troubleshooting for the failure modes seen during initial bring-up.
- **`software/README.md`** — driver / register-map orientation.
- **`CLAUDE.md`** — short orientation for Claude Code sessions in this repo.
- **`src/main/scala/control/CoreConfig.scala`** — parameter definitions, derived widths, presets, and the `forShape(n, m, …)` smart constructor for one-off configs.
