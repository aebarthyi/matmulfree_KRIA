# matmulfree_KRIA

A Chisel3 hardware implementation of a ternary-accumulating systolic array for matmulfree LLM inference, packaged as an AXI4 IP for the Xilinx Kria K26 SoM (KV260 / KR260), with a userspace driver, a CPU-vs-FPGA decode runner, and the measurement scripts behind the published numbers.

> "Matmulfree" here means weights are restricted to the ternary set `{-1, 0, +1}`, so every multiply collapses to an add, subtract, or hold. The array needs no multipliers; the shipped presets map the lane accumulators onto DSP48E2 slices used as adders (`dspAccum`).

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
- **`s_axis`** — AXI4-Stream slave; time-multiplexed between **activations** during `LOAD_ACT` and **weights** during `COMPUTE_MM`. Activations land in on-chip BRAM and stay there; weights stream from DRAM each cycle (1.6-bit packed on the shipped presets, expanded on chip by `WeightUnpackGearbox`).
- **`m_axis`** — AXI4-Stream master; emits accumulator lanes during `STORE_OUT`.

The compute primitive is a `batchSize × xDim` grid of ternary-MAC PEs. Each PE holds `aWidth/2` lane-accumulators; per cycle it consumes `xDim·aWidth/2` ternary weights (broadcast across the batch dim) + one activation per batch row, producing `outLanesPerTile = xDim · aWidth/2` accumulators per array pass. The Core tiles the output column dimension `M` across multiple array passes, reading the same activation BRAM for each tile. Each `COMPUTE` carries the active row count, so one bitstream serves any batch `b ≤ batchSize`.

See `src/main/scala/control/CoreConfig.scala` for the full parameter set and the named presets.

## Repository layout

```
Makefile             Build-server task runner (sim / build / stage / model / deploy).
                     The board's verbs live in scripts/transfer.mk — see below.
board.conf.example   Build-server deploy target (BOARD, BOARD_DEST); copy to board.conf.

src/main/scala/
  systolic/          PE.scala, SystolicArray.scala, WeightUnpack.scala (1.6-bit gearbox)
  control/           Core.scala (FSM + BRAMs), CoreTop.scala (Vivado-facing wrapper),
                     AxiInstructionHandler.scala (instruction MMIO),
                     ActMem.scala / OutMem.scala (activation / output memories),
                     CoreConfig.scala (parameters + presets + manifest emitters),
                     EmitCore.scala (SystemVerilog + preset-manifest emission)
src/test/scala/      systolic/{PE,SystolicArray,WeightUnpack}Spec,
                     control/{Core,AxiInstructionHandler,BenchMirror,CoreConfig}Spec

software/            PS-side userspace stack (software/README.md)
  include/ bench/    register map + instruction encoder, AXI-DMA/udmabuf runtime, bench.c
  integration/       MMfreeLM weight packer, libmmfree engine API, fpga_runner decode runner

scripts/
  build_all.sh       Chisel → SV+manifest → IP → bitstream → overlay → transfer/presets/<p>/
  build_project.tcl / package_ip.tcl / gen_overlay.tcl / ooc_synth.tcl   Vivado + xsct steps
  clone_preset_hw.sh build a same-RTL preset's bundle from an existing bitstream
  transfer.mk        SOURCE for the bundle's board Makefile (staged → transfer/Makefile)
  deploy_kria.sh, bench_batch.sh, power_bench.sh, cma_reclaim.sh, final_sweep_board.sh, …
                     board-side scripts, staged into transfer/
  final_sweep_xeon.sh, final_dataset.py   x86 baseline sweep + the data-set aggregator
vivado/bd.tcl        Block design (PS + N×AXI-DMA + CoreTop)

external/udmabuf/    Vendored u-dma-buf kernel driver (submodule; built + loaded by deploy-hw)
matmulfreellmCPU/    C++ CPU engine + provisioning tools (submodule)

generated/<preset>/  Build product: SystemVerilog + packaged IP + preset.env/.json (the manifest)
build/               Build product: Vivado project, OOC synth, overlay outputs (Mill uses out/)
transfer/            Build product: the self-contained bundle the KRIA needs — and NOTHING
                     else from the repo. `make deploy` rsyncs this whole tree to the board:
                       Makefile, *.sh           board task runner + scripts (from scripts/)
                       udmabuf/                 u-dma-buf driver source
                       src/                     software/ + matmulfreellmCPU/cpp/, compiled on the board
                       presets/<preset>/        core_bench.bit.bin, .dtbo, shell.json, preset.env/.json
                       models/<hf-id>/          model.mmfree, tokenizer.mmtok, packed/ (2-bit blob),
                                                packed-1p6/ (1.6-bit blob), e.g. models/ridger/MMfreeLM-2.7B/
```

### Single source of truth: the preset manifest

Every preset's geometry is defined **once** in `CoreConfig.scala`. `EmitCore` emits it
alongside the SystemVerilog as `generated/<preset>/preset.env` (KEY=VAL, sourced by the
shell/C build) and `preset.json` (read by the Python tools). Everything downstream —
`build_all.sh`, `gen_overlay.tcl`, the board Makefile, the weight packer, the C runtime —
**consumes the manifest instead of re-deriving geometry**, so an array-size change is a
one-line edit in `CoreConfig`. The C runtime cross-checks its resolved geometry against the
manifest at startup (`MMFREE_MANIFEST`), warning (or, with `MMFREE_STRICT=1`, aborting) on any
disagreement with the loaded bitstream.

## Prerequisites

**Host (Chisel build + sim):**
- JDK 11 or newer
- Verilator (`sudo apt install verilator`) — for ChiselSim
- No need to install Mill; the repo ships a `./mill` bootstrap (Mill is the sole build tool)

**Host (KRIA bring-up):**
- Xilinx Vivado / Vitis 2025.2 (`vivado`, `xsct`, `bootgen`)
- `dtc` (`sudo apt install device-tree-compiler`), `unzip`, `rsync`

**KRIA target** (needs ONLY the `transfer/` bundle — no repo checkout):
- Ubuntu-on-KRIA (22.04 or 24.04 LTS Xilinx image) with `xmutil`
- `gcc`, `g++` (C++17), `make`, `cmake` — the board `make build` native-compiles `bench` and
  the `fpga_runner` (which builds `libmmfreecpu.a` via CMake) from the bundled `src/` tree
- kernel headers (`sudo apt install linux-headers-$(uname -r)`) — `sudo make deploy-hw`
  builds + loads the vendored `u-dma-buf` driver; no externally-installed module is required

**Model provisioning + packing (build server):**
- Python 3.10+: `pip install -r matmulfreellmCPU/tools/requirements.txt` (download + quantize)
  and `software/integration/requirements.txt` (the packer needs only `numpy`)

## Quickstart

### 1. Clone

```bash
git clone --recurse-submodules git@github.com:aebarthyi/matmulfree_KRIA.git
cd matmulfree_KRIA
# already cloned without --recurse-submodules?
git submodule update --init --recursive
```

### 2. The command reference

The flow is split across **two Makefiles by responsibility**, so it is never ambiguous which
command runs where. Both read geometry from the preset manifest, both default to
`PRESET=mmfree_2_7b`, and `make help` on either side lists the verbs plus the presets
(and, on the board, models) present.

#### Build server — root `Makefile`

Run from the repo root. All compute-heavy work happens here (Chisel, Vivado, model
provisioning); the board never sees the repo.

| Command | What it does |
|---|---|
| `make sim [SPEC=substr]` | Mill test suite; `SPEC` filters by test-name substring |
| `make build PRESET=<p>` | Chisel → SV+manifest → IP → bitstream → `transfer/presets/<p>/`, **and** stage the C++ sources into `transfer/src/` |
| `make stage` | re-stage `transfer/{Makefile,*.sh,src/}` only — no Vivado. Run it after editing `software/` or `scripts/`, or the board compiles the old copy |
| `make model PRESET=<p>` | `provision` + `pack` the preset's checkpoint → `transfer/models/<hf-id>/` (see [Provision + pack](#provision--pack-model-weights-host) for the 1.6-bit output dir) |
| `make provision PRESET=<p>` | stage 1 only: HF download → `model.mmfree` + `tokenizer.mmtok` |
| `make pack PRESET=<p>` | stage 2 only: `model.mmfree` → per-port engine blobs (format from the manifest) |
| `make deploy BOARD=user@host` | rsync the whole `transfer/` bundle → `<board>:~/transfer/` |

Variables: `MODEL=<hf-id>` overrides the preset's checkpoint, `OUT=<dir>` / `BLOB=<file>` /
`PACKED_DIR=<dir>` retarget provision/pack, `ARGS="…"` passes through to the underlying tool,
`DEPLOY_MODELS="<hf-id> …"` limits which models ship (HW presets and `src/` always ship),
`BOARD_DEST=` changes the remote directory (default `~`).

#### KRIA — `transfer/Makefile` (staged from `scripts/transfer.mk`)

Run from `~/transfer` on the board.

| Command | What it does |
|---|---|
| `make build` | native-compile `bench` + `fpga_runner` from `src/` |
| `sudo make deploy-hw PRESET=<p>` | load the bitstream overlay + build/load u-dma-buf, program `fclk0`, verify udmabuf sizes |
| `make gate PRESET=<p> [MODEL=<hf-id>]` | exact-match gate: CPU == FPGA, bit for bit, every projection |
| `make streams PRESET=<p> [STREAMS=n]` | batched decode with a **different prompt per stream**, each checked against its own single-stream run (the row-order check the sweep cannot do) |
| `make run PRESET=<p> [MODEL=<hf-id>]` | FPGA decode, timed + profiled (+ rail power) |
| `make gen PRESET=<p> PROMPT="…" [GEN=n]` | interactive generation, live streamed text (`TEMP`/`TOPK`/`SEED` for sampling) |
| `make sweep PRESET=<p> [GEN=n] [REPS=n]` | batch ladder b=1..batchSize → one CSV row per batch in `results/` |
| `make bench PRESET=<p> [BATCH=N]` | synthetic per-projection sweep (+ rail power). 2-bit bitstreams only — it refuses a packed-weight preset |

`sweep` also takes `BACKEND=cpu` (the same ladder with the engine out of the loop — the CPU
baseline, int8 weights) or `BACKEND=cpu-2bit` / `cpu-1p6` (CPU weights held at the FPGA's two
encodings; bit-identical results, only speed differs). The CPU arms batch at the `--serve`
width like the engine does; `ENV="MMFREE_CPU_BATCH=1"` forces the one-stream-at-a-time CPU path
for an A/B.

Power sampling (`xlnx_platformstats` → `power/`) is on by default for `bench`, `run` and
`sweep`; `POWER=0` disables it, `POWER_INTERVAL` / `POWER_ARGS` / `POWER_DIR` tune it. A
`POWER=1` arm costs ~2% decode, so compare only arms with matching flags.

The verbs that open `/dev/mem` invoke `sudo` themselves and hand the manifest geometry through
`sudo env`, because `sudo` strips the environment. For the same reason, runtime knobs go in
`ENV="K=V …"` (e.g. `ENV="MMFREE_SPLIT_CHUNKS=0"`) and runner flags in `ARGS="…"`, which is
appended last so it wins. The knobs themselves are listed in `software/README.md` and
`software/integration/fpga_runner/README.md`.

#### Component Makefiles (rarely needed directly)

| Command | What it does |
|---|---|
| `make -C software` | build `bench` (the board's `make build` delegates here) |
| `make -C software/integration/fpga_runner` | build `mmfree-cli-fpga` + `libmmfreecpu.a` |
| `make -C software/integration/tests` | host-side checks: split-chunk plan, threaded readback, 1.6-bit pack round-trip |
| `make -C matmulfreellmCPU` | build the standalone CPU engine (`mmfree-cli`); also `model`, `run`, `bench`, `test` |

### 3. Run the test suite

```bash
make sim                       # all suites  (= ./mill matmulfree_KRIA.test)
make sim SPEC=CoreConfig       # one suite by name substring
./mill matmulfree_KRIA.test -z "CoreSpec"   # equivalent direct form
```

Suites: `PESpec`, `SystolicArraySpec`, `WeightUnpackSpec`, `CoreSpec`,
`AxiInstructionHandlerSpec`, the `BenchMirror*Spec` family, and `CoreConfigSpec`. The first
run is slow (Verilator compiles each DUT); later runs reuse the cache.

### 4. Emit SystemVerilog + manifest for a preset

`make build` does this as its first step, but you can run it standalone:

```bash
./mill matmulfree_KRIA.runMain control.EmitCore mmfree_370m
# → generated/mmfree_370m/{CoreTop.sv, Core.sv, …, preset.env, preset.json}
```

`main` carries exactly three deployable presets — one per released MMfreeLM checkpoint
(`CoreConfig.scala`, registry `CoreConfig.deployable`):

| Preset | Checkpoint | xDim | batch | aWidth | maxN | maxM | ports×width | m_axis | clk | tok/s @ b=8 |
|---|---|---|---|---|---|---|---|---|---|---|
| `mmfree_370m` | MMfreeLM-370M | 32 | 8 | 16 | 4096 | 32000 | 4×128 b | 128 b | 250 | 137.06 |
| `mmfree_1_3b` | MMfreeLM-1.3B | 32 | 8 | 16 | 8192 | 32000 | 4×128 b | 128 b | 250 | 66.27 |
| `mmfree_2_7b` | MMfreeLM-2.7B | 32 | 8 | 16 | 8192 | 32000 | 4×128 b | 128 b | 250 | 44.69 |

All three are B8 + `dspAccum` + 1.6-bit packed weights + column banking + the banked output
memory. **1.3B and 2.7B emit byte-identical RTL**, so build 2.7B once and assemble the 1.3B
bundle with `scripts/clone_preset_hw.sh` rather than running Vivado twice. tok/s is aggregate
decode, the five-pass median on the board with rail power sampled. The 1.3B cell moves between
sessions with a bimodal host-side pack cost; its b=1..6 rows are the stable ones.

`s_axis` is `max(xDim, batchSize) × aWidth` bits, split across `ports` of ≤128 b (one PS HP port
each). `m_axis` is `outBeatLanes × outLaneWidth`. `clk` is the PL clock the design is
timing-closed for (`plClkMhz`); `deploy-hw` programs `fclk0` to it and reports the achieved rate,
since an overlay reload alone leaves `fclk0` at whatever rate it had. All of these land in
`preset.env`/`preset.json`.

**2-bit control arm.** `CoreConfig.candidates` holds `mmfree_370m_2bit` / `mmfree_1_3b_2bit` /
`mmfree_2_7b_2bit`: the shipped presets with `packedWeights` off and nothing else changed, so
each pair isolates the weight encoding. They build and clone the same way and read the 2-bit
blob in `packed/`. Their udmabuf-wt is 1.25× larger (2.7B: 624 MiB over 4 ports), so on the
board run `sudo ./cma_reclaim.sh` before deploying the 2.7B one.

The ablation presets (batch ladders, clock rungs, banking widths, DSP-accum on/off) live on
the **`exploration`** branch, along with the sweep harness that produced their numbers.

`EmitCore <preset> <dir>` overrides the output directory. `EmitCore` does **not** clean it
first, so `rm -rf generated/<preset>` before re-emitting a preset whose geometry changed —
otherwise stale `.sv` files sit alongside the new ones and any hash-the-SV comparison
(including `clone_preset_hw.sh`'s RTL check) is wrong.

### 5. (Optional) Out-of-context synthesis

To get LUT / FF / BRAM numbers without a full board build:

```bash
vivado -mode batch -source scripts/ooc_synth.tcl -tclargs mmfree_370m
# → build/vivado-ooc/mmfree_370m/
```

## KRIA board bring-up

**The whole build-and-deploy, end to end** (2.7B here; 370M is identical with its own
`PRESET` and model dir):

```bash
# ── on the BUILD SERVER ───────────────────────────────────────────────────────
source /opt/Xilinx/2025.2/Vivado/settings64.sh   # your install path; also puts Vitis' xsct on PATH
make sim                                   # optional
make build PRESET=mmfree_2_7b              # Chisel → bitstream → transfer/presets/ + src/
make model PRESET=mmfree_2_7b              # provision + pack into transfer/models/<hf-id>/
make deploy BOARD=ubuntu@kria              # rsync transfer/ → the board (incremental)

# ── on the KRIA, in ~/transfer ────────────────────────────────────────────────
make build                                 # native-compile bench + fpga_runner
sudo make deploy-hw PRESET=mmfree_2_7b     # load overlay + u-dma-buf, verify sizes
make gate PRESET=mmfree_2_7b               # CPU == FPGA, bit for bit  ← always run this first
make run  PRESET=mmfree_2_7b               # profiled FPGA decode
make gen  PRESET=mmfree_2_7b PROMPT="The capital of France is" GEN=40
```

1.3B is built by cloning, since it emits byte-identical RTL to 2.7B:

```bash
rm -rf generated/mmfree_1_3b                                        # EmitCore does not clean
./mill matmulfree_KRIA.runMain control.EmitCore mmfree_1_3b generated/mmfree_1_3b
scripts/clone_preset_hw.sh mmfree_2_7b mmfree_1_3b                  # seconds, not ~40 min
make model PRESET=mmfree_1_3b
make deploy BOARD=ubuntu@kria
```

The clone is **refused** unless the two presets emit byte-identical `.sv` and their manifests
differ only in non-RTL keys (`MMFREE_PRESET`, `MMFREE_MODEL`, `MMFREE_SHAPES`, `UDMABUF_WT_SZ`),
so it cannot quietly ship the wrong bitstream. It re-sizes the overlay's udmabuf-wt nodes to the
destination's model; the timing record is the source's report.

### Build options (host)

`make build` runs `scripts/build_all.sh`: emit SV + manifest → package the `CoreTop` IP →
block design (PS + N×AXI-DMA + CoreTop) → bitstream → `.xsa` → device-tree overlay →
`bootgen`, then assembles `transfer/presets/<preset>/` and stages the shared bundle parts. Every
geometry value (port count, stream widths, PL clock, udmabuf sizes) comes from the manifest.
Useful overrides (environment):

- `REPACKAGE=1` — re-package the IP from SV (needed when ports/interfaces or the SV file set changed)
- `BITSTREAM_ONLY=1` / `PACKAGE_ONLY=1` — stop after the XSA / only re-run the overlay + assembly
  steps from the last-built `build/t_matmul.xsa`
- `UDMABUF_WT_SZ=<bytes>` — override a udmabuf size (the manifest already sizes udmabuf-wt for
  the whole resident model)

### Deploy (host → board)

`make deploy BOARD=ubuntu@kria` is **pure file transfer**: it rsyncs the entire `transfer/`
tree into `<board>:$(BOARD_DEST)/transfer/` and runs nothing on the board. Unchanged files
(including the multi-GB models) don't re-transfer. First time only: if it reports the directory
is missing, run `mkdir -p ~/transfer` on the board.

### On the board

`sudo make deploy-hw PRESET=<p>` installs the overlay under `/lib/firmware/xilinx/core_bench/`,
builds + loads the vendored `u-dma-buf` driver from `udmabuf/`, (re)loads the app with
`xmutil`, programs `fclk0`, and **verifies each u-dma-buf node's size against the manifest**.
After it loads (4-port presets):

```bash
ls /dev/uio*         # → /dev/uio4            (CoreTop)
ls /dev/udmabuf*     # → /dev/udmabuf-act0..3 /dev/udmabuf-wt0..3 /dev/udmabuf-out
```

Each verb defaults to the preset's own checkpoint; `MODEL=<hf-id>` points it at another model
on the same overlay, provided the bitstream's `maxN` spans it and its blob fits the overlay's
udmabuf-wt (sized for the preset's own model). Addresses and device paths come from
`transfer/board.conf` (copy `board.conf.example`) or the standard defaults. `gate/run/gen` find
`models/<hf-id>/model.mmfree` from the manifest's `MMFREE_MODEL`, the tokenizer beside it, and
the packed blob in `packed-1p6/` when the manifest has `MMFREE_PACKED_WEIGHTS=1`, else `packed/`.

### Provision + pack model weights (host)

`make model PRESET=<p>` reads the checkpoint from the preset's manifest (`MMFREE_MODEL`) and runs
both stages into `transfer/models/<hf-id>/`:

```bash
make provision PRESET=mmfree_2_7b   # → transfer/models/ridger/MMfreeLM-2.7B/{model.mmfree,tokenizer.mmtok}
make pack      PRESET=mmfree_2_7b        # 1.6-bit blob → .../packed-1p6/
make pack      PRESET=mmfree_2_7b_2bit                     # 2-bit blob → .../packed/
```

`provision` runs `matmulfreellmCPU/tools/provision.py` (`ARGS="--skip-download"` /
`"--tokenizer-only"` pass through). `pack` runs `software/integration/mmfree_pack` with the
manifest, so the blob's geometry and encoding match the bitstream, and writes it where the
board looks: `packed-1p6/` for a packed-weight preset, else `packed/` (`PACKED_DIR=` or `OUT=`
overrides). The runner refuses a blob whose encoding doesn't match the bitstream.

### Reproducing the measurements

The published numbers come from two sweeps and one aggregator:

```bash
# on the KRIA, in ~/transfer — settings go AFTER sudo, which strips the environment
sudo ./final_sweep_board.sh                        # everything; `fpga` or `cpu` runs one half
sudo CPU_BATCHES="1 2" ./final_sweep_board.sh cpu  # truncate the slow A53 arms' ladder
sudo PRESETS="mmfree_370m_2bit mmfree_1_3b_2bit mmfree_2_7b_2bit" ./final_sweep_board.sh fpga

# on the build server
./scripts/final_sweep_xeon.sh                      # x86 CPU arms, RAPL power
./scripts/final_dataset.py --board ~/results/final_<date> --xeon reports/host_cpu/final \
    [--cpu-ladder "1 2"] [--fpga-formats 1p6,2bit]
```

`final_sweep_board.sh` runs one pass of the A53 CPU arms (int8 / 2-bit / 1.6-bit) and, per
preset, the gate, `make streams` and five independent FPGA passes, all with rail power and an
idle-power sample; it ends by printing the `rsync` that pulls `results/` + `power/` into a
fresh directory.
`final_dataset.py` merges board and Xeon CSVs into `dataset.csv` (+ per-model `ops_<model>.csv`)
under `--out` (default `reports/final`), with tok/J net of a per-session idle baseline, and
refuses to call a set complete if any arm is short of the batch ladder. Declare a deliberately
truncated A53 ladder with `--cpu-ladder` (matching `CPU_BATCHES`), and a 2-bit FPGA arm with
`--fpga-formats 1p6,2bit`.

## Where to read next

- **`make help`** — the task-runner verbs at a glance (root and `~/transfer`).
- **`software/README.md`** — driver / register map, runtime and bench knobs.
- **`software/integration/README.md`** — MMfreeLM weight packer and the C engine API.
- **`software/integration/fpga_runner/README.md`** — the CPU-vs-FPGA decode runner.
- **`matmulfreellmCPU/README.md`** — the standalone CPU engine (build, provision, `--weights` packing modes).
- **`src/main/scala/control/CoreConfig.scala`** — parameter definitions, derived widths, presets, the manifest emitters (`presetEnv`/`presetJson`), and the `forModel` / `forShape` smart constructors.
