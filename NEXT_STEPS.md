# Where things stand — 2026-08-06

## 0. TOMORROW, in this order

1. **Distinct-prompt stream check.** `--serve N` gives every stream the SAME prompt
   (`main_fpga.cpp:483`) and sampling is greedy, so `streams_ok` compares streams that are
   identical by construction. It proves batched decode matches b=1 and that no stream is
   dropped — it CANNOT see a row permutation or cross-contamination between identical
   values. Simulation covers this (`BenchMirrorSpec` drives distinct per-row activations);
   hardware never has. ~20 lines: give each stream a different prompt, check each against
   its OWN single-stream reference. `generate_serve` already takes a vector of prompts.
   **Do this before benchmarking B8** — eight independent streams instead of six. And do
   not rebuild the board binary while a comparison run is in flight.

2. **Board FPGA passes 4–5**, for a median robust to one bad pass:

       cd ~/transfer && sudo PASS_START=4 PASSES=5 ./final_sweep_board.sh fpga

   ⚠️ The variables go AFTER `sudo` — it resets the environment. Putting them before
   silently ran the default passes 1..3 and overwrote the data, twice. The script now
   prints its effective settings; check the banner.

3. **Xeon passes 4–5**: `PASSES=5 ./scripts/final_sweep_xeon.sh` (idempotent — skips the
   three that exist). Deferred from 2026-08-05.

4. **Then re-aggregate** and expect a clean sheet:

       ./scripts/final_dataset.py --board ~/results/final --xeon reports/host_cpu/final

5. **B8 on the board** once 1 passes: three bundles are built and waiting in
   `transfer/presets/mmfree_*_b8/`. Needs `make deploy` first.

---

## 1. The data set

`reports/final/dataset.csv`, keyed by (platform, arm, model, weight_fmt, batch). Headline is
the MEDIAN across passes; `mean_tok_s`, `spread_pct`, `median_shift_pct` (jackknife), `watts`
and `tok_per_joule` alongside. The aggregator refuses to call it clean unless the expected
matrix is complete, every cell has power, all FPGA rows are `streams_ok=1`, and no cell's
median moves >3% when any single pass is dropped.

Complete: 3 arms x 3 models x b=1..6, POWER=1 everywhere, all three FPGA gates PASS bit-exact
(1160/1160/1544 projection invocations, 0 element diffs) under the shipped preset names.

**Seven cells still flagged**, all the same shape — one perturbed pass out of three:

    kria/370M/1p6/b5     kria/1.3B/1p6/b5, b6      xeon/2.7B/2bit/b3,b4,b5,b6

The medians already discard the bad pass; what is missing is the 5th pass proving it. See §3.

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

⚠️ The table below is the 2026-08-04 sweep, kept because the perf/W analysis under it is
built on those rows. **`reports/final/dataset.csv` is now the authority** — same three
bitstreams, but medians over three passes with per-batch power, and it agrees to ~1%
(370M b=6 103.87 vs 102.63, 1.3B 54.9 vs 55.43). Requote from the CSV once passes 4-5 land.

Three presets, one per released MMfreeLM checkpoint, all B6 + 1.6-bit packed weights +
128-bit m_axis at a true 250 MHz. Measured on a KRIA KV260, decode tok/s, POWER=1,
all gates PASS (engine bit-identical to the CPU reference), all `streams_ok=1`.

| preset | b=1 | b=2 | b=3 | b=4 | b=5 | b=6 | WNS @250 | SOM avg W |
|---|---|---|---|---|---|---|---|---|
| `mmfree_370m` | 41.67 | 65.32 | 79.00 | 93.10 | 95.84 | **102.63** | +0.030 | 5.37 |
| `mmfree_1_3b` | 20.40 | 33.30 | 42.75 | 49.18 | 51.73 | **55.43** | +0.025* | 5.96 |
| `mmfree_2_7b` | 11.72 | 19.27 | 25.16 | 29.88 | 30.97 | **34.41** | +0.025 | 6.14 |

\* 1.3B runs the 2.7B bitstream, so the 2.7B WNS is its timing record.

Reproducibility: `mmfree_1_3b` was re-measured in a separate session on 2026-08-05 and
came back 49.45 / 52.22 / 54.75 at b=4/5/6 — within ~1.2% of the row above. Treat ±1–2% as
the session-to-session band and do not read a delta smaller than that as a result.

Use **b=4 or b=6, never b=5** — the host's vector ops are `omp schedule(static)` over the
batch on 4 A53 cores, so they cost `ceil(b/4)` waves and b=5 pays b=6's price for 5 streams.

### Perf/W — the headline

Against a Xeon W-2155 running the same model at the same weight format (`reports/host_cpu/`,
RAPL package+DRAM), at b=6:

| model | KRIA tok/s | KRIA W | KRIA tok/J | Xeon tok/s | Xeon W | Xeon tok/J | advantage |
|---|---|---|---|---|---|---|---|
| 370M | 102.63 | 5.37 | 19.1 | 209.11 | 98.76 | 2.12 | **9.0x** |
| 1.3B | 55.43 | 5.96 | 9.30 | 97.69 | 111.97 | 0.872 | **10.7x** |
| 2.7B | 34.41 | 6.14 | 5.60 | 49.05 | 115.52 | 0.425 | **13.2x** |

Xeon rows are the b=6 best of `xeon_best.csv` (all three land on 2-bit, 10 threads) and use
its `total_w`, which is the column comparable to the KRIA's SOM rail figure — both include
idle. Using its `net_w` instead (idle subtracted on the Xeon side only) would read 7.8x /
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
| B4 → B6 | +11.8% | the big one. B8 also fits and closes 250 — see below — but is unbenchmarked |
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
   nothing has tried to vectorize or block it.

---


### B8 — built 2026-08-06, both close 250 MHz

    preset            WNS@250    LUT      Reg     CARRY8   BRAM     URAM     DSP
    mmfree_2_7b_b8    +0.002   83.18%   49.76%   25.92%   49.65%   50.00%   100%
    mmfree_370m_b8    +0.011   81.42%   48.30%   25.92%   29.51%   53.13%   100%
    mmfree_1_3b_b8    clone of the 2.7B bitstream (RTL sha256 identical, wt repatched)

Overturns the "~95% LUT capacity wall" verdict, which was measured on 370M B8 with FABRIC
accumulators before `dspAccum` existed. Unbenchmarked. The 2.7B's +0.002 ns is thin enough
that a different seed could miss — sweep seeds or take the 200 rung before shipping it.
They live in `CoreConfigs.candidates`, not `deployable`. See [[b8-closes-250]].

### b=5 is the least trustworthy operating point, twice over

Already known degenerate (host vector ops cost ceil(b/4) waves on 4 A53 cores, so b=5 pays
b=6's price for five streams). Now also the least stable to MEASURE: 370M b=5 pass 2 read
70.82 tok/s against 95.04/95.23, and the phase breakdown puts it entirely host-side —
rmsnorm +184%, scan +181%, swishgate +56%, engine phases only +8-14%. That is the OpenMP
wave structure collapsing when a core is stolen.

**Suspect POWER=1 as the thief.** `xlnx_platformstats` sampling is a process competing for
one of four cores. It costs ~2% on average, but can evidently cost 25% when it lands badly,
and two of the three flagged KRIA cells are b=5. If b=5 matters to you, measure it POWER=0
separately. Otherwise use b=4 or b=6 — b=6 is clean.

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
