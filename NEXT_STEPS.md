# Where things stand — 2026-08-07

## 0. THE DATA SET IS COMPLETE — 2026-08-07. Tomorrow is analysis, not collection.

Both arms ran today. `reports/final/dataset.csv` is the merged set: **3 platforms-arms x 3
models x b=1..8, five passes each on the board and the Xeon, POWER=1 on every row.** The
aggregator's completeness check passes in full — no cell absent, no cell missing power, every
FPGA row `streams_ok=1` — and **exactly one cell is flagged**, which is item 2 below.

⚠️ `reports/` is gitignored. `dataset.csv` and every raw CSV live only on this build server
(`~/results/final/`, `reports/host_cpu/final/`) and the board. The tables in §3 are the
tracked copy; if the box is lost, they are what survives.

### ⏰ 1. BUILD THE TRUE SYSTOLIC ARRAY AND BENCH IT — planned 2026-08-07, execute next

**Why.** What ships is NOT a systolic array. `SystolicArray.scala` instantiates a 2D grid of
PEs, but no PE's input is ever driven by another PE's output — every PE reads the boundary
pipeline and writes straight to the output port (the only cross-references in the file are
`arr(0)(0).output.valid` and `arr(0)(doneCol).output.valid`, both completion signals). It is
an **output-stationary broadcast MAC array**: one activation broadcasts to 32 columns, one
weight beat broadcasts to 8 rows, each PE accumulates independently. The name is historical —
the ORIGINAL design was systolic (weights down a per-row delay chain, bottom-row-only output)
and was replaced when yDim became the batch dimension.

That broadcast is the whole timing story of this project: one activation net drives
**256 PE inputs**, and beating that fanout is what `actMaxFanout`, `colBankSize=4` and the
maxN=8192 two-path fight were all about. So the experiment is: does nearest-neighbour
dataflow buy back the slack, and is there enough of it to raise the clock rung or grow the
array? **Do not expect tok/s to move** — decode is weight-bandwidth-bound and the 200→250
rung was measured at only ~5%. Timing and routing headroom is the result; throughput is the
control.

**The register arithmetic says build it** (shipped geometry, xDim=32 yDim=8 aWidth=16):

    dataflow                 pipeline regs   max fanout of one activation net
    shipped  colBankSize=4      5 120 bits       32 PE inputs
    control  colBankSize=1     20 480 bits        8
    true systolic              16 576 bits        1     (8192 pass-through + 8384 skew)

True systolic is CHEAPER in registers than the existing mechanism taken to its limit, and
gets fanout to 1. That is the case for spending the build.

**Phase 0 — the free control, do this FIRST (~15 min, no new RTL).** `colBankSize=1` is
already a zero-RTL approximation: it stages every column, cutting fanout 32→8. OOC-synth the
shipped 2.7B geometry at colBankSize=1 and compare WNS against the shipped colBankSize=4.
If that alone recovers most of the slack, the true array's marginal value is small and this
whole item shrinks to a footnote. Cheap, and it can only inform the decision:

    rm -rf generated/mmfree_2_7b_cb1 && ./mill matmulfree_KRIA.runMain control.EmitCore mmfree_2_7b_cb1 generated/mmfree_2_7b_cb1
    vivado -mode batch -source scripts/ooc_synth.tcl -tclargs mmfree_2_7b_cb1

**Phase 1 — RTL.** New module, do NOT modify `SystolicArray` (the shipped SV must stay
byte-identical or `clone_preset_hw.sh` refuses every legitimate clone). Same IO bundle, same
output-stationary semantics, all yDim rows exposed, so it is a drop-in for `Core` and the
existing `SystolicArrayModel.compute` reference model applies unchanged.

* per PE: `actOut := RegNext(actIn)` passed to PE(r, c+1); `wtOut := RegNext(wtIn)` passed to
  PE(r+1, c). Nothing else crosses.
* input skew, triangular: activation for row r delayed r cycles; weight for column c delayed
  c cycles. Derivation — act[r][t] injected at PE(r,0) at t+r reaches PE(r,c) at t+r+c;
  w[t][c] injected at PE(0,c) at t+c reaches PE(r,c) at t+c+r. They meet. Any pair of skews
  differing by a constant works; (r, c) is the minimal one.
* completion: the deepest PE is (yDim-1, xDim-1), valid at **N + xDim + yDim - 2** = N+38.
  Reuse the `draining_state` pattern colBankSize already added — accumulators hold, so no
  output de-skew buffer is needed.

**Phase 2 — equivalence, the test that matters.** It must be bit-identical to the broadcast
array, not merely close: same math, different dataflow. Extend `SystolicArraySpec` with the
`runAndCheckBanked` pattern at `maxSkew = xDim + yDim` — that helper already polls for a
late `output.valid`, which is exactly the systolic drain. Reuse `SystolicArrayModel.compute`
untouched; if it needs changing, the dataflow is wrong.

**Phase 3 — config.** Add `trueSystolic: Boolean` to `CoreConfig`, and a candidate preset
`mmfree_2_7b_sys` = `MMFree_2_7B.copy(trueSystolic = true)`. ⚠️ **`CoreConfigSpec` will fail**
— the test "the buildable registry is exactly the deployable set while no candidate is open"
asserts `candidates mustBe empty` (added 2026-08-07 when B6 retired). Relax it to the
disjoint/reachable half, which is what it was before, and keep the empty check for later.
Build 2.7B, not 370M: maxN=8192 is the geometry that fought 250 MHz, so it is the real test.

**Phase 4 — bitstream (~50 min).** `rm -rf generated/mmfree_2_7b_sys` first (EmitCore does
not clean, and stale .sv breaks every hash comparison). ⚠️ The shipped 2.7B closes at
**+0.002 ns** — the thinnest margin anything here has shipped with — so a new array structure
missing 250 is a likely outcome, not a failure of the idea. If it misses, rebuild BOTH arms
at the 200 rung and compare there; a 250-vs-200 comparison would measure the clock, not the
dataflow.

**Phase 5 — board A/B, one session, both arms.** Do NOT compare against yesterday's numbers:
isolated cells move 6-9% between sessions ([[xeon-baseline-facts]]), so the control has to be
re-measured in the same session as the variant.

    make model PRESET=mmfree_2_7b_sys && make deploy
    # on the board, for EACH of mmfree_2_7b and mmfree_2_7b_sys:
    sudo make deploy-hw PRESET=$p && make gate PRESET=$p && sudo make streams PRESET=$p
    ./bench_batch.sh PRESET=$p TAG=sysab GEN=64 REPS=3 WARMUP=1 POWER=1

POWER=1 on both arms, `TAG=` on both so neither clobbers the final set. The gate is the
correctness proof — a wrong dataflow produces plausible numbers, not a crash — and `streams`
matters more than usual here, because skew is exactly the mechanism that could mis-order rows.

**What to read.** `bl_compute_us_call` is the phase that should move; `decode_tok_s` probably
will not. If COMPUTE improves and decode does not, that is the bandwidth wall confirmed, and
it is a publishable result in its own right: the array was never the bottleneck.

### ⏰ 2. THE ONE FLAGGED CELL — `kria/1.3B/1p6/b7`, 13.97% spread. Diagnosed, not fixed.

It is the 1.3B host-`pack` bimodality again, and the evidence is unambiguous. Five passes,
decode against the pack cost that produced it:

    1.3B b=7   59.51   68.47   59.06   68.57   68.10  tok/s
       pack   215.3   140.8   218.1   138.2   142.2  us/call     <- two clean modes
    2.7B b=7   42.26   42.49   42.50   42.38   42.24  tok/s      <- SAME bitstream
       pack   172.5   170.3   169.3   171.7   168.1  us/call     <- dead stable

Slow mode = pack ~216 us, fast mode = ~140 us, and decode tracks it exactly. 2.7B runs the
identical bitstream and does not move, so this is host-side and specific to 1.3B's shapes —
the standing hypothesis is where the udmabuf-act allocation lands per process changing the
strided-scatter cost. Three of five passes landed fast, so the median sits in the fast mode.

**Two things this breaks, and they are the real work for tomorrow:**

1. **b=8 is worse than b=7 for 1.3B** (62.42 vs 68.10), because b=8 landed in the SLOW mode
   in four of five passes (pack 287/277/292/279/220 us). So 1.3B's best operating point is
   b=7, not b=8, and its ladder is non-monotonic at the top — for a host-software reason, not
   an engine one.
2. **1.3B's b=8 number does not reproduce across sessions.** The 2026-08-06 B8 set read
   **71.53** for exactly this cell; today it reads **62.42**, a 13% shift, because that
   session drew mostly fast-mode passes and this one drew mostly slow. Neither is wrong; the
   cell is bimodal and the mode mixture is what varies. Until that is understood, no single
   1.3B top-of-ladder number is quotable — which is the same conclusion as yesterday, now
   with the mechanism localized to one measurable phase.

Start here: `bl_pack_us_call` is already in every CSV, so the mode is visible per pass without
re-running anything. [[pack-bimodal-1p3b-b6]] has the history (it was b=6 last time — the
bimodality MOVES with the batch, which is itself a clue).

### ⏰ 3. STILL UNDECIDED: how to quote the FPGA-vs-Xeon ratio at b=8

The Xeon's batch curve is non-monotonic and reproducible (§3), so at b=8 it sits in a trough
for 370M and 1.3B. Equal-batch and best-point-vs-best-point are both defensible and they are
far apart — 370M reads 1.20x one way and 1.51x the other. The method already gives the CPU its
best thread count, which argues for best-point; equal-batch is the apples-to-apples on
identical work. **Pick one deliberately and say which in the report.** I can produce either
from the existing data; nothing needs re-measuring.

### 4. What is clean and quotable right now

Everything except 1.3B at b=7/b=8. Specifically: all three gates PASS bit-exact, all three
`make streams` row-independence checks OK — **including 2.7B and including B=8, the widest
drain this design has ever done, which was the last outstanding per-bitstream check** — and
every Xeon cell is stable to <=1.7% jackknife with zero flags.

**KRIA, engine + A53 host, 1.6-bit weights. Five-pass medians, POWER=1.**

    preset      b=1    b=2    b=3    b=4    b=5    b=6    b=7    b=8   W@b8  tok/J@b8
    mmfree_370m 41.25  67.67  84.35 108.13 113.03 123.99 132.78 138.19  5.56   24.84
    mmfree_1_3b 20.37  35.13  46.17  53.76  58.25  62.08  68.10† 62.42† 6.07   10.29
    mmfree_2_7b 11.70  20.42  26.88  33.02  33.99  38.40  42.38  44.45  6.35    7.00

    † the flagged pair — see §1. 1.3B's best point is b=7, and neither cell is quotable yet.

**Xeon W-2155, 2-bit (the fastest CPU encoding), 20 threads. Five-pass medians.**

    model   b=1    b=2    b=3    b=4    b=5    b=6    b=7    b=8    W@b8
    370M   172.1  169.4  166.6  142.1  156.8  184.9  208.2  164.6  100.31
    1.3B    89.5   92.5   93.7   87.6   80.7   97.6   96.4   83.9  112.99
    2.7B    47.8   43.0   43.6   48.9   45.6   49.7   44.6   49.8  121.44

    Non-monotonic, and reproducibly so: 370M peaks at b=7 and drops 21% at b=8; 2.7B barely
    scales with batch at all. This is what makes §2 a real decision rather than a formality.

**Perf/W at b=8, equal batch, 2-bit on the CPU side.** ⚠️ The 1.3B row inherits §2's caveat.

    model  KRIA t/s  KRIA W  tok/J | Xeon t/s  Xeon W  tok/J | advantage
    370M     138.19    5.56  24.84 |   164.62  100.31  1.641 |    15.1x
    1.3B      62.42    6.07  10.29 |    83.92  112.99  0.743 |    13.9x
    2.7B      44.45    6.35   7.00 |    49.76  121.44  0.410 |    17.1x

    Against the old published table (9.0/10.0/13.0x) the advantage grew because the KRIA side
    gained ~30% from B8 + threaded readback while the Xeon did not move. Best-point instead of
    equal-batch would change the throughput column, not this one materially.

**The on-board A53 control — what the engine is actually worth on this SoC**, b=8, 2-bit:

    model   A53 tok/s   A53 W   engine speedup
    370M         9.37    4.37       14.7x
    1.3B         2.51    4.45       24.8x
    2.7B         1.25    4.46       35.6x

    The speedup grows with model size, which is the offload argument in one line.

### 5. The Xeon arm — how it was run, 385 min, zero flagged cells

`reports/host_cpu/final/`, 15 groups x 24 rows = 360 rows, b=1..8, 5 passes, 20 threads,
AVX-512 confirmed by probe, RAPL on every row. **Zero flagged cells** against five in the set
it replaces; max jackknife median-shift 1.66% (limit 3), no row missing power.

Two results that change how the table gets built — both in [[xeon-baseline-facts]]:

* **The Xeon's batch curve is not monotonic, and the shape is real** — see the table in §4
  and the open decision in §3. Every cell is stable to <=1.7% and the shape reproduces to ~2%
  against an independent set, so more passes will not smooth it.
* **Isolated cells do not reproduce across sessions to better than ~7%**, even when five
  fresh processes agree to 0.2% within a session.

The archived b=1..6 five-pass set is `reports/host_cpu/final_b1to6_5pass_20260807/`. Its
370M/2bit/b2 and 1.3B/2bit/b5 cells were the flagged ones and are now known to have been
contended: today's quiet-box values read +11.3% and +8.7% against them.

### 5b. How the Xeon sweep was set up

The defaults are now **b=1..8, five passes** (was 1..6, three). The ladder had to widen because
the board ships B8: without b=7/8 the top of the FPGA ladder has no CPU denominator. That
forces a **full re-run, not a top-up** — the winning thread count is chosen per (model,
encoding) across the whole ladder, so a set with some passes six rungs wide and some eight
would have the choice depend on which pass was wider. The script now refuses to run against
existing output on a different ladder and tells you to archive it (`ARCHIVE_PREV=1` does the
move). The existing `reports/host_cpu/final` is **five passes at b=1..6** — passes 4-5 did
complete on 2026-08-06 — with five cells still flagged UNSTABLE, so it is being replaced, not
extended.

Preconditions, all of which have bitten before: RAPL readable (above), VS Code closed (its
file-search `rg` held 11 cores for 50 minutes once), nobody — including any agent — building
on the box, and the runner built deliberately AVX-512. **The AVX-512 build is done** (rebuilt
clean 2026-08-07, `MMFREE_SIMD=X86_512`), and the script probes the binary rather than counting
zmm, plus re-fingerprints it before every run-group.

**Decided 2026-08-07: `THREAD_SET="20"`, not the {10,20} sweep** — ~6 h instead of ~12. On the
five-pass b=1..6 data, 20 threads wins **every** (model, encoding) cell, by +1.9% (1.3B int8)
to +20.3% (1.3B 1.6-bit); on 2-bit, the encoding actually quoted as the denominator, the
margins are +5.5 / +13.2 / +7.1%. So the thread sweep was re-measuring a settled question.

    model  fmt    sum t10   sum t20   t20 vs t10       (medians summed over the b=1..6 ladder)
    370M   2bit     930.7     982.1        +5.5%
    1.3B   2bit     478.1     541.2       +13.2%
    2.7B   2bit     242.3     259.5        +7.1%
    1.3B   int8     294.2     299.9        +1.9%   <- narrowest margin anywhere

⚠️ **This carries the winner over from the narrower ladder**, which is the one thing the full
sweep would have proven rather than assumed. It is a reasonable carry — the reason 20 wins is
that these are memory-bound and SMT hides DRAM latency, which gets *more* true at b=7/8, not
less — but it is an assumption. To retire it cheaply later, run one pass at t10 restricted to
the new rungs: `PASSES=1 THREAD_SET=10 BATCHES="7 8" ./scripts/final_sweep_xeon.sh` and check
it does not beat t20 there.

⚠️ **Corrects an earlier note in this file.** It said the Xeon rows "all land on 2-bit, 10
threads" — that came from the 3-pass `xeon_best.csv` and is wrong on the 5-pass data, where 20
wins everywhere. Any ratio quoted off the 10-thread rows understated the Xeon.

### 6. ✅ Re-aggregated — and the ceiling is per-cell now

    ./scripts/final_dataset.py --board ~/results/final --xeon reports/host_cpu/final

Run 2026-08-07 against both finished arms: completeness passes in full, one cell flagged (§1).
Re-run it any time — it is pure aggregation over the raw CSVs and takes seconds.

`want_batches` is no longer hardcoded. The expected ladder is read per model from the sweep
CSV's new `hw_batch_max` column (the bitstream's compile-time batchSize, handed through as
`MMFREE_HW_BATCH` so `BATCH=b` cannot shadow it), and it refuses to GUESS: a CSV without that
column gets no ladder and says so, rather than inferring one from the batches present — which
is how a half-finished sweep used to report CLEAN. Every arm for a model must cover the FPGA's
ladder, so a CPU arm that stops at b=6 shows up as `missing b=[7, 8]` instead of passing.

⚠️ CSVs written before 2026-08-07 lack `hw_batch_max`, which includes everything in
`~/results/final_b8/` and `~/results/final_b6_serial_readback/`. Aggregate those with
`--max-batch 8` / `--max-batch 6`.

### 7. Still open, not blocking

* **The 1.3B pass-3 anomaly** (2026-08-06: b=6 read 34.23 tok/s with a NORMAL 130 µs pack).
  DID NOT REPRODUCE on 2026-08-07 — 1.3B b=6 is now stable across five passes (62.02-62.53,
  pack 123-128 µs). The instability moved to b=7/b=8 and is fully pack-explained there, so
  the "normal pack yet slow decode" case is currently unwitnessed. If it returns, that is a
  different fault from §1 and the phase CSV is the place to look.
* ~~**`make streams` on the 2.7B geometry.**~~ ✅ CLOSED 2026-08-07: all three presets passed
  at B=8 — `OK (every stream == its OWN single-stream reference)` — which is the first time
  row independence has been shown on the widest drain, and on the maxN=8192 geometry at all.

---

## 0b. Carried over from the 2026-08-06 queue — the parts still worth knowing

Everything that queue listed is either done (B8 promotion + bundle assembly, the aggregator
ceiling, the five-pass board passes, the distinct-prompt stream check) or restated in §0. What
survives is the evidence behind the preconditions:

* **Contention on the build server corrupts the Xeon arm, and it looks like data.** Measured:
  `2.7B/t20_p5` 2-bit b=6 read **31.82** tok/s with a compile running and **48.64** on the same
  binary with the box quiet. `t20` uses all 20 logical CPUs, so anything else lands directly on
  it — `t10` survived the same window because it had ten spare threads. A stray VS Code
  `rg --files --hidden` held ~11 cores for 50 minutes and caused the other instance.
* **The Xeon's flagged cells are multi-modal, not noisy.** int8 is stable everywhere (±2-6%)
  while the packed encodings swing up to 25% at specific batches, so more passes firm up a
  median without collapsing the modes. Do not expect five passes to make them all clean.
* **A plain `make` in the runner directory produces a 256-bit build** and leaves 167 zmm from
  the un-rebuilt `libmmfreecpu.a`, which is why the old `zmm -eq 0` guard passed it. The guard
  now probes the binary and re-fingerprints it before every run-group.
* **On the board, sweep variables go AFTER `sudo`.** `PASS_START=6 sudo ./final_sweep_board.sh`
  is silently stripped and runs the default passes, overwriting what you meant to extend —
  twice now. `sudo PASS_START=6 ./final_sweep_board.sh` is the working form, and the script
  prints its effective settings so you can check the banner.
---

## 1. The data set

`reports/final/dataset.csv`, keyed by (platform, arm, model, weight_fmt, batch). Headline is
the MEDIAN across passes; `mean_tok_s`, `spread_pct`, `median_shift_pct` (jackknife), `watts`
and `tok_per_joule` alongside. The aggregator refuses to call it clean unless the expected
matrix is complete, every cell has power, all FPGA rows are `streams_ok=1`, and no cell's
median moves >3% when any single pass is dropped.

⚠️ **`reports/final/dataset.csv` is now TODAY'S B8 set** (regenerated 2026-08-07) — the B6
content this section used to describe is gone from that file. The B6 raw CSVs survive at
`~/results/final_b6_serial_readback/`; aggregate them with `--max-batch 6` if the historical
comparison is ever needed. **The rest of this section describes the RETIRED B6 set** and is
kept for the failure modes it documents, not for its numbers.

Complete, for that set: POWER=1 everywhere, all three FPGA gates PASS bit-exact (1160/1160/1544
projection invocations, 0 element diffs) under the shipped preset names.

**Board arms were 5 passes** (2026-08-06). The three KRIA cells that were flagged on three
passes split into two different stories:

* `kria/370M/b5` and `kria/1.3B/b5` were one perturbed pass each (370M: 95.0 / **70.8** /
  95.2 / 95.8 / 95.3). Two more passes settle the median and both clear. Their pack cost is
  NORMAL in the bad pass, so this is the host's OpenMP wave structure losing a core, not the
  engine — see §3.
* `kria/1.3B/b6` is **bimodal**, not noisy — 54.93 / 51.26 / 51.29 / 54.85 / 51.36, two clean
  modes ~7% apart, and the whole gap is host `pack` (129-131 vs 167-170 us/call; engine phases
  do not move). Decided 2026-08-06: take the 5-pass median (51.36) as the number. It is the
  only bimodal cell — 2.7B b=6 pack is 155-158 across all five passes and every other 1.3B
  batch is stable — and it makes 1.3B non-monotonic at the top of its ladder (b=5 52.09 >
  b=6 51.36). Hypothesis, unmeasured: where the udmabuf-act allocation lands per process
  changes the strided-scatter cost at that one geometry. Worth chasing under §"open items" 3,
  since `pack` is already the top software cost.

The Xeon arm reached five passes on 2026-08-06 and still has five cells flagged UNSTABLE
(`1.3B/2bit/b5`, `2.7B/2bit/b4`, `2.7B/2bit/b6`, `370M/1p6/b2`, `370M/2bit/b2`) — modes, not
noise, per §0b. It is being re-run on the wider ladder regardless.

## 2. Method — read before quoting anything

See [[final-dataset-methodology]] in memory for the full rationale. The short version:
POWER=1 on every arm so tok/s and watts come from the same run; passes are separate
PROCESSES, not reps; GEN is per model so every b=1 window is >=2 s; the Xeon MUST be built
`MMFREE_SIMD=X86_512` (AVX2 costs 29% and nothing in the CSV would show it); the Xeon thread
count is swept and chosen per (model, encoding) across the whole ladder so one curve is one
configuration.

**2-bit is the fastest weight encoding on BOTH platforms** — int8 is not, which corrects the
older note. Xeon b=6: 2bit 97.95 vs int8 53.74 at 1.3B, because the AVX-512 ternary kernels
only cover the packed formats. Quote 2-bit as the CPU denominator on both sides.

## 3. Measured results and open items

Three presets, one per released MMfreeLM checkpoint, all **B8** + dspAccum + 1.6-bit packed
weights + 128-bit m_axis at a true 250 MHz. Measured on a KRIA KV260, decode tok/s, POWER=1,
all gates PASS (engine bit-identical to the CPU reference), all `streams_ok=1`.

### B8 — first measurement, 2026-08-06. SUPERSEDED by the 2026-08-07 set in §0.4.

Kept because the 1.3B rows differ between the two sessions and that difference is now the open
question (§0.1): this session read 1.3B b=8 = 71.53, the next read 62.42, and the mode mixture
of the host `pack` is why. The 370M and 2.7B rows agree with 2026-08-07 to ~0.5%.

Five passes, threaded readback (every one of the 120 runs logged `# readback: threaded x4`),
POWER=1, gates PASS on all three including the +0.002 ns 2.7B. In `~/results/final_b8/`.

| preset | b=1 | b=4 | b=6 | b=7 | **b=8** | W @b8 | tok/J |
|---|---|---|---|---|---|---|---|
| `mmfree_370m` | 41.31 | 107.51 | 123.46 | 132.03 | **137.67** | 5.52 | 24.96 |
| `mmfree_1_3b` | 20.38 | 54.05 | 57.89† | 68.51 | **71.53**† | 6.17 | 11.60 |
| `mmfree_2_7b` | 11.73 | 33.12 | 38.63 | 42.66 | **44.58** | 6.39 | 6.97 |

**B8 is free at low batch, which is the result that justifies the promotion.** Its b=6 rows sit
on top of the B6 bitstream's b=6 measured on the same software: 370M 123.46 vs 124.39 (−0.7%),
2.7B 38.63 vs 38.55 (+0.2%). So the extra logic and thinner slack cost nothing below b=8, and
b=8 adds +11.5% (370M) / +15.4% (2.7B). Best-point to best-point against B6: **370M +10.7%,
2.7B +15.6%**. Spreads are 0.2–1.5% on 370M and 0.2–1.0% on 2.7B across all eight batches.

Against the numbers still in the report (103.16 / 34.41, B6 + serial readback), the two changes
of 2026-08-06 together are **+33% on 370M and +30% on 2.7B**.

† 1.3B only. Its b=6 cell reads 62.05 / 53.79 / **34.23** / 57.89 / 62.17 — 48% spread, still
below its own b=5 — and host `pack` (125/204/130/164/126 µs/call) explains every pass EXCEPT
pass 3, which had a normal 130 µs pack and still read 34.23. b=8 shows the milder pack version
(one pass at 268 µs/call dragging 71.5 → 63.5), so its median rests on 4 of 5 good passes.
2.7B runs the SAME bitstream and is rock-solid, so this is host-side and specific to 1.3B's
shapes. Not quotable until pass 3 is diagnosed — see §0.

### B6 — RETIRED 2026-08-07, kept as the historical record

The comparison it existed for is closed (see the row above it), the configs are out of the
registry, and the bundles are archived at `~/results/b6_bitstreams/`. **Medians over five
passes**, from `reports/final/dataset.csv` (2026-08-06). ⚠️ Taken with the SERIAL readback, so
it is 11–20% low against anything measured after that flipped. Do not quote these as current:

| preset | b=1 | b=2 | b=3 | b=4 | b=5 | b=6 | WNS @250 | SOM avg W (b=6) |
|---|---|---|---|---|---|---|---|---|
| `mmfree_370m` | 41.74 | 64.80 | 80.04 | 92.80 | 95.23 | **103.16** | +0.030 | 5.39 |
| `mmfree_1_3b` | 20.45 | 33.25 | 42.77 | 49.20 | 52.09 | **51.36**† | +0.025* | 5.90 |
| `mmfree_2_7b` | 11.75 | 19.38 | 25.08 | 30.17 | 30.93 | **34.41** | +0.025 | 6.22 |

\* 1.3B runs the 2.7B bitstream, so the 2.7B WNS is its timing record.

† The one bimodal cell (§1): five passes read 54.93 / 51.26 / 51.29 / 54.85 / 51.36 and the
slow mode holds three of them, so the median sits there and **1.3B is non-monotonic at the
top of its ladder** (b=5 beats b=6). The three-pass number quoted before this was 54.9, and
the older 2026-08-04 sweep said 55.43 — both were the fast mode with a 2-of-3 majority, not
a different measurement. Quote 51.36 and say b=5/b=6 are within the cell's own spread.

Reproducibility elsewhere is good: 370M and 2.7B agree with the 2026-08-04 sweep to ~0.5%
across the whole ladder. Treat ±1–2% as the session-to-session band and do not read a delta
smaller than that as a result — except at 1.3B b=6, where the modes are 7% apart.

Use **b=4 or b=8, never b=5..7** — the host's vector ops are `omp schedule(static)` over the
batch on 4 A53 cores, so they cost `ceil(b/4)` waves and only b=4 and b=8 fill the waves they
pay for. (On the retired B6 bitstreams the same reasoning made b=4 or b=6 the answer.)

### Perf/W — the headline

Against a Xeon W-2155 running the same model at the same weight format (`reports/host_cpu/`,
RAPL package+DRAM), at b=6:

⚠️ **This table is superseded and must not be published — the replacement is in §0.4.**
Both halves moved: the KRIA column is B6 + serial readback (~30% low against the shipped
configuration) and the Xeon column is a b=1..6 ladder measured on a contended box. Kept only
as the historical record of what was believed before 2026-08-07.

| model | KRIA tok/s | KRIA W | KRIA tok/J | Xeon tok/s | Xeon W | Xeon tok/J | advantage |
|---|---|---|---|---|---|---|---|
| 370M | 103.16 | 5.39 | 19.1 | 209.11 | 98.76 | 2.12 | **9.0x** |
| 1.3B | 51.36 | 5.90 | 8.71 | 97.69 | 111.97 | 0.872 | **10.0x** |
| 2.7B | 34.41 | 6.22 | 5.53 | 49.05 | 115.52 | 0.425 | **13.0x** |

Xeon rows are the b=6 best of `xeon_best.csv` (all three land on 2-bit) and use its `total_w`,
which is the column comparable to the KRIA's SOM rail figure — both include idle. ⚠️ That file
also says 10 threads, which the five-pass aggregation contradicts — see §0. These rows are
understated on the Xeon side as well as the KRIA side. Using its `net_w` instead (idle subtracted on the Xeon side only) would read 7.8x /
9.4x / 11.7x, which is not a like-for-like comparison; pick one and say which.

The Xeon wins raw throughput by 1.4–2.0x; the KRIA wins energy by 8.8–13.2x, and **the
energy advantage grows with model size while the throughput deficit shrinks.** The board
idles ~4.4–4.6 W against 5.4–6.2 W under load, so the *dynamic* cost of inference is only
~1.0–1.7 W.

⚠️ These are not the same kind of measurement. SOM rails cover PS + PL + the SOM's DDR;
RAPL covers CPU package + DRAM and excludes board/PSU losses. Both are "compute silicon
plus its memory", so the comparison is meaningful — label it, do not claim equivalence.

### What each lever was actually worth

Measured, not estimated, at b=6 — worth knowing before spending a build on any of them:

| lever | gain | notes |
|---|---|---|
| threaded readback | **+20.4% (370M), +11.1% (2.7B)** | the largest software lever found; default on since 2026-08-06 |
| B4 → B6 | +11.8% | the big one |
| B6 → B8 | +11.5% (370M), +15.4% (2.7B) at b=8 | and free below b=8; shipped 2026-08-06 |
| 2-bit → 1.6-bit packing | +4.3% at 2.7B | grows with model size, decays with batch — only ~+1.4% at 1.3B b=6 |
| column-tile split (k=4) | +4.4% at b=4 | hides readback under COMPUTE; the ladder is already optimal |
| 200 → 250 MHz | ~+5% | **the least valuable axis** — decode is weight-bandwidth-bound |

---

### Open engineering items

1. **STORE ‖ COMPUTE (RTL), ~+5%.** `m_axis` is a separate physical port from `s_axis`, so
   STORE and COMPUTE can overlap; the only conflict is `outBram` (STORE reads while the
   drain writes), which needs double buffering. Resource-feasible — URAM would go ~25% → 50%
   and it adds no DSP, which is the tight resource. This is the largest remaining item and
   it also retires the per-chunk store tax that caps chunking.

   Explicitly NOT worth doing: `LOAD ‖ COMPUTE` (one physical `s_axis` port carries both
   activations and weights, and LOAD is only 3.9% of engine time), and a deeper instruction
   queue on its own (the Core FSM runs one op to completion regardless, and the CPU programs
   the DMA anyway — that needs scatter-gather, not a queue).

2. **~217 ms/run of unexplained per-chunk cost** at k=4. The balance sheet closes overall,
   but after accounting store (+171 ms) and pack (+5 ms) there is a residual charged to
   compute. Hypothesis is weight-DMA ramp-up — k separate MM2S transfers instead of one long
   one. Unmeasured; instrumenting DMA start → first-beat latency would confirm or kill it,
   and it decides whether item 2 is worth more than the +5% estimate.

3. **Host `pack` is now the top software cost** at 1.1–1.2 s/run at b=6 on the large models,
   larger than STORE and readback combined. It is a strided int16 scatter into udmabuf-act;
   nothing has tried to vectorize or block it. Note that threading readback worked and is the
   same shape of problem (per-row host work on one core while three idle) — but `pack` WRITES
   into a cacheable udmabuf whose lines then have to be flushed, and the one previous attempt
   to restructure it ([[activation-pack-fusion-negative]]) came out 10–14% slower, so this is
   not a free copy of the readback win. Measure the loop standalone first, the way
   `software/bench/bench_readback.c` did.

4. **Readback: what is left after threading.** The loop is now bandwidth-bound, not
   core-bound — one A53 sustains ~4.0 GB/s on it and four reach 8–13, which is the cluster
   ceiling. So more threads buy nothing and the remaining ideas are about moving less data:
   consume the drained values straight from the mapped udmabuf instead of staging them into
   `acc` (needs udmabuf-out double-buffered so the next chunk's drain does not overwrite what
   is being read), and STORE ‖ COMPUTE (item 1), which raises how much readback can hide.
   Both are worth less now than they were this morning: 3.7× is already banked.

---


### B8 — the shipped utilization, both geometries close 250 MHz

    preset         WNS@250    LUT      Reg     CARRY8   BRAM     URAM     DSP
    mmfree_2_7b    +0.002   83.18%   49.76%   25.92%   49.65%   50.00%   100%
    mmfree_370m    +0.011   81.42%   48.30%   25.92%   29.51%   53.13%   100%
    mmfree_1_3b    the 2.7B bitstream (RTL sha256 identical, wt field repatched)

Overturns the "~95% LUT capacity wall" verdict, which was measured on 370M B8 with FABRIC
accumulators before `dspAccum` existed. The 2.7B's +0.002 ns is thin enough that a different
seed could miss — it has NOT been seed-swept, and the 200 rung (~5% slower) is the fallback if
the board ever shows intermittent gate failures. See [[b8-closes-250]].

### b=5 is the least trustworthy operating point, twice over

Already known degenerate (host vector ops cost ceil(b/4) waves on 4 A53 cores, so b=5 pays
b=6's price for five streams — and on B8, b=5..7 all pay two waves while only b=8 fills them).
Now also the least stable to MEASURE: 370M b=5 pass 2 read 70.82 tok/s against 95.04/95.23,
and the phase breakdown puts it entirely host-side — rmsnorm +184%, scan +181%, swishgate
+56%, engine phases only +8-14%. That is the OpenMP wave structure collapsing when a core is
stolen.

**Suspect POWER=1 as the thief.** `xlnx_platformstats` sampling is a process competing for
one of four cores. It costs ~2% on average, but can evidently cost 25% when it lands badly,
and two of the three flagged KRIA cells were b=5. If b=5 matters to you, measure it POWER=0
separately. Otherwise use b=4 or b=8.

### Things that will bite you

- **`sudo` on the board needs a password**, so board sweeps cannot be driven over SSH from
  the build server. Hand over a script and run it there.
- **`reports/` and `transfer/` are gitignored.** Every bitstream and every CSV lives only on
  this build server and the board. Hours of Vivado and all the measurements above.
- **Always pass `TAG=`** when re-running a preset under changed conditions. Without it the
  re-run overwrites the previous `<preset>_batch_sweep.csv`; that is how a whole set of
  250 MHz rows was lost on 2026-08-04.
- **Only compare POWER=1 against POWER=1** — rail sampling costs ~2% decode and has already
  manufactured one phantom regression.
- **A single (arm, batch) cell can go bad without anything else in the row moving, and it
  is repeatable across reps within that run.** On 2026-08-04 the 1.3B 2-bit b=6 cell read
  47.57 tok/s across 3/3 reps (spread 0.12), non-monotonic against its own b=5. A four-pass
  re-run — two invocations under one overlay load, one after a fresh load, plus the packed
  sibling as an in-session control — put it at 53.48–54.56 over 9 reps and the host `pack`
  cost back in family (123–132 µs/call vs the bad run's 204.8, trend ~127). So rep-to-rep
  agreement is NOT evidence a number is real; a fresh process and a fresh overlay load are.
  When one cell is out of family, diff the `bl_*_us_call` columns against a sibling arm
  before believing the headline — the phase that moves tells you whether it is even the
  engine. Here it never was.
- **`plClkMhz` must be a PL0_REF rung** (100/143/167/200/250/300). Off-rung values used to
  snap down silently, so a "230 MHz" build was really 200 and its recorded Fmax was slack
  against the wrong period. `CoreConfig` now rejects them at build time.
- **`fclk0` does not reset on overlay reload.** `deploy_kria.sh` sets it from the manifest —
  do not bypass it, or a build silently runs at whatever the previous one left behind.
- **The 2.7B preset needs ~624 MiB of CMA.** `cma_reclaim.sh` frees the ~477 MiB that
  zocl/pynq/jupyter accumulate; `cma_boot_raise.sh` raises the boot-time total past 1000M.
- **The board compiles from `transfer/src/`, which is a COPY.** Editing anything under
  `software/` or `scripts/` and then deploying without re-staging means the next board run
  silently measures the previous code. `make stage` (new 2026-08-07, `STAGE_ONLY=1
  build_all.sh`) re-stages the bundle's scripts and sources with no Vivado; then `make deploy`,
  then `make build` on the board. The C++ change of 2026-08-07 (`hw_batch_max`) is the reason
  this got a verb of its own.
- **A sweep CSV's name does not encode the bitstream.** `<preset>_final_pN_batch_sweep.csv` is
  the same filename whether a B6 or a B8 build wrote it, so re-running under the shipped names
  overwrites — and a run that dies partway leaves a MIX of two bitstreams' rows that the
  aggregator will merge into one median. `final_sweep_board.sh` now archives any previous
  `*_final*` into `results/prev_<timestamp>/` before starting (unless `PASS_START > 1`, the
  deliberate top-up case).
