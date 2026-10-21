# `matmulfree_KRIA` — FPGA ternary-matmul accelerator for the MatMul-Free LM, and its glue

Working notes for the project report: the hardware design, the PS-side software that drives
it, the build/deploy flow, and the measurement harness. Companion to
`matmulfreellmCPU/SUMMARY.md`, which covers the CPU inference engine this offloads from.

Provenance: read out of the working tree on 2026-08-23, branch `main` (HEAD `b4872f5`,
"Standardize the final data set"), with the `matmulfreellmCPU` submodule at `3e91da3`.
File:line references are to that state. Measured numbers are quoted from
`reports/final/dataset.csv` and `reports/*.rpt` in-tree, not re-derived; Xeon comparisons
use the batched re-sweep in `reports/final_batched_20260825/` (board rows are identical).

---

## 1. What this is

A **Chisel3 hardware accelerator for the one operation that dominates MatMul-Free LM
inference** — the ternary BitLinear projection — plus the entire stack needed to build it,
get it onto a Xilinx Kria K26 (KR260 carrier), and measure it honestly against two CPU
baselines.

The operation is exactly:

```
acc[b][m] = Σ_{n<N} x[b][n] · w[n][m],     w ∈ {-1, 0, +1},   x ∈ int16 (Q5.10),  acc ∈ int32
```

so there are no multipliers in the datapath: a ternary weight selects **add / subtract /
hold**. Everything else in the model — RMSNorm, the int16 quant, the dequant, SiLU/SwiGLU,
the HGRN recurrence, sampling — stays on the four Cortex-A53 cores, running the *unmodified*
CPU engine. The two sides meet at one C++ abstract class (`TernaryBackend`), which is what
makes "A53 alone vs A53 + PL" a controlled, single-variable comparison.

**Headline result** (2026-08-15/16, five passes, POWER=1, all gates bit-exact): the K26 at
b=8 decodes **137.1 / 66.3 / 44.7 tok/s** on 370M / 1.3B / 2.7B — **18× / 32× / 44× the same
board's A53-only path** — at **66.8 / 25.1 / 14.9 tok/J**, which is **31-47× the tok/J of a
Xeon W-2155 workstation (20 OpenMP threads, batched CPU kernels)** running the identical engine
at the same weight format. In raw throughput the board is 0.66× / 1.03× / 1.34× the Xeon on
1.6-bit, and 0.57× / 0.67× / 0.78× against the Xeon's fastest encoding (2-bit): it wins on
identical weights at 1.3B and 2.7B, and its position improves with model size on both axes.

### Inventory

| area | files | lines | what |
|---|--:|--:|---|
| RTL (`src/main/scala`) | 10 | 2903 | PE, array, weight gearbox, Core FSM, AXI handler, memories, config, emitter |
| RTL tests (`src/test/scala`) | 8 | 3542 | ChiselSim/Verilator suites — more test than design |
| PS runtime (`software/{include,bench}`) | 5 | 2050 | register/ISA headers, AXI-DMA driver, userspace runtime, bench sweep |
| engine API + runner (`software/integration`) | 15 | 3889 | `libmmfree`, the `TernaryBackend` impls, the decode runner, the offline packer, host tests |
| build/deploy/measure (`scripts`, `vivado`) | 27 | 6056 | Chisel→bitstream→overlay→bundle, board task runner, sweeps, aggregation |
| vendored PyTorch model (`runtime/`) | 40 | 5888 | the original Triton/PyTorch MMfreeLM, kept as provenance only; not in any live path |

---

## 2. System architecture

```
 BUILD SERVER                                        KRIA K26 (Ubuntu, 4× A53 @1.33 GHz)
 ────────────                                        ─────────────────────────────────────
 CoreConfig.scala ── EmitCore ──▶ *.sv + preset.env         ┌── PS (Cortex-A53) ────────────┐
        │                              │                    │  mmfree-cli-fpga (runner)     │
        │  (single source of truth)    │                    │    └ matmulfreellmCPU Model   │
        ▼                              ▼                    │        RMSNorm/quant/dequant, │
   package_ip.tcl ──▶ CoreTop IP   gen_overlay.tcl          │        SwiGLU, HGRN scan,     │
        │                          (udmabuf sizes           │        sampling, tokenizer    │
        ▼                           from the manifest)      │              │ TernaryBackend │
   bd.tcl + build_project.tcl                               │              ▼                │
        │  PS + 4× AXI DMA + CoreTop                        │      FpgaBackend (C++)        │
        ▼                                                   │              │                │
   bitstream → XSA → .bit.bin + .dtbo + shell.json          │      libmmfree (C)            │
        │                                                   │              │                │
  mmfree_pack ──▶ weights.port{0..3}.bin + manifest.tsv     │      mmfree_runtime (C)       │
        │                                                   │   /dev/mem  /dev/uioN         │
        └────────── rsync transfer/ ──────────────────────▶  │   /dev/udmabuf-{act,wt,out}N  │
                                                            └───────┬───────────────────────┘
                                                          AXI4 (instr) │ 4× AXI-S in, 1 out
                                                            ┌─────────▼─────────────────────┐
                                                            │  PL: CoreTop @ 250 MHz        │
                                                            └───────────────────────────────┘
```

Two Makefiles, split by responsibility, because the board is a slow build host and the
build server has no PL:

- **`Makefile` (build server)** — `sim`, `build PRESET=…` (Chisel → bitstream → bundle),
  `stage`, `model` (= `provision` + `pack`), `deploy` (rsync).
- **`scripts/transfer.mk` → `transfer/Makefile` (board)** — `build` (native compile),
  `deploy-hw` (overlay + driver), `gate`, `streams`, `bench`, `run`, `gen`, `sweep`. All
  paths rooted at `transfer/`, so **the board needs nothing but the rsync'd bundle**.

---

## 3. The hardware: `CoreTop`

### 3.1 Processing element — `src/main/scala/systolic/PE.scala:30`

Parameterized by `aWidth` (activation width) and `maxAcc` (max accumulation cycles). One PE
holds **`aWidth/2 = nLanes` independent lane accumulators**, each `aWidth + log2Ceil(maxAcc)`
bits wide (headroom for worst-case growth over `maxAcc` accumulations). Per cycle it takes
`nLanes` 2-bit weights plus **one** activation, and each lane does:

| weight code | action |
|---|---|
| `0b00` (0) | hold |
| `0b01` (1) | `acc += act` |
| `0b11` (3) | `acc -= act` |

3-state FSM `idle → running → done`; `nAcc` arrives once per operation and the PE counts to
it. Two parameters ride on top:

- **`signedAct`** — sign-extend the activation into the accumulator (two's complement) rather
  than zero-extend. All shipped presets set it; the zero-extend path is the bring-up legacy
  and is bit-identical for non-negative activations, which is why it survived so long
  unnoticed.
- **`useDspAccum`** — attach `use_dsp = "yes"` to each lane accumulator so Vivado implements
  the add/sub in the **DSP48E2 ALU** (P-register feedback = accumulate, dynamic ALUMODE = the
  ternary sign, CE = the hold) instead of LUT/CARRY8 fabric. Synthesis-only metadata:
  Verilator ignores it, so every simulation result is byte-identical.

Two handshake bugs are recorded in the source as *fixed*, and both are worth a line in a
report on ready/valid discipline:

1. **`input.ready` must stay high through the final accumulation beat**
   (`PE.scala:94`). The original `!(counter === nAccReg)` form deasserted `ready` on the last
   cycle and *consumed that beat's data without a handshake* — so a `tvalid` gap on exactly
   that cycle made the PE accumulate garbage, finish one beat early, and desynchronise the
   upstream DMA stream for the rest of the run. `PESpec` now pins the negative case ("valid
   gap on the final accumulation cycle stalls — no phantom beat").
2. **`done → idle` resets the counter and the accumulators**, so back-to-back operations need
   no external reset. (`Core` still pulses a local array reset between column tiles — see
   §3.3 — because the *array* has more state than the PE.)

### 3.2 The array — `src/main/scala/systolic/SystolicArray.scala:61`

`yDim × xDim` grid. **It is a broadcast MAC array, not a true systolic array** — the name is
historical and the source says so explicitly. Weights and activations are broadcast to every
PE in the same cycle; there is no nearest-neighbour delay chain. (A genuinely systolic variant
*was* built and measured; see §5.)

- **`yDim = batchSize` is the batch dimension.** Row `k` holds batch item `k`'s activation;
  every row sees the *same* weight beat. So one weight stream produces `yDim` output rows —
  this is the entire reason batching pays on this design.
- **`xDim` is the output-columns dimension.** Per cycle the array consumes one weight beat of
  `xDim · nLanes = outLanesPerTile` ternary weights and produces, per row, that many
  accumulators.
- Handshakes: `weights_i` and `activation_i` gate each other in `running` (both must be valid
  for the array to step); `nAcc` is a one-shot rendezvous consumed in `idle`.

At the shipped geometry (`aWidth=16, xDim=32, batchSize=8`): `nLanes=8`,
`outLanesPerTile=256`, and the array performs **2048 ternary accumulate ops per cycle**
(8 rows × 32 cols × 8 lanes) off **256 weight trits per cycle** — a weight-reuse factor of
exactly `batchSize`.

**Column banking (`colBankSize`)** is the one non-obvious structural feature. The activation
broadcast is the fanout-heavy, route-bound net that walls 250 MHz: `act[row]` drives
`xDim × nLanes ≈ 256` PE inputs. `colBankSize` splits the columns into banks and inserts
**one pipeline register between banks**, so bank `b` sees the whole input beat delayed by `b`
cycles:

- fanout drops from `xDim` to `colBankSize` columns (32 → 4 = 8× at the shipped value);
- **the whole beat is delayed, not just the activation**, so each bank's weights stay aligned
  with the activation that reached it and the result is *numerically identical, only retimed*;
- **no output de-skew buffer is needed**, because every PE holds its result in `done_state`:
  bank `b` finishes at `nAcc + b`, earlier banks simply hold, and the array raises
  `output.valid` when the *deepest* bank finishes;
- cost is `numBanks − 1` cycles per operation (negligible against `nAcc` = 1024…8192) plus a
  per-bank beat-wide register.

Banking forced a fourth FSM state, `draining` (`SystolicArray.scala:102`), and the reason is
a subtle streaming hazard: bank 0 is the input consumer, so **the moment bank 0 finishes the
array has swallowed all `nAcc` beats and must stop accepting that same cycle**, or it eats
the next operation's first beat while the deeper banks drain. Hence `stillAccepting` is gated
on a *combinational* `inputDone` (bank 0's `output.valid`) rather than on the registered state
transition. The unbanked path deliberately keeps a 3-state FSM so its emitted SV — and
therefore existing bitstreams — stays byte-identical.

### 3.3 The Core — `src/main/scala/control/Core.scala:63`

#### Instruction set (`AxiInstructionHandler.scala`)

One 128-bit beat, written to a single MMIO register. LSB-first:

```
[  7:  0]  opcode        [ 79: 48]  dim0
[ 47:  8]  ptr (40-bit)  [111: 80]  dim1
                         [127:112]  flags
```

| opcode | name | dim0 | dim1 | flags |
|---|---|---|---|---|
| `0x01` | `LOAD_ACT` | N activations | — | — |
| `0x02` | `COMPUTE_MM` | rows = N | cols = M | **active batch count `b`** |
| `0x03` | `STORE_OUT` | output lanes | — | — |

Register map (12-bit aperture): `0x000` instruction write, `0x010` packed status read
(busy / lastOpcode / errorCode / irqPending), `0x020` IRQ ack. Single-beat AXI only —
`AWLEN/ARLEN ≠ 0` returns `SLVERR`, as does writing a new instruction while one is pending.
Completion raises a level-high `irq`, and the completion pulse deliberately **wins ties with
the AXI-driven ack so an IRQ is never dropped** (`AxiInstructionHandler.scala`, end).

The `flags` field carrying the runtime batch count is the cheapest big win in the whole
design: `cmpBatch` makes the drain write only `b` of `B` rows, so **one B8 bitstream serves
b=1..8 at ~b=1 cost** (the weight stream is batch-count-independent). `flags = 0` clamps to
the full `batchSize`, which is why a legacy host is byte-behaviour-identical.

#### Top-level FSM and dataflow

`sIdle → sLoad | sCompute | sStore → sIdle`, with `sCompute` running a nested
`cInit → cStream → cReset → cDone` per output column tile (`Core.scala:261`, `:264`).

```
LOAD_ACT     s_axis ──▶ actBram[0..N)          (activations resident; N beats)
COMPUTE_MM   per column tile t of ceil(M/256):
               s_axis (weights) ─┐
                                 ├─▶ inQ ─(armSlice)─▶ SystolicArray  ── N cycles
               actBram[n] ───────┘                            │ capture
                                                              ▼
               tileAccums ──(drain engine, parallel with tile t+1)──▶ outBram
STORE_OUT    outBram ──▶ outQ ──▶ m_axis       (one row = one beat, no lane mux)
```

Three properties matter for the report:

1. **Activations are stationary; weights stream.** `actBram` is written once per projection
   and re-read for every column tile, so N activations serve `M` outputs. Weights are never
   stored on-chip — they stream from DRAM at one beat per cycle, which is why weight
   bandwidth is the design's binding constraint and why both batching and 1.6-bit packing
   attack it.
2. **Re-COMPUTE without re-LOAD is legal and is used.** `CoreSpec` pins it ("edge:
   re-COMPUTE without intervening LOAD reuses the prior activations"), and the host exploits
   it for column-tile splitting (§6.4).
3. **The drain is hidden.** The capture into `tileAccums` kicks a drain engine
   (`Core.scala:567`) that writes `batchSize × outSubBeats` beat-wide rows into `outBram`
   while the *next* column tile is streaming — so for any real shape (`N ≥ B·outSubBeats`)
   the entire output write costs nothing.

#### The pipeline registers, and why each exists

Every one of these was added to close 250 MHz, and each is documented with the path it fixed:

| structure | line | what it terminates |
|---|---|---|
| `inQ` (2-deep `Queue`, flushable) | `:214` | `actBram` read → 256-way activation broadcast → PE accumulate. Carries **(weight beat, activation) PAIRS**, with the enqueue side owning the row counter, so pairing survives queue buffering across tile boundaries. Flushed in `sIdle` so a host streaming extra weight beats cannot poison the next COMPUTE. |
| `armSlice` (1-deep, `pipe=true`, B>1 only) | `:239` | `inQ`'s distributed-RAM read → PE accumulate, which lengthens as the PE grid doubles with batch. Uniform 1-cycle delay on every beat, functionally transparent. B=1 keeps the validated direct path. |
| `outQ` (2-deep) | `:256` | `outBram` read → DMA S2MM skid buffer. **Never flushed** — it keeps draining after the FSM returns to `sIdle`, and the runtime waits for S2MM idle rather than just the IRQ, so in-flight tail beats are covered. |
| `cmpRowsM1` / `activeIsLast` registers | `:305` | The live compare `activeIdx === cmpRows-1` put a wide subtract+compare in the cone feeding the **actBram read-address enable** — the route-dominated 6-LUT path that walled the 250 MHz builds. Precomputing both shortens the enqueue path to reg→mux→BRAM. |

#### Output-column tiling and the address-folding trick

`outBram` rows are **exactly one `m_axis` beat wide** and laid out in stream order:

```
rowAddr = (batchIdx · numColTiles + colTileIdx) · outSubBeats + subBeatIdx
```

so `STORE_OUT` is a flat row counter with **no sub-beat lane mux at all**. The drain engine
shifts `tileAccums` down by `effOutBeatLanes` lanes per cycle, so the write data is always
lanes `[0, effOutBeatLanes)`. The 256:1 dynamic lane mux on the old wide-row layout was the
250 MHz critical path; folding the lane select into the row address deleted it rather than
pipelining it.

#### The two memories, and why both are BlackBoxes

CIRCT's `AttributeAnnotation` can only target modules, wires and regs — never a
`SyncReadMem` — so any Vivado memory attribute needs a hand-written BlackBox. Both exist for
that reason alone; both have *identical* read semantics and latency to the inferred form, so
they are **mapping changes, not design changes**.

- **`ActMem`** (`control/ActMem.scala`) — activation memory, 1 write / 1 read port, 1-cycle
  unconditional synchronous read, carrying `ram_style` + `cascade_height`. It exists because
  at `maxN=8192` Vivado maps the memory onto 1024×36 RAMB36E2 blocks and chains **eight**
  of them through the hard output cascade: `1.588 + 7×0.247 + 0.136 = 3.206 ns` of pure
  **logic** against a 4.000 ns budget, before a single net is routed. `cascade_height=2`
  leaves one hop and resolves the rest in a ~96-LUT fabric mux. Deliberately **not**
  pipelined (unlike the output memory): a read-latency stage here would desynchronise the
  weight/activation pairing `inQ` depends on, and that pairing has a history of off-by-one-beat
  bugs. `maxN=4096` is a 4-deep chain and closes 250 with no attribute at all — which is the
  control proving chain *depth* is the variable, not batch or array size.
- **`OutMemUltra`** (`control/OutMem.scala`) — URAM output memory with pipelined read *and*
  write. Three findings are recorded in it, and they are the most transferable
  FPGA-engineering content in the repo:
  - **Vivado will not absorb read-pipeline registers into a URAM cascade, whatever you
    write.** The obvious form (one deep array + `latency` register stages) produced
    `WARNING [Synth 8-6057] … has no pipeline registers. FF provided: 0 FF absorbed: 0`,
    leaving the read a fully combinational `CAS_IN_DOUT → CAS_OUT_DOUT` run plus the group
    mux. Two fixes were tried and rejected: shortening the cascade (4→2) bought +0.028 ns and
    made TNS *worse*, because URAM288 CLK→DOUT is 1.837 ns before any cascade; and the UG901
    single-always-block template changed absorption not at all and cost 0.3 ns, because
    dropping `shreg_extract="no"` let the stages become SRL16Es.
  - **So place them structurally.** The address space is split into explicit banks, each
    exactly one cascade chain deep (`4096 × cascadeHeight` rows); cycle 1 registers every
    bank's output *and* the bank select, cycle 2 registers the mux over already-registered
    outputs, and the remaining `latency−2` stages are unchanged. **Total latency is
    unchanged**, so `Core`'s `outReadLat`, `primeCtr` and drain-flush accounting are
    untouched. Worth +1.068 ns post-synth / +0.018 ns post-route at `maxN=8192` for **+240
    LUT, +4 registers, the same URAM count, no latency change**.
  - **The write port is pipelined too** (`writeLat`), because the drain's combinational write
    address (`drainBatchCtr · numColTiles + …`) into a 64000-row URAM was the 250 MHz critical
    path at B≥6 (post-route WNS −0.74 ns, congestion-free). The latency is free — the drain
    writes during COMPUTE and the read happens in a later STORE — but `Core` must extend the
    drain-completion gate by `writeLat` (`drainFlush`, `Core.scala:336`) so a STORE issued off
    the IRQ still sees the last write landed.

  ⚠️ **Every character of the emitted SV is load-bearing**: `scripts/clone_preset_hw.sh`
  hashes it to decide whether the 1.3B clone may reuse the 2.7B bitstream, so even an added
  comment line inside those strings makes a legitimate clone get refused. The vestigial-looking
  `_pbanked` module-name tag is retained for the same reason.

### 3.4 1.6-bit weight packing — `systolic/WeightUnpack.scala`

Ternary carries `log2 3 = 1.585` bits; the PE code space uses 2. Since `3^5 = 243 ≤ 256`,
**five trits fit in one byte** as base-3 digits — 1.600 bits/trit, 99.1% of the information
floor, a flat **20% fewer weight bytes** on the stream that bounds decode.

```
byte = d0 + 3·d1 + 9·d2 + 27·d3 + 81·d4      (trit i at digit i, LSB-first)
digit 0 → PE code 0 (hold) | digit 1 → 1 (add) | digit 2 → 3 (subtract)
```

Values 243..255 are unreachable and the packer must never emit them; hardware treats them as
holds. Unpack is a **256-entry ROM of 10-bit words** (5 codes × 2 bits), so it is one LUT
lookup per byte with no arithmetic (`TernaryPack.lutWords`, `WeightUnpack.scala:55`).

The interesting part is the **`WeightUnpackGearbox`** (`:75`), because 5 does not divide
`outLanes`. Each input beat contributes `inBytes × 5` trits and each output beat needs
`outLanes`, so an elastic trit buffer regroups whatever arrives into full weight beats and
carries the fractional remainder across input beats. Capacity is `outLanes + inTrits`, which
guarantees a beat is always acceptable whenever it cannot be drained — no deadlock. A
synchronous **`flush`** exists because one dense packed stream per matmul can leave 1..4
padding trits behind; `Core` pulses it whenever `state === sIdle` so they never bleed into the
next stream's first beat. `CoreSpec` pins this directly ("packed: back-to-back COMPUTEs stay
aligned (buffer flush between ops)").

At the shipped geometry the wire beat is 64 B = 320 trits against a 256-trit weight beat, so
**each still-full wire beat carries ~1.25 weight beats** — fewer wire beats, no change to beat
width, port count or the enqueue handshake. `packedWeights` is a **compile-time RTL
parameter** (the gearbox only exists when it is set), so a packed bitstream requires a packed
blob; the runner hard-errors on a mismatch rather than streaming garbage (§6.6).

### 3.5 `CoreTop` — the Vivado-facing wrapper (`control/CoreTop.scala`)

A `RawModule` that exposes `aclk`, active-low `aresetn`, `s_axi_*`, `s_axis*`, `m_axis_*`,
`irq` — the names Vivado's packager auto-recognises as AXI4 / AXI-Stream. It inverts
`aresetn` into Core's synchronous active-high reset and is otherwise a pass-through, with one
real function: **the input-stream port join**.

One PS HP port carries at most 128 bits, and the shipped `axisDataWidth` is
`max(xDim, batchSize) × aWidth = 512` bits. So the stream arrives on
`numInPorts = min(4, ceil(bits/128)) = 4` parallel `s_axis_0..3` ports (suffixed individual
IOs, not a `Vec`, because `EmitCore` lowers with `disallowPackedArrays` and the packager needs
one flat interface per port), and `CoreTop` joins them:

```
tvalid = AND of all ports        tdata = Cat(ports.reverse)   // port 0 = LSBs
tready[i] = core.tready AND (all OTHER ports valid)           // no port may run ahead
```

`N == 1` is kept as a literal separate pass-through so single-port presets emit
byte-identical SV. `BenchMirrorSpec` covers the join with **staggered port valids** —
because the failure mode is silently losing beats, not deadlocking.

---

## 4. Parameterization: `CoreConfig` as the single source of truth

`control/CoreConfig.scala` (853 lines, over half of it recorded reasoning) is a case class
holding every `Core`/`CoreTop` parameter, all derived widths, the named presets, and — the
architecturally important part — **the manifest emitters**.

### 4.1 The manifest

`EmitCore` writes, next to the SystemVerilog, `preset.env` (KEY=VAL, `source`-able in bash and
trivially parsed in C) and `preset.json` (for the Python tools). **Every downstream consumer
reads the manifest instead of re-deriving geometry**: `build_all.sh`, `gen_overlay.tcl`,
`build_project.tcl`'s report naming, `software/Makefile`, the board Makefile, the weight
packer, and the C runtime. An array-size change is therefore a one-line edit in `CoreConfig`
with no hand-syncing — and the per-preset case tables that used to live in the shell scripts
(and silently drifted) are gone.

It carries geometry (`MMFREE_AWIDTH/XDIM/BATCH/MAXACC/MAXN/MAXM/SIGNED`), derived widths
(`NLANES`, `OUT_LANES_PER_TILE`, `ACC_WIDTH`, `OUT_LANE_BYTES`, `NUM_COL_TILES_MAX`), the
stream/HP-port split (`AXIS_DATA_WIDTH`, `NUM_DMA`, `MM2S_WIDTH`, `S2MM_WIDTH`,
`PORT_BYTES`), deployment policy (`PL_CLK_MHZ`, the Vivado impl directives), the HF checkpoint
(`MMFREE_MODEL`) and bench sweep (`MMFREE_SHAPES`), and the **u-dma-buf node sizes**
(`UDMABUF_ACT/WT/OUT_SZ`) derived from the geometry.

`load_manifest` in both `build_all.sh` and `gen_overlay.tcl` sets each key **only if unset**,
so documented environment overrides still win. The C runtime closes the loop the other way:
`mmfree_geom_check_manifest` (`mmfree_runtime.h`) cross-checks its *resolved* geometry against
the manifest at startup and WARNs (or aborts under `MMFREE_STRICT=1`) on any disagreement —
catching the classic footgun where the binary's geometry disagrees with the bitstream actually
in the PL, which otherwise surfaces only as a Core wedge and a completion timeout.

### 4.2 Derived geometry (the shipped presets)

| quantity | expression | 370M | 1.3B / 2.7B |
|---|---|--:|--:|
| `nLanes` | `aWidth/2` | 8 | 8 |
| `outLanesPerTile` | `xDim · nLanes` | 256 | 256 |
| `maxN` | `nextPow2(max(hidden, intermediate))` | 4096 | 8192 |
| `maxM` | `ceilTo(max(2·inter, vocab), 256)` | 32000 | 32000 |
| `numColTilesMax` | `maxM / outLanesPerTile` | 125 | 125 |
| `accWidth` | `aWidth + log2Ceil(maxAcc)` | 28 | 29 |
| `outLaneWidth` | `nextPow2(accWidth)` | 32 | 32 |
| `axisDataWidth` | `max(xDim, batch) · aWidth` | 512 | 512 |
| `numInPorts` / `portBytes` | `min(4, ⌈bits/128⌉)` | 4 / 16 | 4 / 16 |
| `outBeatWidth` | `outBeatLanes · outLaneWidth` | 128 | 128 |
| `outSubBeats` | `outLanesPerTile / outBeatLanes` | 64 | 64 |
| `outBramRows` | `batch · tiles · subBeats` | 64000 | 64000 |

Weight-stream arithmetic worth quoting: 4 ports × 16 B × 250 MHz = **16 GB/s**, which is
exactly one 256-trit weight beat per cycle at 2 bits/trit. The design is provisioned to
saturate its ports, and 1.6-bit packing is what buys headroom against that ceiling.

### 4.3 The three deployable presets

`CoreConfigs.deployable` is exactly three, one per released checkpoint, and `CoreConfigSpec`
asserts that ("the deployable set is exactly the three released checkpoints"). All three are
**B8 + dspAccum + colBankSize=4 + 1.6-bit packed weights + 128-bit m_axis @ 250 MHz + banked
output memory**.

| preset | checkpoint | layers/hidden/inter | maxN | WNS @250 | LUT | Reg | CARRY8 | BRAM | URAM | DSP |
|---|---|---|--:|--:|--:|--:|--:|--:|--:|--:|
| `mmfree_370m` | MMfreeLM-370M | 24 / 1024 / 2816 | 4096 | **+0.009** | 81.1% | 48.3% | 25.9% | 29.5% | 53.1% | 100% |
| `mmfree_1_3b` | MMfreeLM-1.3B | 24 / 2048 / 5632 | 8192 | +0.020* | — | — | — | — | — | — |
| `mmfree_2_7b` | MMfreeLM-2.7B | 32 / 2560 / 6912 | 8192 | **+0.020** | 83.9% | 49.8% | 25.9% | 49.7% | 50.0% | 100% |

\* 1.3B runs the 2.7B bitstream, so the 2.7B timing record is its own.

**Three presets, two bitstreams.** `maxN = nextPow2(max(hidden, intermediate))` and
`maxM = ceilTo(max(2·inter, vocab), 256)` put 1.3B and 2.7B on the *same* geometry, so
`CoreConfig` emits **byte-identical SystemVerilog** for them; they differ only in manifest
fields that never reach the Core (`MMFREE_MODEL`, `MMFREE_SHAPES`, `UDMABUF_WT_SZ`).
`scripts/clone_preset_hw.sh` assembles the 1.3B bundle from the built 2.7B bitstream in
seconds instead of ~40 minutes of Vivado, **refusing unless** (a) the two `generated/` dirs
hold byte-identical `.sv`, and (b) their `preset.env` differ only in those allow-listed keys.
The dtbo patch (re-sizing the `udmabuf-wt` nodes) is verified by re-decompiling and diffing
the DTS. `CoreConfigSpec` pins the RTL identity independently.

**What differs between the sizes** is three timing levers the `maxN=8192` pair needs and
370M does not: `dspAccum`, `colBankSize=4`, and the `actRamStyle="block"` /
`actCascadeHeight=2` pair. The last two are a **pair on purpose** — the paths they fix failed
**0.005 ns apart**, so fixing either alone moves WNS by 0.005 ns and wastes a 50-minute
build. `CoreConfigSpec` asserts they always travel together ("the maxN=8192 presets carry
both 250 MHz timing levers, not one").

**`forModel` is the constructor to use, and `.copy` is a trap.** `residentWtBytes` is a
constructor field that `forModel` recomputes from the model shapes *and the packing*, summing
the exact per-projection 1.6-bit blob size (5 trits/byte, each padded to a whole wire beat).
`.copy(packedWeights = false)` flips the flag and leaves the byte count at the **packed**
value, emitting a `UDMABUF_WT_SZ` that under-allocates against a 1.25× larger 2-bit blob. The
first version of the 2-bit control presets did exactly that, and the `CoreConfigSpec` check
"the 2-bit candidates size udmabuf-wt 1.25x their packed twins" is what caught it.

### 4.4 The 2-bit control arm

`CoreConfigs.candidates` holds `mmfree_{370m,1_3b,2_7b}_2bit`: each shipped preset with
`packedWeights` flipped and **nothing else touched**, so the pair isolates the weight format.
They exist because the report compares encodings across platforms and the CPU engine has all
three formats while the FPGA had only 1.6-bit — **2-bit is the one format every platform
shares**. Since `packedWeights` is an RTL parameter, they are separate bitstreams.

Measured 2026-08-16 (full b=1..8 ladder, five passes, all gates bit-exact): they close 250
with **more** margin than their packed twins (2.7B **+0.036** vs +0.020; 370M **+0.041** vs
+0.009) because dropping the unpack gearbox frees ~7k LUT (77.6% vs 83.9% at 2.7B) at
identical BRAM/URAM/DSP/CARRY8. And **2-bit is slower on the board by the amount packing was
measured to be worth**: mean decode delta vs the 1.6-bit twin is −2.3% (370M), −3.2% (1.3B),
−5.5% (2.7B), growing with model size and shrinking with batch (2.7B: −8.3% at b=1, −4.4% at
b=8) — the predicted shape for a bandwidth saving.

⚠️ Quoting this arm makes the three-way comparison *less* favourable to the board, because
2-bit is the Xeon's best encoding and the board's second-best (§7). Both framings are honest;
carry both and say which you mean.

### 4.5 `plClkRungs` — a measured constraint, not a preference

`pl_clk0` comes from `PL0_REF`, a divided PLL output, so it exists on **discrete rungs only**,
and the PS IP silently snaps an unreachable request **DOWN** to the rung below. Measured
2026-08-04 by reading `PSU__CRL_APB__PL0_REF_CTRL__ACT_FREQMHZ` back out of the configured PS
IP (`scripts/probe_pl_clk.tcl`, part `xck26-sfvc784-2LV-c`, IOPLL):

```
299.997  249.997  199.998  166.665  142.856  99.999 MHz
```

Consequences the report must not get wrong: every `_230` preset in the historical sweeps was
**really a 200 MHz build**, whose WNS was measured against 5.000 ns and not 4.348; the 225 and
230 bitstreams came out byte-identical; and the "275/285/290/295 ceiling sweep" was four runs
of the same 250 MHz constraint. **You cannot over-constrain through this field to buy
margin** — use `set_clock_uncertainty` instead. `CoreConfig` now `require`s `plClkMhz` to be a
rung, `build_project.tcl` reads the *constrained* period off the clock object and reports it
alongside the request (flagging `*** SNAPPED ***`), and `build_all.sh` appends the achieved
rate to the shipped manifest so the board sets `fclk0` from the constrained rate rather than
the label. Separately, `fclk0` is the *runtime* clock and **persists across overlay reloads**
— a stale rate is not evidence the overlay is up.

---

## 5. Timing closure: what bound each rung, and what each lever bought

This is the design's most report-worthy thread, because almost every lever was *measured*
rather than assumed, and several were measured and **rejected**.

| lever | targets | measured result |
|---|---|---|
| **outBram address folding** | 256:1 dynamic lane mux at 250 MHz | Deleted the mux by folding lane select into the row address; rows are one beat wide. |
| **`inQ` / `outQ` / `armSlice`** | BRAM read → wide fanout → PE; outBram → S2MM skid | Each terminates a cross-module path at a register. `armSlice` is B>1 only (the −0.39 ns path at B=2). |
| **`cmpRowsM1` + `activeIsLast`** | wide compare in the BRAM-enable cone | Shortened the enqueue address path to reg→mux→BRAM. |
| **`dspAccum`** | LUT/CARRY8 pressure | ~22 pp LUT, ~34 pp CARRY8. **The enabling condition for B8**, not an optimization: the old "~95% LUT wall" was measured before it existed. Full coverage wants `32·8·8 = 2048` DSPs against the K26's 1248, so ~800 lanes spill to fabric — and *that spill* is what created the route-bound broadcast path the next lever fixes. |
| **`colBankSize = 4`** | activation-broadcast fanout (74% route over 3 levels) | Halves fanout; numerically identical, +(banks−1) cycles. |
| **`ActMem` + `cascadeHeight=2`** | 8-deep RAMB36E2 hard cascade at `maxN=8192` = **3.206 ns of pure logic** | Caps it at one hop plus a fabric mux. Needed only at `maxN=8192`. |
| **banked `OutMemUltra`** | URAM read cascade + mux + cross-die route = 3.569 ns | +1.068 ns post-synth / +0.018 ns post-route for +240 LUT, +4 reg, same URAMs, no latency change. |
| **`writeLat` on the URAM** | drain write-address arithmetic → URAM at B≥6 (WNS −0.74) | Terminates it at a flop adjacent to the URAM; latency hidden. |
| **impl directives** | the last ~50 ps | `SYNTH_FLATTEN=full` + `AggressiveExplore` place/phys/route/post-route. **All three presets need it to make 250.** Carried in the preset (`implDirectives`) so `make build` needs no remembered env. |
| ~~cascade 4→2 on the output memory~~ | — | **REJECTED**: +0.028 ns and *worse* TNS. URAM288 CLK→DOUT is 1.837 ns before any cascade — the chain was never where the time went. |
| ~~UG901 single-always-block memory template~~ | — | **REJECTED**: absorption unchanged (`FF provided: 0`) and 0.3 ns worse, because it dropped `shreg_extract="no"` and the stages became SRL16Es. |

### The 300 MHz question is closed (2026-08-09)

Three levers were built and measured against the 300 rung: a **real nearest-neighbour
systolic array** (`systolic.TrueSystolicArray`), a **pipelined + replicated tile-capture
enable**, and the banked output memory.

```
banked, broadcast dataflow    @300   WNS −0.268   TNS −13241.762   94 071 failing endpoints
banked + capture + systolic   @300   WNS −0.310   TNS    −798.596    9 113 failing endpoints
```

Failing endpoints fell **90%** and TNS **94%** while **WNS did not move**: each fix works on
its own endpoint family and uncovers the next control net of the same shape (the gearbox
flush, then `arrLocalReset` into 1248 DSP `RSTP` pins at 90% route). Post-route Fmax is
**~274.5 MHz**, and `PL0_REF` has no rung between 250 and 300 — so all of it buys a
*deployable* clock of exactly **zero**, for a prize of ~5% on a decode that is
weight-bandwidth-bound anyway. Only the banked memory shipped, and only for margin at 250.
The array and capture levers live on `exploration` (`git show 6c4abb6`).

### The methodology finding behind that

`build_project.tcl` was changed because **the 30 worst paths are the wrong artefact to reason
from**, twice over. At 300 MHz the reported worst path was `core/wtGearbox` — 54 of 40 000
endpoints, 0.1% — while **74.2% of the failing population was one control net** (`core/state_reg`
driving the `tileAccums` capture enable). Fixing the headline path would have moved WNS and
nothing else. So every build now writes, while the routed design is still in memory:

- `reports/<tag>.rpt` — resource table + requested-vs-**constrained** clock + Est. Fmax;
- `<tag>.timing.rpt` — full summary + 30 worst paths;
- `<tag>.paths.txt` — one line per path with the **logic-vs-route split** (logic-dominated ⇒
  pipeline it; route-dominated ⇒ placement/congestion, pipelining will not help);
- `<tag>.endpoints.txt` — **the endpoint-family distribution**, bucketing bit indices and
  numeric suffixes and bucketing the *driver* the same way, because "74% share one driver" is
  the observation that picks the lever. It also states that a capped query is a censored
  sample;
- `<tag>.congestion.rpt`, and on a failing build (or `ARCHIVE_DCP=1`) the **routed DCP** —
  because `build_all.sh` uses one shared Vivado project dir, so the next build clobbers
  `impl_1/`, and a 300 MHz checkpoint has twice been lost to a later 250 build.

A failing-WNS build is **refused** by default (`ALLOW_TIMING_FAIL=1` downgrades it to a
warning for hardware experiments only) — Vivado writes the bitstream regardless, which was
harmless at 100 MHz and real risk at 250.

---

## 6. The glue: PS-side software

Four layers, each with a narrow contract, bottom to top.

### 6.1 `software/include/mmfree_core.h` — the ISA mirror

Register offsets, opcodes, error codes, the status bit layout, and `mmfree_encode()` — a
header-only mirror of `AxiInstructionHandler.scala`, so the C encoding and the Chisel decoder
cannot drift. Instructions are written as a **single 128-bit MMIO store** (NEON `vst1q` on
aarch64) so the AXI slave sees one beat.

### 6.2 `software/include/mmfree_dma.h` — AXI DMA, simple mode

A deliberately bare direct-register driver for Xilinx AXI DMA (PG021): no scatter-gather, no
IRQ wiring, poll `DMASR.Idle`. 40-bit addressing via the `_MSB` registers; **writing `LENGTH`
last commits the transfer**. Every spin is bounded (`MMFREE_DMA_SPIN_LIMIT`, ~10 s), so a
wedged engine or an unprogrammed PL becomes an error return instead of a silent hang — the
Core FSM has no watchdog, so this and the completion timeout are the *only* places a hang can
be detected.

### 6.3 `software/bench/mmfree_runtime.c` — the userspace runtime

Owns the OS-facing details:

- **Register windows** via `/dev/mem` + `mmap` (`O_SYNC`): the Core aperture and `num_dma` AXI
  DMA windows (DMA 0 is the full MM2S+S2MM engine; 1..N−1 are MM2S-only, built with
  `c_include_s2mm=0`).
- **Contiguous buffers** via ikwzm `u-dma-buf`, reading `phys_addr`/`size` out of sysfs. The
  `cached` flag is the interesting one: **`O_SYNC` forces a non-cacheable Device mapping**
  (slow CPU access, no cache management); dropping it gives a write-back cacheable mapping
  (fast) at the cost of explicit `sync_for_device` (clean, pre-MM2S) / `sync_for_cpu`
  (invalidate, post-S2MM) through sysfs, with `sync_size` set per call to the touched region,
  cache-line rounded.
- **Completion**: either the UIO IRQ path (`poll`/`read` on `/dev/uioN`, then ack **both** the
  UIO fd and the Core's IRQ register — `uio_pdrv_genirq` masks until userspace acks) or a
  **userspace spin on the status word**. Polling is the default and is worth **~+40% tok/s at
  b=1** (26→37), because the decode loop is dominated by short LOAD/STORE ops where the
  IRQ round-trip, not the op, sets the pace. It watches `irqPending`, **not `busy`** — `busy`
  has not asserted yet in the launch-latency window right after `push_instr`, so polling
  `!busy` would false-complete immediately. The clock is checked only every 1024 spins so the
  hot path stays a single MMIO read.
- **Geometry**: `mmfree_geom_init` derives every width from `(aWidth, xDim, maxAcc, maxN,
  maxM)` exactly as `CoreConfig` does, `mmfree_wt_port_bytes` is the single source of truth
  for the weight DMA length (both encodings), and `mmfree_geom_check_manifest` cross-checks
  against the bitstream's manifest.
- **High-level ops**: `mmfree_load` / `mmfree_compute[_off[_b]]` / `mmfree_store`. Each kicks
  the DMA(s) **first**, then pushes the instruction, then waits for the Core IRQ, then waits
  for every DMA to drain. **Every port must push the same byte count** — the `CoreTop` join
  only advances when all ports present a slice, so unequal counts deadlock.
- `mmfree_dump_state` prints Core status plus both DMA channel states, with hints mapping the
  fingerprint to a likely cause; it fires automatically on any timeout.

### 6.4 `software/integration/libmmfree/mmfree_lib.c` — the resident-weights engine API

This is where the per-projection performance work lives. Weights are copied into the udmabufs
**once** at open; a projection is registered by `(byte_offset, N, M)`, and a COMPUTE just
points the weight DMA at its offset — **no weight is ever re-copied**.

`mmfree_bitlinear_batch(h, proj_id, x, acc, b)` is the blocking primitive, five phases, each
individually timed into `mmfree_lib_stats_t`:

```
pack       scatter b×N int16 activations into port 0's beats  (+ sync_for_device)
LOAD_ACT   N B-wide beats -> actBram
COMPUTE_MM weights broadcast to all B rows                     (flags = b)
STORE_OUT  b × n_outputs lanes                                 (+ sync_for_cpu)
readback   sign-expand accWidth-bit accumulators -> int32
```

Three optimizations sit on top, and all three are measured:

**(a) Threaded readback** (default ON since 2026-08-06). Readback was the only phase still
single-core while the other three idled, and the one that scales ~linearly with batch. It is
partitioned **(row × column-block)**, not by rows alone — rows alone would leave b=1 fully
serial and would not divide 6 rows over 4 cores (the same `ceil(b/4)` wave problem the host
vector ops have). Measured at b=6:

| | readback µs/call | decode tok/s | SOM avg W | tok/J |
|---|---|---|---|---|
| 370M | 79.4 → 22.3 | 103.30 → **124.39** | 5.501 → 5.498 | **+20.5%** |
| 2.7B | 128.6 → 34.8 | 34.71 → **38.55** | 5.956 → 6.245 | **+5.9%** |

The loop speeds up 3.7× in both cases; what differs is what that buys. On 370M COMPUTE fell
20% too, because readback overrun had been spilling into the engine wait; on 2.7B compute is
392 µs/call and dwarfs readback, so the gain is the readback line alone. **Perf/W is not the
throughput delta** — on 2.7B the four cores cost 289 mW streaming DDR alongside a busier
engine, eating half the gain. One core sustains a flat ~4.0 GB/s on this loop at every shape;
four reach 8-13, so the ceiling is cluster memory bandwidth, not cores (M=8192 gets 3.6×,
M=32000 only 2.0×). The `MMFREE_RB_MIN_ELEMS=4096` threshold is **load-bearing**: at b=1,
M=1024 the whole call is 2.2 µs and the parallel region alone is ~1.8, so threading it
measured **0.55×**. Bit-exactness was not assumed — `make gate` with
`MMFREE_RB_MIN_ELEMS=1`, forcing every projection through the threaded path, passes on both
geometries (1160 / 1544 invocations, 0 element diffs).

**(b) Column-tile splitting** (`mmfree_bitlinear_batch_split_cb`). A projection's weight blob
is **col-tile-major**, so a contiguous range of output column tiles is a contiguous byte
range — a "chunk" is just the same projection with a shifted `byte_offset` and a smaller `M`.
No re-packing, no new blob, no manifest change. Every chunk reads the *same* activations, so
one pack + one LOAD serves them all (the RTL keeps `actBram` across COMPUTEs), which turns one
serialized projection into a self-pipeline: **chunk j−1's readback runs on the CPU while the
engine computes chunk j**, and readback was ~28% of engine time on the big projections and
pure dead time before. A per-chunk callback lets the *caller's* per-column work (the dequant,
and in principle the elementwise ops) ride inside the same window — which is exactly what
`bitlinear`'s `matmul_batch_chunked` seam consumes.

Chunk boundaries are **constrained by the encoding, not chosen freely**. 2-bit: offset
`t·N·portBytes`, always legal. 1.6-bit: tile `t` starts at trit `t·N·olt`, i.e. byte
`t·N·olt/5`, which is only a valid DMA start when it divides exactly **and** lands on a wire
beat. With `olt = 256 ≡ 1 (mod 5)` legality reduces to `t·N ≡ 0 (mod 5)`, so a hidden dim not
divisible by 5 only permits `t ≡ 0 (mod 5)`. The original halve-until-legal loop collapsed to
k=1 on exactly the projections worth splitting; it was replaced by **snapping each even
boundary outward to the nearest legal tile**, which recovers 370M `gate_proj` (~41% of its
weight traffic) from k=1 to k=2 and 1.3B `gate_proj` to k=4, and is a no-op at distance 0
wherever the even split was already legal (so previously measured 2-bit and 2.7B numbers stay
valid). `mmfree_split_plan` reports the plan without running it, from the same
`chunk_boundaries` the driver uses, so the plan you can inspect *is* the plan that runs.
`fpga_backend.cpp`'s `split_chunks(M)` ladder — 8 at M≥16384, 4 at M≥8192, 2 at M≥4096, else
1 — encodes that splitting is a **trade**: each chunk costs a COMPUTE-issue + STORE
round-trip, so the win peaks where chunk COMPUTE ≈ chunk readback and goes negative if you
over-split a small projection.

**(c) The pipelined cluster** (`mmfree_seq_*` + `FpgaBackend::matmul_seq`). The engine is
strictly one-op-at-a-time (1-deep instruction buffer, single `irqPending`), so the blocking
primitive is split into issue/wait and the CPU work of the *neighbouring* projections is
slotted into the COMPUTE wait window:

```
for n in 0..k:  compute_issue(n)
                  readback+consume(n-1)      # OUT still holds STORE(n-1)
                  produce+quant+pack(n+1)    # ACT free since LOAD(n) drained it
                compute_wait(n) -> store_issue -> store_wait -> load(n+1)
```

ACT and OUT are single-region, which dictates the ordering above exactly. `MMFREE_NO_PIPELINE=1`
restores the strictly serial default for a token-equivalence A/B. The model calls this for
the **i/f/g cluster** — the only three projections in a block with no mutual dependency.

Two practical notes recorded in the source: **glibc `memset`/`memcpy` can SIGBUS on a
Device-memory udmabuf** (the `dc zva` path), so all writes into an uncached buffer go through
volatile word loops (`dev_copy`/`dev_zero`, `mmfree_pack_act_beat*`); and `open_buf_checked`
distinguishes three failures that need three different fixes — **ENOENT means the overlay is
not loaded** (the common one after a reboot), other errors mean permissions or a wedged
driver, and only genuinely-short means "regenerate the overlay with a bigger `UDMABUF_WT_SZ`".
Printing one message for all three cost a debugging round trip.

### 6.5 `fpga_runner` — the seam, and the measurement front-end

| file | role |
|---|---|
| `fpga_backend.{hpp,cpp}` | `FpgaBackend` (drives libmmfree) and `CompareBackend` (the gate) |
| `manifest.{hpp,cpp}` | parse `<prefix>.manifest.tsv` → `tag → (byte_offset, N, M)`; TSV, not JSON, keeps the runner dependency-free |
| `main_fpga.cpp` | open the engine, load resident weights, register projections, `set_backend`, then bench / gate / serve / generate |

`FpgaBackend` implements four `TernaryBackend` virtuals: `batch_size()` returns the
**bitstream's compiled width** (which is what makes `bitlinear` chunk its rows correctly),
`matmul_batch` narrows `int32 → int16` (already saturated by the Q5.10 quant; clamped
defensively) and issues one engine call for all `b` rows, `matmul_batch_chunked` adds the
per-column-range callback, and `matmul_seq` does the cluster pipeline. `proj_id` addresses the
resident weights, so the CPU `wq` pointer is ignored entirely.

`CompareBackend` is the **gate**: it computes the CPU reduction into `acc` (so generation stays
valid) *and* runs the engine into a scratch buffer, then compares element by element,
accumulating `calls`, `projections_with_mismatch`, `total_elem_mismatches`, `worst_abs_diff`.
This is a bit-exactness check, not a tolerance check — see §7.

`main_fpga.cpp` arms: `--backend cpu | cpu-2bit | cpu-1p6 | fpga | both`. The two packed CPU
arms exist so a CPU-vs-FPGA comparison can **hold the weight format fixed** — plain `cpu` is
really an 8-bit-*storage* baseline. `--serve N` decodes N streams with batched decode;
`--distinct-prompts` gives each stream its own prompt (left-rotations, so all B have the same
length and timings stay comparable) and checks each against its **own** single-stream
reference, which is the only form that can see a row permutation. `--csv` emits one
`#CSVH`/`#CSVR` pair for scripted sweeps.

Two diagnostics in that file are pure hard-won process:

- **It prints the actual SIMD build, widened from the macros the packed-ternary kernels switch
  on**, because `simd::backend()` returns the literal `"x86-avx2"` for any x86 build including
  AVX-512. Getting it wrong costs 31% (1.6-bit) / 24% (2-bit) with *nothing else moving* —
  int8 has no AVX-512 path, so a narrow build looks completely healthy in the int8 column. A
  stray rebuild mid-sweep did exactly this on 2026-08-06 and the old line printed the same
  string before and after, which made two run-groups look valid.
- **It flushes that banner immediately**, because piped stdout is fully buffered and anything
  that aborts later discards it — leaving a caller probing the binary for its build unable to
  tell a narrow build from an unreadable one.
- It also records **`fclk0` as read from the PS clock driver** alongside the manifest's
  `PL_CLK_MHZ`, because those disagreed once and a 295 MHz build quietly ran at 250.

### 6.6 `mmfree_pack` — the offline weight packer

Pure numpy, board-free and torch-free (torch is imported lazily only to load a raw
checkpoint). The normal path packs **directly from the CPU engine's `model.mmfree`**, so the
engine's ternary codes are *identical* to the CPU path's `wq` — which is the precondition for
the exact-match gate meaning anything.

```
s        = 1 / mean(|W|)                      (per projection, into the manifest)
w_t[m,n] = clamp(round(W[m,n] · s), -1, 1)    (== the CPU engine's weight_quant)
engine     acc[m] = Σ_n x_int[n] · w_t[n,m]
runtime    y[m]   = acc[m] / (a_scale · s)    (the caller dequantizes)
```

Geometry comes from `preset.json`, so the packer never guesses the bitstream's shape
(`Geometry.from_manifest`), and `Geometry` is a line-by-line mirror of `mmfree_geom_init` —
including `port_blob_bytes`, which must agree with the C runtime's DMA length exactly.
Orientation matters: `nn.Linear` weight is `(out=M, in=N)` and `F.linear` computes `x @ Wᵀ`,
so the engine's `w_t[n,m]` is `W[m,n]` — the packer indexes the transpose.

**The two byte layouts are genuinely different, not a variation on one:**

- **2-bit** (`pack_projection`) — col-tile-major, and each port takes its own **lane slice**:
  byte `(t·N + n)·portBytes` of port `p` packs that port's 64 ternary lanes, 4 per byte at bit
  `2l`, where lane `l` is output column `t·256 + l`. Ternary → code is just `v & 0x3` in
  two's complement (−1 → `0xFF & 3` = 3), matching `Core`'s encoding for free.
- **1.6-bit** (`pack_projection_packed`) — the gearbox reads the **whole assembled wire beat**
  as a flat little-endian byte stream, so the flat `[t][n][lane]` trit sequence is packed
  5/byte in base 3, padded to a whole wire beat, and then **each wire beat is sliced
  `portBytes` at a time across the ports** (byte `b` → port `b // portBytes`, port 0 = beat
  LSBs, matching `CoreTop`'s `Cat(ports.reverse)`). This is *not* the 2-bit path's per-port
  lane split, and confusing the two produces plausible garbage.

Both emit `<prefix>.port{0..3}.bin` plus `<prefix>.manifest.{json,tsv}` carrying a
**`weight_format` tag** (`ternary-1p6` / `ternary-2bit`). The runner refuses to start when
that tag disagrees with `MMFREE_PACKED_WEIGHTS` (an empty tag means a pre-tag, necessarily
2-bit blob), because a 1.6-bit blob fed to a 2-bit engine is silent garbage. The board
Makefile derives `PACKED_DIR` (`packed` vs `packed-1p6`) from the manifest for the same reason
— before that, every sweep script had to pass `--packed-dir` by hand and a forgotten one
looked like a gate bug.

Note the *projection set* differs by consumer: packing from a blob emits one projection per
BitLinear (`i/f/g/o/gate/down` × layers + `lm_head` = 145 for a 24-layer model), matching the
C++ block, which calls i/f/g separately on a shared post-norm input. The older
checkpoint path could fuse i/f/g into one `1024×3072` matmul as a perf step — exact only if
the three RMSNorm weights are folded in at pack time, since each has its own norm.

### 6.7 `software/bench/bench.c` — the shape sweep

The hardware-only harness, independent of the model: for each shape it packs random
activations and ternary weights, issues LOAD/COMPUTE/STORE with each timed by
`CLOCK_MONOTONIC_RAW`, optionally verifies against a host reference matmul
(`MMFREE_VERIFY=1`), and emits one row per shape (aligned table on a terminal, CSV when
piped). Sweep modes come from `MMFREE_SHAPES`: `pow2` (an N×M cross sweep), `370m|1.3b|2.7b`
(every distinct projection of that checkpoint — `ifgo_proj`, fused `gate_up`, `down_proj`,
`lm_head` — plus a per-token cost summary and compute-phase efficiency against the HP-port
stream peak), or an arbitrary model from `MMFREE_HIDDEN/INTER/VOCAB/LAYERS`. Physical
addresses and device nodes are CLI args, and geometry is env-driven from the manifest, so one
binary serves any board config and any array size without recompiling.

---

## 7. Correctness: what is checked, and what each check cannot see

### The exactness argument

Every arithmetic step from the CPU engine's quantized activation to the engine's accumulator
is integer, and integer addition is associative. So the FPGA result is **bit-identical** to
the CPU reduction, not close — lane order, column tiling, batch width, banking depth, and the
weight encoding all cannot change it. That is what makes a *gate* (equality) possible instead
of a *tolerance*, and it is the property every other check leans on.

### The check ladder

| check | where | what it proves | what it is blind to |
|---|---|---|---|
| `make gate` (`--backend both`) | board | Engine == CPU reduction, **bit-exact, every projection, every decode step**. Passes: 1160 / 1160 / 1544 invocations, **0 element diffs** on all three presets (2026-08-15) and all three 2-bit twins (2026-08-16). | Nothing about batching layout — it compares per row. |
| `make streams` (`--distinct-prompts`) | board | **Row independence**: B streams from B different prompts, each vs its OWN single-stream reference. Reports `ROW PERMUTATION` explicitly. | — (this is the check that closes the gap below) |
| sweep `streams_ok` column | board | Batched decode == single-stream, no stream dropped. | **Permutation.** Every stream gets the same prompt and sampling is greedy, so the streams are identical by construction and the check is invariant under a row swap. It cannot see the drain writing row 2 into stream 5. |
| `CoreSpec` (939 lines) | sim | Numeric equivalence against a reference matmul over 5 random seeds, per-tile independence, packed == 2-bit path, **gearbox flush between back-to-back COMPUTEs**, split-chunk COMPUTEs on one LOAD == the whole projection, runtime `batch=1` drains only row 0, LOAD at full BRAM depth, banked-memory bank selection, every `(ram_style, cascade_height)` variant. | Real timing, real DDR, temperature. |
| `PESpec` (681) | sim | Every weight code, wrap-around, all `aWidth` variants, and the **negative** handshake case ("valid gap on the final accumulation cycle stalls — no phantom beat"). | |
| `SystolicArraySpec` (332) | sim | Batched matvec, handshake gating in idle and running, randomized ops across `(aWidth, xDim, yDim)` **and `colBankSize`/`numBanks`**. | |
| `BenchMirrorSpec` (605) | sim | The **host packing loop** matches a reference matmul — including a deliberate **regression guard that row-major (yesterday's bug) does NOT match**, negative-accumulator wrap, a non-pow2 inner dim, per-row non-contamination, and **staggered port valids on the N-way join**. | |
| `WeightUnpackSpec` (96) | sim | `TernaryPack` round-trip, ROM words == `unpackByte`, gearbox streaming **and under backpressure**, at two geometries. | |
| `AxiInstructionHandlerSpec` (455) | sim | Dispatch per opcode, DECERR/SLVERR cases, status semantics, IRQ raise/ack, back-to-back sequencing. | |
| `CoreConfigSpec` (396) | sim | The registry *is* exactly the three + three candidates; each 2-bit twin differs only in format; **udmabuf-wt ratio 1.25×**; every preset carries the full stack incl. `dspAccum`; identical geometry across sizes; every projection tile-aligned; 1.3B/2.7B RTL identity; `plClkMhz` on a rung; the two `maxN=8192` levers travel together. | |
| `test_packed_roundtrip.py` | host | Packer byte layout **byte-identical to `bench.c`'s pack loop**. | |
| `test_split_chunks.c`, `test_readback.c` | host | Split plans tile a projection exactly; threaded readback == serial. | |

Simulation runs on Verilator through ChiselSim, with one infrastructure fix worth noting:
`testutil/SingleThreadVerilator` pins each Verilator invocation to `-j 1`, because Mill fans
suites out across cores and each Verilator build wanted all of them, producing intermittent
`Internal Error: attempted to destroy locked Thread Pool` aborts in whichever suite lost the
race.

**The gate is what promotes "build-verified" to "shipped"** — `scripts/gate_all.sh` exists as
a script rather than something automated because `deploy-hw` needs an interactive sudo
password on the board. A wrong engine result is not a crash; it is a plausible number.

---

## 8. Results

### 8.1 Board, five passes, POWER=1, b=8 (`reports/final/dataset.csv`)

| model | FPGA b=8 tok/s | FPGA tok/J (net) | A53-only tok/s | speedup |
|---|--:|--:|--:|--:|
| 370M | **137.06** | **66.79** | 7.51 | **18.2×** |
| 1.3B | **66.27** | **25.08** | 2.05 | **32.3×** |
| 2.7B | **44.69** | **14.92** | 1.02 | **43.8×** |

The A53-only column is that arm at b=1 (1.6-bit); it is flat in batch — 7.48 / 2.07 / 1.03 at
b=2 — which is itself the finding: on a general-purpose core the weight sweep is redone per
stream, so batching buys nothing. The board's CPU arms were deliberately run to b=2 only,
since a 2.7B A53 decode at ~1 tok/s costs hours to resolve a difference the FPGA arms already
bound.

At b=1 the engine alone is worth 5.5× / 9.9× / 11.5× over the A53 path; batching multiplies
that. The speedup **grows with model size**, which is the expected shape for a
weight-bandwidth machine beating a general-purpose core.

### 8.2 Against a Xeon W-2155 workstation (20 threads) running the identical engine

| model | fmt | K26 tok/s | Xeon tok/s | ratio | K26 tok/J | Xeon tok/J | **energy ratio** |
|---|---|--:|--:|--:|--:|--:|--:|
| 370M | 1.6-bit | 137.06 | 208.37 | 0.66× | 66.79 | 2.124 | **31.4×** |
| 370M | 2-bit | 133.43 | 235.52 | 0.57× | 64.57 | 2.332 | 27.7× |
| 1.3B | 1.6-bit | 66.27 | 64.47 | **1.03×** | 25.08 | 0.623 | **40.2×** |
| 1.3B | 2-bit | 69.87 | 104.86 | 0.67× | 26.44 | 0.910 | 29.1× |
| 2.7B | 1.6-bit | 44.69 | 33.24 | **1.34×** | 14.92 | 0.317 | **47.1×** |
| 2.7B | 2-bit | 42.73 | 54.59 | 0.78× | 15.35 | 0.463 | 33.1× |

Both platforms at b=8, 20 threads on the Xeon, and **every row's tok/s and its watts come from
the same run**. Xeon rows are the batched re-sweep (`reports/final_batched_20260825/`): the
runner now constructs the CPU backends with the `--serve` width, so `matmul_batch` is actually
entered. The earlier `reports/final/` Xeon rows decoded the b streams one after another; the
batched arm reads 10-43% higher at b=8 on 2-bit, and on 1.6-bit only 370M moves (+39%). Note the format asymmetry that makes the framing choice matter: **2-bit is the
Xeon's best packed encoding and the board's second-best** (the Xeon is ALU-bound, so cheaper
extraction wins; the board is bandwidth-bound, so fewer bytes win). Carry both pairings.

⚠️ **tok/J here is NET OF IDLE and is not comparable to any perf/W number published before
2026-08-15.** The SOM idles at 3.5 W of a 5.6-6.5 W total and the workstation package at ~13 W; dividing by *total* draw credits the board for having less overhead to switch off rather
than for doing the work more cheaply. Net is the like-for-like figure, and it is only
meaningful because both sides measured their own idle with their own instrument in the same
session. `watts_total` and `power_domain` (`board_rails` via `xlnx_platformstats` vs
`rapl_pkg+dram`) are carried on **every** row, so the old basis stays recomputable and the
boundary each number was measured across is never in doubt. The board's idle baseline is
re-sampled every sweep and applies to the whole table (2026-08-16 read 3.504 W against
2026-08-15's 3.521 W — 0.49% apart, so this column moved <0.9% and the ranking not at all).

### 8.3 Where the time goes (2.7B, b=8, 1.6-bit, `reports/final/ops_2_7b.csv`)

| op-level | % | | engine phase | % |
|---|--:|---|---|--:|
| `matmul` | 72.5 | | `compute` | 50.9 |
| `swiglu` | 14.1 | | `pack` (host → udmabuf) | 27.7 |
| `gate` | 3.8 | | `store` | 11.6 |
| `swishgate` | 3.0 | | `readback` | 6.5 |
| `elementwise` | 2.6 | | `load` | 3.3 |
| `lm_head` | 1.5 | | | |
| `rmsnorm` | 1.5 | | | |
| `scan` | 1.0 | | | |

So **~74% of decode is the offloaded projection** and ~26% is host vector work that was never
a candidate for offload; and *within* the offloaded part, only about half is the engine — host
activation packing is the second-largest line. That, not the fabric clock, is where the next
gain is.

### 8.4 What each measured lever was worth on the board

| lever | landed | measured |
|---|---|---|
| B4 → B6 | — | +11.8% |
| B6 → **B8** | 2026-08-06 | +11.5% (370M) / +15.4% (2.7B) at b=8, and **free below b=8** (b=6 within 0.7% of the B6 bitstream). Promoted. |
| **Threaded readback** | 2026-08-06 | +20.4% (370M) / +11.1% (2.7B) decode; tok/J gain smaller (+20.5% / +5.9%) |
| **1.6-bit packing** | 2026-07-26 | +4.3% tok/s, COMPUTE −15.4% at 2.7B b=6; confirmed 2026-08-16 as −2.3…−5.5% for the 2-bit control across a full ladder |
| **Column-tile splitting** | — | +4.4% at b=4, gain scaling with batch, bit-exact; a k-sweep says the ladder is already optimal (k=4 peak, k=16 net-negative) |
| **`MMFREE_POLL`** | — | ~+40% at b=1 (26 → 37 tok/s) |
| **`-O3` + `-mcpu=cortex-a53`** | 2026-07-30 | host CPU time −12.8%, decode +4.6% (29.56 → 30.91), gate still passing |
| 250 MHz vs 200 | — | only ~5% — a bandwidth-bound decode barely notices the fabric clock |
| **Banked output memory** | 2026-08-09 | **±1.5% on every cell** — shipped for 10× WNS margin (+0.002 → +0.020), not for speed. That non-result is the point: it is what shipping-for-margin predicted, and it is now measured rather than assumed. |

### 8.5 One known-unstable cell, carried openly

1.3B's host `pack` is **bimodal** (slow ~216 µs vs fast ~140 µs per call, visible per pass in
the `bl_pack_us_call` column of every sweep CSV), so its top rung moves between sessions by
more than the effect size of anything you would want to measure there: 71.53 (08-06), 62.42
(08-07), 66.27 (08-15), all five-pass medians differing only in the mode mixture drawn. The
flagged rung moves too (b=6, then b=8, now b=7). **b=1..6 reproduce to ±0.4% and are the
quotable part of that row.** The 2-bit arm sharpened the diagnosis: b=6/b=7 are bimodal in
**both** encodings (so it is not the 1.6-bit path), but at b=8 the 1.6-bit arm draws the slow
mode on all five passes while 2-bit is clean (3.5% spread) and its ladder stays monotone to
69.87. Same host code, different `UDMABUF_WT_SZ`, therefore different udmabuf-act placement —
which is the best evidence yet that this is **buffer placement, not the scatter**, and means
66.27 is *depressed by the pack mode, not a throughput ceiling*.

---

## 9. Build, deploy, measure

### 9.1 `scripts/build_all.sh` — six steps

```
1. mill EmitCore <preset>       -> generated/<preset>/*.sv + preset.env + preset.json
2. package_ip.tcl               -> user.org:user:CoreTop:1.0 (auto if component.xml absent;
                                   REPACKAGE=1 forced when ports OR the SV FILE SET changed —
                                   the packaged IP carries a fixed manifest, so a module in a
                                   new file synth-fails as "module not found")
3. build_project.tcl + bd.tcl   -> bitstream + build/t_matmul.xsa  (+ all the reports of §5)
4. gen_overlay.tcl (xsct)       -> core_bench.{bit,dtbo}, shell.json
5. bootgen                      -> core_bench.bit.bin  (for fpga_manager / xmutil)
6. assemble                     -> transfer/presets/<preset>/ + staged shared parts
```

`STAGE_ONLY=1` (`make stage`) re-stages only the preset-independent bundle parts — the board
Makefile, the board scripts, and the C++ sources under `src/`. This step is not optional
hygiene: **the board compiles from `transfer/src/`, so editing `software/` or `scripts/`
without re-staging means the next sweep silently measures the previous code.** Other gates:
`SKIP_SV`, `BITSTREAM_ONLY`, `PACKAGE_ONLY`.

The block design (`vivado/bd.tcl`, exported from IP Integrator) is Zynq US+ PS + **4× AXI DMA**
+ `CoreTop`: DMA 0 is a full MM2S+S2MM engine, DMAs 1..3 are MM2S-only, one HP port each.
`generated/` and `transfer/` are both build products and gitignored; the board-side files
under `transfer/` are *tracked* in `scripts/` and staged from there.

### 9.2 The overlay

`gen_overlay.tcl` deliberately drives `xsct createdts` rather than hand-writing a DTS, because
a hand-written overlay misses the fragments that make it actually work: `&fpga_full`
`firmware-name` (so fpga-manager programs the PL at all), the `clocking0` `xlnx,fclk` node (so
`PL0_REF` is claimed and needs no `clk_ignore_unused` hack), `afi0` (PS↔PL AXI port-width
config), and correctly-addressed CoreTop/axi_dma nodes with their IRQs. It then patches
`firmware-name`, rebinds the CoreTop placeholder to `generic-uio`, and **appends the
u-dma-buf nodes sized and counted from the preset manifest** — `NUM_DMA` act/wt buffers plus
one shared out. The hand-tuned case tables that used to live here (and drifted from
`CoreConfig`) are retired.

### 9.3 On the board

`deploy_kria.sh`: install the three firmware files where `xmutil` expects them; ensure
`clk_ignore_unused` is on the kernel cmdline (without it the kernel disables the unclaimed PL
clock ~30 s after boot and every PL MMIO access bus-errors or hangs the CPU — needs one
reboot); build and `insmod` the vendored `u-dma-buf` **before** the overlay so the driver binds
the nodes it adds; then `xmutil unloadapp` / `loadapp`.

**CMA release is the fragile step, and is verified rather than assumed.** `u-dma-buf`
allocates `udmabuf-wt` from CMA when the overlay *binds* and frees it when the overlay is
*removed* — but not instantaneously, and `unloadapp` used to run with its output and exit
status discarded. Chaining presets then loaded the next, bigger overlay while the previous
one's CMA was still held, and the failure surfaced far downstream as a missing
`/dev/udmabuf-wtN`, which reads like a driver bug rather than "the last preset had not let go
yet". So it now unloads, **waits for `CmaFree` to stop rising**, and only then loads. Ascending
model order is the worst case: each step needs a bigger *physically contiguous* slice, so a
late large allocation can fail even when the total free figure looks sufficient. Helpers:
`cma_reclaim.sh`, `cma_boot_raise.sh`.

### 9.4 The measurement harness

- **`power_bench.sh`** samples SOM rail power with `xlnx_platformstats` for *exactly* the
  workload window (sampler started immediately before, killed the instant the command
  returns), writing `power/<geometry>_<mode>.{log,samples}`. The workload's stdout passes
  through untouched, so `make bench | tee x.csv` still works. Degrades gracefully when the
  package is absent.
- **`bench_batch.sh`** runs the runner once per `b = 1..MMFREE_BATCH` (ladder read from the
  manifest, not hardcoded) into one CSV: TTFT, prefill latency, per-step token latency,
  aggregate and per-stream tok/s, plus the op-level and engine-phase breakdowns as columns.
  `BACKEND=cpu|cpu-tq1|cpu-2bit` runs the identical ladder with the engine out of the loop —
  a true apples-to-apples control, since only the `TernaryBackend` differs.
- **`final_sweep_board.sh` / `final_sweep_xeon.sh`** produce the report data set. Four
  deliberate methodology choices, all documented in the script header:
  1. **POWER=1 on every arm, including the CPU ones.** Rail sampling costs ~2% decode, so
     mixing sampled and unsampled rows silently biases every comparison drawn across them —
     that has already manufactured one phantom regression. Sampling everything makes the tax a
     constant of the method, and it means each row's tok/s and its watts come from the same
     run so tok/J is exact rather than spliced.
  2. **Passes are separate processes, not reps.** On 2026-08-04 one (arm, batch) cell read 12%
     low and agreed to **0.12 tok/s across 3/3 reps**, because whatever went wrong was fixed
     for that process's lifetime. Only a fresh process — and for the FPGA a fresh overlay load
     — resolves it.
  3. **Gate before timing**, per bitstream per session, at temperature.
  4. **Check row independence once per bitstream** with `make streams`, because the sweep's own
     `streams_ok` cannot see a permutation (§7).
- **`final_dataset.py`** merges both halves into `reports/final/dataset.csv` (one row per
  platform/arm/model/format/batch, identical shape across platforms) plus per-model
  `ops_<model>.csv`. It **refuses to emit a clean data set** when any row is missing power,
  when POWER settings are mixed, when `streams_ok=0` on any FPGA row, when passes disagree by
  more than the limit, or when an arm falls short of the batch ladder (read from the CSV's own
  `hw_batch_max` column, so one aggregator validates a B8 set and a B6 set without either
  being wrong by definition). It prints the idle baseline it used on every run.

---

## 10. Footguns

Ordered by how much time each has cost, per the recorded notes.

1. **`EmitCore` does not clean its output directory.** Re-emitting a preset whose geometry
   changed leaves stale `.sv` behind (a B6 `actBram_4096x96.sv` next to the B8 `_128.sv`),
   which makes any hash-all-the-SV comparison wrong — *including* `clone_preset_hw.sh`'s RTL
   check, so a legitimate clone gets refused. `rm -rf generated/<preset>` after a config
   change.
2. **A stale `transfer/` bundle silently measures old code.** `make stage` after any edit to
   `software/` or `scripts/`.
3. **`plClkMhz` off a rung is a mislabel, not a slower clock** (§4.5), and `fclk0` persists
   across overlay reloads, so a stale rate is not evidence the overlay is up.
4. **`.copy(packedWeights = …)` leaves `residentWtBytes` wrong** — use `forModel` (§4.3).
5. **Blob/bitstream format mismatch is silent garbage** without the `weight_format` guard; the
   1.25× larger 2-bit `udmabuf-wt` (2.7B: 500 → 624 MiB over 4 ports) fits the 1536 MiB CMA but
   **not** an older `cma=1000M` boot arg, and the overlay carries the size, so a cloned or
   reused overlay will be too short.
6. **CMA leaks across overlay cycles** — `cma_reclaim.sh` or a reboot before a large run, and
   confirm `CmaFree` ≥ ~700 MiB.
7. **`sudo` strips the environment.** Every board verb hands tuning variables through
   `sudo env …`; `MMFREE_POLL` defaulting differently in code than in a README once caused a
   false FPGA regression hunt.
8. **Unequal per-port DMA byte counts deadlock** the `CoreTop` join. Ports 1..3 stream zeros
   during LOAD precisely to keep the counts equal.
9. **glibc `memset`/`memcpy` SIGBUS on Device-memory udmabufs** — volatile word loops only.
10. **Reason from the endpoint *distribution*, never the worst path** (§5), and remember a
    capped `get_timing_paths` query is a censored sample.
11. **`pgrep -f X` matches the agent's own shell**, so a "still running?" guard never releases
    and a queued build silently never starts. More generally: **test that a guard both fires
    and releases** — four guard bugs in one session all produced plausible non-failures.
12. **A repeatable bad benchmark cell is not a real one.** Reps agreeing to 0.12 tok/s proved
    nothing; only a fresh process + overlay disproves it. Diff `bl_*_us_call` against a sibling
    arm first.
13. **Match harness flags before believing a delta** — `POWER=1` alone is ~2%.
14. **Prefer b=4 or b=8 over b=5..7.** The host's vector ops are OpenMP-static over the batch
    on four A53s, so they cost `ceil(b/4)` waves and only 4 and 8 fill the waves they pay for.
15. **`make streams` is not optional** for any claim about batched decode row ordering.

---

## 11. What is closed, and what is open

**Closed, with the measurement recorded** (do not re-derive): 300 MHz (post-route Fmax
~274.5, no rung between 250 and 300, ~5% prize on a bandwidth-bound decode); a true
nearest-neighbour systolic array and a replicated tile-capture enable (together −90% failing
endpoints, −94% TNS, **WNS unmoved**); shortening the URAM cascade; the UG901 memory template;
output-memory banking as a *speed* lever (it is a margin lever, ±1.5% on throughput); B6 as a
shipped configuration (retired 2026-08-07, `git show d63d9f9`); the batch ladder above b≈8 on
this array; and the 2-bit-vs-1.6-bit encoding question on the board. The ~50 ablation presets
and their sweep harness live on the `exploration` branch — adding a preset back to `main`
needs a reason beyond "it was interesting", and `CoreConfigSpec` enforces that.

**Open, and where a report should point next:**

- **Host activation packing is 27.7% of engine time at 2.7B b=8** and the largest single
  remaining line after COMPUTE. One attempt is already recorded as a *negative* result:
  fusing the int32→int16 narrow into the strided scatter was **10-14% slower**, because the
  sequential pass was the cheap one. Whole-beat writes remain untested in isolation.
- **The 1.3B pack bimodality** (§8.5) is diagnosable from data already on disk — every sweep
  CSV carries `bl_pack_us_call` per pass — and the 2-bit arm has already narrowed it to buffer
  placement. No new runs needed.
- **2.7B's +0.020 ns WNS has never been seed-swept.** The 200 MHz rung (~5% slower, far more
  margin) is the fallback if a future build regresses; the per-session gate is what makes
  shipping a thin margin defensible at all.
- **The full-DSP question**: 100% of the K26's 1248 DSPs are used and ~800 accumulator lanes
  still spill to fabric at B8. A part with more DSPs, or a lane-sharing scheme, changes the
  capacity/route trade this design is currently balanced against.
