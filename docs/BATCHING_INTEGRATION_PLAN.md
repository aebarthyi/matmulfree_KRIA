# Batching Integration Plan (Chisel → application)

**Status:** planning · **Created:** 2026-06-24 · **Owner:** Andrew

## Context & goal

FPGA decode of MMfreeLM is **DDR/HP-port bandwidth-bound**, not compute-bound: the
~92 MB of ternary weights can't be resident, so every token re-streams the full weight
set, and we're already at ~96% of the 4×128-bit HP-port wall (see
`udmabuf_cached_mapping_speedup` memory + `multi_hp_port_scaling`). The CPU-side overhead
around the stream is largely optimized out (cached udmabuf → fast-pack → `MMFREE_POLL`,
10.3 → 26.2 tok/s). The only remaining lever that moves the bandwidth floor is **batching**:
apply each streamed weight to B activation vectors so one weight stream produces B results.

**Decisions taken (2026-06-24):** target **both** prefill-latency and serving-throughput;
take it through to **1B/2.5B** models; grow the **batch (yDim)** dimension and keep
aWidth=16 (the int16-exact validated path), since xDim=32 already saturates the 4 ports and
batch rows reuse the same weight beat (bandwidth-free growth, no extra ports for B ≤ 8).

## Key architectural finding

**The hardware spatial-batching datapath already exists** and is the right design — it is
not net-new. `CoreConfig.batchSize → yDim` makes the systolic array a 2-D `xDim × batchSize`
grid (`SystolicArray.scala:54`): weights are **broadcast** to all `batchSize` rows
(`SystolicArray.scala:83`), each row has its own activation (`actBram = SyncReadMem(maxN,
Vec(batchSize, aWidth))`, `Core.scala:121`) and its own accumulators; the drain engine
already iterates `drainBatchCtr` over `batchSize` (`Core.scala`), and `outBram` depth is
`batchSize × numColTilesMax × outSubBeats`.

- **Verified in sim at the array level:** `SystolicArraySpec.scala:174` runs `yDim=2`
  ("varying activations per cycle compute true batched matvec"). `Tiny` preset is
  `batchSize=2` (`CoreConfig.scala:104`).
- **Gap:** the `Core` FSM wrapper (B-deep LOAD, B-tile drain, outBram batch addressing) is
  only tested at `batchSize=1` (`BenchMirrorSpec.scala:44`). This is the area to harden.

So the bulk of the work is **plumbing an existing synthesis-time HW parameter through the
software stack** + hardening the Core FSM batch path + characterizing timing/resources per B.
`batchSize` is baked into the bitstream (the array is physically B rows), so "tweak to find
the sweet spot" = build a few bitstreams (B = 2/4/8) and benchmark each.

## Phases

### Phase 0 — Harden the HW batch path (simulation only, no board) — ✅ DONE 2026-06-24
- Parameterized `BenchMirrorSpecBase` with `batchSize` (`BenchMirrorSpec.scala`) and added a
  batched flow (`packActBeatBatch` / `refMatmulBatch` / `runBenchFlowBatch`) + two tests
  (batched-matvec vs per-batch ref; per-row no-cross-contamination), guarded `batchSize>1`.
  Single-vector tests guarded `batchSize==1`. New specs: `BenchMirrorBatch2Spec`,
  `BenchMirrorBatch4Spec` (small xDim=4), `BenchMirror370M_A16_B2Spec` (deployed a16 geom).
  **Result:** B=2 and B=4 bit-correct at small geom AND at the real a16 geometry (xDim=32,
  4-way port join, signed, 256-deep drain). All B=1 subclasses (Bench/16/32/64/A16) still
  green — the refactor is self-validating. CoreConfigSpec green (17).
- Added presets `K26_MMFree370M_A16_B{2,4,8}` = `K26_MMFree370M_A16.copy(batchSize=B)` +
  presets-map entries (`CoreConfig.scala`). B ≤ 8 keeps the 512-b LOAD beat → 4 ports;
  topology identical to the B=1 a16 bitstream.
- **Drain-hiding analysis (the key finding):** at `outBeatLanes=1`, drain =
  `B × outLanesPerTile` = `B×256` cycles/col-tile, overlapped with the next tile's `N`
  compute cycles → fully hidden iff **`B×256 ≤ N`**. The 370M model is mostly **N=1024**
  (i/f/g/o, gate), so **B=4 is the hide ceiling at outBeatLanes=1** (B=4 → drain 1024 =
  compute; B=8 → drain 2048 > 1024, ~half the batch benefit wasted). down_proj (N=2816)
  hides to B=11. Plus a non-hideable last-tile drain tail of `B×256` cyc (~B×1.0 µs @250 MHz)
  per projection, amortized over B vectors. **Implication for Phase 2:** build/characterize
  **B=2 and B=4 at outBeatLanes=1**; to make **B=8** productive on N=1024 projections, bump
  `outBeatLanes=2` (drain → `B×128`) and re-check 250 MHz timing (intersects
  `a16_timing_outbram_fold` — widening the drain was the original timing threat).

### Phase 1 — Software plumbing (host-buildable, no board) — ✅ DONE 2026-06-24
- **Geometry:** added `uint32_t batch` to `mmfree_geom_t`; `mmfree_geom_init` seeds
  `batch=1` (signature unchanged — port math is xDim-derived and correct for batch ≤ xDim,
  which the 4-port wall enforces anyway; documented in the header).
- **libmmfree:** added `mmfree_bitlinear_batch(h, proj_id, x[b*N], acc[b*M], b)`
  (`mmfree_lib.c`); `mmfree_bitlinear` is now the `b=1` wrapper. Packs row r at bytes
  [r*abytes,(r+1)*abytes) of each LOAD beat (rows [b,B) zero-padded), LOAD N beats, COMPUTE,
  STORE B*n_outputs, readback batch-major `acc[i*M+m] = ob[i*n_outputs+m]`. Fast path
  (cached+int16) writes B int16/beat; fallback uses new pure helper
  `mmfree_pack_act_beat_batch`. `mmfree_register` validates `batch*n_outputs` against the out
  buf. Added `batch` to `mmfree_lib_cfg_t`; `mmfree_lib_open` sets `geom.batch` + guards
  `batch ≤ xDim`. Instruction format unchanged (COMPUTE is batch-agnostic; LOAD count=N,
  STORE count=B*n_outputs).
- **Runner:** `main_fpga.cpp` reads `MMFREE_BATCH`, sets `cfg.batch`, scales `out_bytes` ×B,
  prints batch.
- **ctypes:** mirrored `batch` in `_Cfg` + `LibConfig` + `open()`; added `bitlinear_batch()`.
- **Host verification (all green):** `make test-lib` (new `test_pack_act_batch` incl. a
  B=1==single-vector byte check) passes; `libmmfree.so` builds clean; ctypes ABI sizeof
  matches (200 B); `main_fpga.cpp` syntax-checks; 27 `mmfree_pack` tests pass.
- **Deferred to Phase 2:** the full `bench.c` batch run-loop (it drives the low-level ops
  directly; better exercised on-board). The gated correctness vehicle is the runner's
  `--backend both` CompareBackend (Phase 3) + a batched bench in Phase 2.

### Phase 2 — Build & bring up batched bitstreams (board) — CODE READY 2026-06-24
**Prepared (host-verified):**
- `build_all.sh`: `k26_mmfree370m_a16_b{2,4,8}` map to the a16 geometry (XDIM=32/AWIDTH=16 →
  4×128-b ports, 250 MHz); a `BATCH` parse from the `_bN` suffix scales `UDMABUF_OUT_SZ` ×B
  (128 KiB→256/512 KiB/1 MiB); ACT/WT unchanged. `bash -n` + arithmetic checked.
- `bench.c` batch mode (the on-board correctness + GOPS vehicle): `MMFREE_BATCH` env sets
  `geom.batch` (validated ≤ xDim); `run_shape` generates B activation vectors, packs B/beat
  (`write_act_beat_batch`), STOREs `B×n_outputs`, verifies `B×M` batch-major vs per-vector
  `ref_matmul`, and reports **GOPS ×B** + **tok/s ×B** (same weight stream, B tokens). Builds
  clean; the existing `k26_mmfree370m_a16` bench binary covers all B via env.

**To run on the board, per B ∈ {2,4} (then 8 as a stretch):**
1. Build bitstream on the Vivado host: `PRESET=k26_mmfree370m_a16_b2 ./scripts/build_all.sh`.
   **Record WNS @250 MHz** (timing risk: broadcast-weight fanout to B×xDim PEs — if it fails,
   pipeline the broadcast) and **LUT/FF/BRAM/DSP**.
2. Deploy the per-config overlay to the KRIA and load it: `scp -r
   transfer/<preset> kria:~/transfer/ && sudo ~/transfer/<preset>/deploy_kria.sh`
   (each preset builds into its own `transfer/<preset>/`, so b2/b4/b6 coexist and
   you can switch on-board without rebuilding).
3. Build bench on board: `cd software && make BENCH_PRESET=k26_mmfree370m_a16`.
4. **Gate + characterize:** `sudo MMFREE_BATCH=2 MMFREE_SHAPES=370m <csr> <stream> /dev/uio4
   /dev/udmabuf-act /dev/udmabuf-wt /dev/udmabuf-out` → **errs=0** required; read the GOPS
   column and the per-token rollup. Compare B=1 (existing a16 bitstream) vs B=2/4.
   - **⚠ `MMFREE_BATCH` MUST equal the loaded bitstream's batchSize.** B is baked into the
     bitstream (the array is physically B rows and always drains B×n_outputs); a mismatch
     desyncs the m_axis stream / hangs. The bench startup print shows `batch=` — eyeball it
     against the loaded preset.
- **Expected (from the Phase 0 drain analysis, N=1024 shapes):** B=2 ≈ 2×, B=4 ≈ 4× (drain
  1024 = compute, just hidden), B=8 ≈ 4–5× (drain 2048 > compute → drain-bound) GOPS;
  down_proj (N=2816) scales further. That taper IS the sweet spot — likely B=4 at
  outBeatLanes=1, or revisit outBeatLanes=2 for B=8.

### Phase 3 — Model integration: prefill batching + serving scaffold (board, gated)
- **Prefill:** in `bitlinear.cpp`, when a backend is set and `rows>1`, process rows in chunks
  of B — RMSNorm+quant B rows, one batched engine call, dequant B rows → prefill becomes
  `⌈T/B⌉` calls. Add `matmul_batch(...)` to `TernaryBackend` with a default that loops the
  scalar `matmul` (so `CpuBackend` is unchanged); `FpgaBackend` overrides → `mmfree_bitlinear_batch`.
- **Serving:** a new runner mode (e.g. `--serve B`) that packs B concurrent sequences' decode
  steps into one batched call (decode is sequential per-stream, so this batches *across*
  streams). Per-stream `rstate` already exists; needs a B-wide scheduler in the runner.
- **Gate:** extend `CompareBackend` to compare `B×M`; `--backend both` prefill must stay
  bit-exact CPU vs engine.

### Phase 4 — Larger models (1B / 2.5B)
- **Dimensions:** confirm 1B/2.5B `hidden_size`/`intermediate_size` from their blob configs;
  if `intermediate > 4096`, bump `maxN` (preset param → actBram depth, accWidth). vocab/maxM
  likely unchanged.
- **Weights don't fit resident** (1B ≈ 250 MB, 2.5B ≈ 625 MB ternary > CMA): refactor the
  `fpga_runner` from all-resident to **per-projection weight streaming** (stream each
  projection's slice from a large mmap'd region/file per COMPUTE, as `bench` does), or a
  reserved-memory region addressed directly by MM2S. This is the main runner change.
- Pack 1B/2.5B blobs (`mmfree_pack` is dimension-agnostic). Gate one layer, then end-to-end.

### Phase 2 timing fix (Tier 2) — RTL done & sim-verified 2026-06-24
First b2 build failed timing @250 MHz: **WNS −0.482** on `outBram → outDataReg` (7 cascaded
RAMB36E2 — batch deepened outBram to 64000 rows) and a secondary **−0.390** on
`inQ deq-ptr → array accumReg` (the array doubling to yDim=2). Fixes in `Core.scala`:
- **Generalized the store read into an `outReadLat`-stage pipeline** (`outReadLat = 4` for
  batched, 2 for B=1). `rdPtr` issues addresses leading the emitted beat by `outReadLat`;
  `primeCtr` fills the pipe; `outPipe` is the register chain; stall freezes the whole pipe.
  The extra register stages let Vivado pipeline the deep BRAM/URAM read cascade. Store
  latency is hidden (drained under the next op). Replaces the old 2-cycle `outDataReg`.
- **1-deep registered slice (`armSlice`) between `inQ` and the array, batched cores only**
  (`pipe=true` keeps throughput; flushed with `inQ`). Terminates the inQ distributed-RAM
  read at a register so the PE accumulate starts fresh. B=1 keeps the direct path.
- **Verified:** CoreSpec 11/11; all BenchMirror specs green — B=1 (Bench/16/32/64/a16, L=2)
  and batch (B2/B4/a16-B2, L=4 + slice). Functionally exact; timing is for synth to confirm.
- **URAM (`ram_style=ultra`) deferred to B≥4.** B=2 fits BRAM (58% util) and should close on
  BRAM with the pipeline stages alone. B=4/8 exhausts BRAM (outBram ~57 of 84 tiles), so
  they need outBram in URAM (1.5% used) — the `outReadLat=4` stages already satisfy URAM's
  cascade-pipelining need; only the `ram_style=ultra` attribute remains, which needs a synth
  round to confirm inference (Chisel/CIRCT has no clean API → likely a generated-SV annotation
  or Vivado synth attribute). Wire it when scaling past B=2.

**2nd b2 build (WNS −0.389):** the read-pipeline fixed the −0.482 path, but WNS only moved to
−0.389 and the critical path is **still the outBram read** (`RAMB36E2=7`). Root cause: URAM was
**not** inferred (CIRCT emits the memory as a plain reg array, no `ram_style`), and the
`outPipe` registers were **SRL-optimized** (`_srl2_srlopt`) — bolted on *after* the cascade,
not pipelining it (external regs can't cross the memory-module boundary into the cascade).

**URAM fix — `OutMemUltra` BlackBox (clean Chisel, not SV post-processing):** CIRCT can't
attach `ram_style` to a `SyncReadMem` (`firrtl.AttributeAnnotation` only targets
modules/wires/regs — verified by firtool error on `~|Core>outBram`). So batched cores emit
the output memory as a parameterized `BlackBox` (`src/main/scala/control/OutMem.scala`,
`HasBlackBoxInline`) with `(* ram_style = "ultra" *)` AND an **internal** `outReadLat`-stage
read pipeline — the pipeline regs live inside the module so they pack into the URAM cascade
(external regs SRL-optimize and never pipeline it — that was the 2nd-build failure). Core
selects it via `outRamStyle = (batchSize>1) ? "ultra" : "block"`; B=1 keeps `SyncReadMem`
untouched. The store FSM was refactored to a uniform `outRdAddr`/`outRdEn`/`outRdData`
(latency `outReadLat`) + write-port interface so both backends drop in.
- **Verified (sim):** URAM module emits with the attribute + lands in `filelist.f`; Verilator
  simulates the inline SV; all specs green — CoreSpec 11/11, CoreConfigSpec 17/17, batch
  B2/B4/a16-B2 (blackbox path) and B=1 Bench/16/32/64/a16 (SyncReadMem path). No sed anywhere.

**3rd build (REPACKAGE=1): URAM inferred (84→34 RAMB36, 17 URAM288 — freed ~50 BRAMs as
predicted), but WNS −0.405.** Two residual issues: (1) the URAM read is a **7-deep cascade**
(`URAM288=7` — 64000 rows ≈ 16 URAMs chained long), and (2) `rpipe` got **SRL-optimized
again** (`rpipe_reg[2]_srl2_srlopt`) — even inside the BlackBox, a plain shift register infers
SRLs that sit *after* the cascade and pipeline nothing. A secondary path (−0.367) is the
**write-address** cascade: `drainBatchCtr → (×numColTiles arith) → URAM CAS_IN_ADDR` (13 levels,
URAM288=7).

**Fix (OutMem.scala BlackBox attributes):** `cascade_height = 4` on the `mem` array (bounds the
URAM chain to 4 → 4 parallel chains + output mux, shortening BOTH the read-data and
write-address cascades from 7) + `shreg_extract = "no"` on `rpipe` (keeps the read-pipeline regs
as flops so they pack into the URAM cascade pipeline instead of SRL'ing). `cascadeHeight` is an
`OutMemUltra` param (default 4). Verified: attributes emit; batch sim green.

**TIMING CLOSED 2026-06-24:** `k26_mmfree370m_a16_b2` builds @250 MHz with no timing violation.
Final B=2 fix stack: `outReadLat=4` store read-pipeline + `armSlice` (inQ→array) + URAM BlackBox
(`ram_style=ultra`, `cascade_height=4`, `shreg_extract=no` on rpipe). `cascade_height` shortened
both the read-data and write-address cascades; `shreg_extract=no` let the pipeline regs pack
into the URAM cascade.

**Board bring-up (now the live step):**
1. Deploy the per-config overlay: `scp -r transfer/<preset> kria:~/transfer/ &&
   sudo ~/transfer/<preset>/deploy_kria.sh` (builds land in `transfer/<preset>/`;
   configs coexist, switch on-board without rebuilding).
2. `cd software && make BENCH_PRESET=k26_mmfree370m_a16` (a16 bench binary; batch via env).
3. **Gate + characterize:** `sudo MMFREE_BATCH=2 MMFREE_SHAPES=370m <csr> <stream> /dev/uio4
   /dev/udmabuf-act /dev/udmabuf-wt /dev/udmabuf-out` → require **errs=0**, read the GOPS
   column (expect ~2× B=1) and the per-token rollup (tok/s ×B). ⚠ `MMFREE_BATCH=2` MUST match
   the bitstream (the print shows `batch=`). Then move to **B=4** (URAM headroom: 17/64 used).

### Phase 2.5 — outBeatLanes=4 (store-bandwidth fix) — RTL+sim done 2026-06-24
Board B=4 showed the STORE is the batch bottleneck: it doesn't amortize (B·M outputs) and was
m_axis-width-bound at ~1 GB/s (32-bit S2MM). The batched a16 presets (`_b{2,4,8}`) now carry
`outBeatLanes=4` (128-bit m_axis = the HP-port width → ~4 GB/s store). Triple win: also shrinks
`outBram` depth 128000→32000 (4× shallower URAM cascade → easier timing — `OutMemUltra_32000x112`)
and `outSubBeats` 256→64 (drain hides through B=8). **Software unchanged** — runtime/bench work
in lanes/bytes and the udmabuf layout is identical (lane m at byte m·4); `out` udmabuf size
unchanged. Changes: `CoreConfig` `outBeatLanes=4`; `bd.tcl` `s2mm_width` (`c_s2mm_tdata_width`
+ `c_m_axi_s2mm_data_width` on `axi_dma_0`); `build_all.sh` `S2MM_WIDTH=128` for `_bN`;
`BenchMirrorSpec` `outBeatLanes` param + `*_Obl4` specs. Sim green; B=1 intact.
**Next board step:** `REPACKAGE=1 PRESET=k26_mmfree370m_a16_b4 ./scripts/build_all.sh` → expect
easier timing (shallower URAM) + `st_GBps` ~4× → total tok/s closer to ~2×/doubling. Same bench
command as before. Then Phase 3.

## Risks / open items
- **Timing at 250 MHz** with B-broadcast fanout (Phase 2). Mitigate by pipelining broadcast.
- **Drain hiding at `outBeatLanes=1`** (Phase 0): `B×256` drain vs `N` compute; taper on
  small-N projections at high B. May need `outBeatLanes>1` for batched presets.
- **CMA / resident weights** for 1B/2.5B (Phase 4) → streaming-weights runner refactor.
- **`maxN`** may need raising for 2.5B intermediate dim (confirm from blob config).
- Decode single-stream latency is NOT improved by batching (sequential); serving/spec-decode
  is the only way batch helps decode.

## Validation discipline
Each phase ends in a validated state, mirroring the project's gate-driven style: sim green
(P0), host build + packer tests (P1), board `bench` gate errs=0 + perf table per B (P2),
`--backend both` model gate bit-exact (P3), larger-model layer + e2e gate (P4).
