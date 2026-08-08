# Where things stand — 2026-08-07

## 0. THE FINAL DATA SET — what is ready and what is waiting on you

**B8 is the final configuration and B6 is retired.** The three shipped presets carry the whole
optimization stack (B8 + dspAccum + colBankSize=4 + 1.6-bit packing + 128-bit m_axis @ 250,
threaded readback on by default in the runner), `transfer/presets/mmfree_{370m,1_3b,2_7b}/`
now hold the **B8** bitstreams under those names, and the `mmfree_*_b6` candidates are gone
from the registry. The outgoing B6 bundles are archived at `~/results/b6_bitstreams/` and the
B6 five-pass measurements at `~/results/final_b6_serial_readback/`; `git show d63d9f9` has the
B6 configs if one is ever needed. Nothing below is about B6 any more.

### ⏰ 1. KICK OFF THE KRIA SWEEP — ready now, needs your sudo

    cd ~/transfer && sudo ./final_sweep_board.sh          # both arms, ~7-8 h

Everything it needs is already on the board: the B8 bundles are deployed, the runner has been
native-recompiled there, and the batch ladder now comes from the manifest so it sweeps b=1..8
on its own. Five FPGA passes, one CPU pass at three encodings, POWER=1 on every row, gate +
`make streams` (distinct prompt per row) once per bitstream before any timing. CMA was 1.39 GiB
free at handoff against the 2.7B's ~624 MiB need, so no `cma_reclaim.sh` is required.

It archives any previous `results/*_final*` before starting, so a half-finished run can only be
incomplete, never a silent mix of two bitstreams' rows under one set of filenames.

Pull it back with the two rsyncs the script prints, into `~/results/final/` (the old B6 set
there was copied aside first), then re-aggregate.

### 2. ✅ RAPL — done 2026-08-07 (`sudo chmod a+r /sys/class/powercap/intel-rapl:*/energy_uj`)

Resets to 0400 on every reboot, so this comes back every time the box restarts.

⚠️ **And close VS Code before any measurement.** On 2026-08-07 its file search
(`rg --files --hidden --no-ignore --follow`) was walking `transfer/models` (9.6 GB) at twelve
cores while the sweep waited to start; `settle()` would have given up and started underneath
it fifteen minutes later. `final_sweep_xeon.sh` now aborts on any foreign process over a core
(or 1.5 cores in total) rather than shrugging — but that aborts the RUN, so the editor still
has to be closed. A `search.exclude` for `transfer/models` would prevent it recurring.

### 3. THE XEON SWEEP — ✅ DONE 2026-08-07, 385 min, complete and clean

`reports/host_cpu/final/`, 15 groups x 24 rows = 360 rows, b=1..8, 5 passes, 20 threads,
AVX-512 confirmed by probe, RAPL on every row. **Zero flagged cells** against five in the set
it replaces; max jackknife median-shift 1.66% (limit 3), no row missing power.

Two results that change how the table gets built — both in [[xeon-baseline-facts]]:

* **The Xeon's batch curve is not monotonic, and the shape is real.** 2-bit medians:
  370M peaks at b=7 (208.2) and falls 21% at b=8 (164.6); 1.3B peaks at b=6; 2.7B barely
  scales with batch at all. Every cell is stable to <=1.7% and the shape reproduces to ~2%
  against an independent set, so more passes will not smooth it. **At b=8 the Xeon sits in a
  trough for 370M and 1.3B**, which means an FPGA-vs-Xeon ratio at equal batch is a different
  number from best-point-vs-best-point (370M 1.20x vs 1.51x). ⚠️ UNDECIDED — pick one
  deliberately and say which; the method already gives the CPU its best thread count, which
  argues for best-point, while equal-batch is the apples-to-apples on identical work.
* **Isolated cells do not reproduce across sessions to better than ~7%**, even when five
  fresh processes agree to 0.2% within a session.

The archived b=1..6 five-pass set is `reports/host_cpu/final_b1to6_5pass_20260807/`. Its
370M/2bit/b2 and 1.3B/2bit/b5 cells were the flagged ones and are now known to have been
contended: today's quiet-box values read +11.3% and +8.7% against them.

### 3b. Superseded — how the Xeon sweep was set up

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

### 4. Then re-aggregate — the ceiling is per-cell now

    ./scripts/final_dataset.py --board ~/results/final --xeon reports/host_cpu/final

`want_batches` is no longer hardcoded. The expected ladder is read per model from the sweep
CSV's new `hw_batch_max` column (the bitstream's compile-time batchSize, handed through as
`MMFREE_HW_BATCH` so `BATCH=b` cannot shadow it), and it refuses to GUESS: a CSV without that
column gets no ladder and says so, rather than inferring one from the batches present — which
is how a half-finished sweep used to report CLEAN. Every arm for a model must cover the FPGA's
ladder, so a CPU arm that stops at b=6 shows up as `missing b=[7, 8]` instead of passing.

⚠️ CSVs written before 2026-08-07 lack `hw_batch_max`, which includes everything in
`~/results/final_b8/` and `~/results/final_b6_serial_readback/`. Aggregate those with
`--max-batch 8` / `--max-batch 6`.

### 5. Still open, not blocking

* **The 1.3B pass-3 anomaly.** b=6 read 34.23 tok/s with a NORMAL 130 µs pack, so the pack
  bimodality does not explain it; `~/results/final_b8/mmfree_1_3b_b8_final_p3_phases.csv` will
  say which phase ate it. 1.3B is the only model affected and it shares 2.7B's bitstream, so it
  is host-side and shape-specific. The new sweep may or may not reproduce it — if 1.3B comes
  back with a wide b=6/b=8 spread again, this is why.
* **`make streams` on the 2.7B geometry.** It has passed on 370M B6 hardware only. The board
  sweep now runs it once per preset, so this closes itself on the next run.

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

⚠️ **`reports/final/dataset.csv` is the B6 set and is being replaced.** It is 3 arms x 3 models
x b=1..6 with the SERIAL readback, so every KRIA row in it is 11-20% low against current
software and its ladder stops two rungs short of what the shipped bitstreams do. It stays as
the historical record until the runs in §0 land; the raw CSVs behind it are copied to
`~/results/final_b6_serial_readback/`. Everything in the rest of this section is about that
set unless it says otherwise — the B8 numbers are in §3.

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

### B8 — the shipped configuration, measured 2026-08-06

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

⚠️ **This table is entirely superseded and must not be published.** Both halves have moved:
the KRIA column is B6 + serial readback (~30% low against the shipped configuration) and the
Xeon column is a b=1..6 ladder that is being re-run. It is kept only so the *shape* of the
result — order-of-magnitude energy advantage, growing with model size — is on record while
the replacement runs. Regenerate it from `reports/final/dataset.csv` after §0.

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
