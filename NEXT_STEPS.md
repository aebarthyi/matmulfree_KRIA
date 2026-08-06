# Where things stand — 2026-08-06

## 0. TOMORROW, in this order

0. **RE-MEASURE THE BOARD ARM.** Readback is now threaded by default (2026-08-06), worth
   **+20.4% at 370M and +11.1% at 2.7B** at b=6. Every board row in
   `reports/final/dataset.csv` was taken with the serial readback and is that much low. Both
   geometries pass the gate with `MMFREE_RB_MIN_ELEMS=1` (full coverage), so this is a
   measurement job, not a verification one:

       cd ~/transfer && sudo PASSES=5 ./final_sweep_board.sh fpga     # ~1 h, fpga arm only

   The CPU arms are unaffected (they never touch the engine), so they do not need redoing.
   **Watts must come from the new runs, not be carried over**: threading costs 0 mW on 370M
   but 289 mW on 2.7B, so the perf/W gain is +20.5% and +5.9% respectively — the throughput
   delta is NOT the perf/W delta. To A/B against the old software on the same binary, set
   `MMFREE_RB_THREADS=1`.

1. ~~**Distinct-prompt stream check.**~~ WRITTEN 2026-08-06, not yet run on hardware.
   `--distinct-prompts` gives stream *b* its own prompt (a left-rotation of the ids, so all
   B keep the same length and the timings stay comparable) and checks each against its OWN
   single-stream reference; a swap is reported as `ROW PERMUTATION` rather than a bare
   mismatch. It refuses a config where two rotations coincide, and the `--csv` row carries
   `distinct_prompts` / `distinct_refs` so a saved row says which check produced its
   `streams_ok`. Verified on the build server against the 370M blob with `--backend cpu`,
   including fault-injected swap and dropped-stream cases (both caught, and the shared-prompt
   run passes the same injected swap — that is the blind spot, demonstrated).

   **PASSED ON HARDWARE 2026-08-06**, `mmfree_370m` at B=6: `OK (every stream == its OWN
   single-stream reference)`, six distinct prompts, six distinct references, no permutation.
   That is the first time batched row independence has been shown on the board rather than in
   simulation — decode 105.38 tok/s aggregate on that run, in family with the 103.16 median.

   **Still to check, one per distinct bitstream** (this is a per-bitstream check, not a
   per-sweep one; the sweep still uses the shared prompt, unchanged, so passes 1–5 stay
   comparable):

       sudo make deploy-hw PRESET=mmfree_2_7b && sudo make streams PRESET=mmfree_2_7b

   `mmfree_2_7b` is the other geometry (maxN=8192, and 1.3B runs that same bitstream, so one
   run covers both). Then each B8 bundle — eight rows is the widest drain yet and the reason
   the check exists.

2. ~~**Board FPGA passes 4–5**~~ DONE 2026-08-06 (all three models, pulled back into
   `~/results/final/`). ⚠️ For any future top-up: the variables go AFTER `sudo` — it resets
   the environment. Putting them before silently ran the default passes 1..3 and overwrote
   the data, twice. The script now prints its effective settings; check the banner.

       cd ~/transfer && sudo PASS_START=6 PASSES=7 ./final_sweep_board.sh fpga

3. **Xeon passes 4–5**: `PASSES=5 ./scripts/final_sweep_xeon.sh` (idempotent — skips the
   three that exist). Started 2026-08-06, ~3 h. Two preconditions bite every reboot:
   RAPL needs `sudo chmod a+r /sys/class/powercap/intel-rapl:*/energy_uj` (the script
   refuses without it) and the box must be idle — a stuck VS Code `rg --files` was holding
   11 cores for 50 minutes and would have measured contention.

4. **Then re-aggregate** (KRIA half already done — that is where the 1.3B b=6 bimodality
   in §1 came from):

       ./scripts/final_dataset.py --board ~/results/final --xeon reports/host_cpu/final

5. **B8 on the board** once 1 passes there: three bundles are built and waiting in
   `transfer/presets/mmfree_*_b8/`. Needs `make deploy` first. `sudo make streams
   PRESET=mmfree_370m_b8` before any sweep — eight rows is the widest drain yet, and the
   sweep's own `streams_ok` cannot see a mis-ordered one.

---

## 1. The data set

`reports/final/dataset.csv`, keyed by (platform, arm, model, weight_fmt, batch). Headline is
the MEDIAN across passes; `mean_tok_s`, `spread_pct`, `median_shift_pct` (jackknife), `watts`
and `tok_per_joule` alongside. The aggregator refuses to call it clean unless the expected
matrix is complete, every cell has power, all FPGA rows are `streams_ok=1`, and no cell's
median moves >3% when any single pass is dropped.

Complete: 3 arms x 3 models x b=1..6, POWER=1 everywhere, all three FPGA gates PASS bit-exact
(1160/1160/1544 projection invocations, 0 element diffs) under the shipped preset names.

**Board arms are now 5 passes** (2026-08-06). The three KRIA cells that were flagged on three
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

`xeon/2.7B/2bit/b3,b4,b5,b6` are still on three passes and still flagged; passes 4-5 running
2026-08-06.

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

Three presets, one per released MMfreeLM checkpoint, all B6 + 1.6-bit packed weights +
128-bit m_axis at a true 250 MHz. Measured on a KRIA KV260, decode tok/s, POWER=1,
all gates PASS (engine bit-identical to the CPU reference), all `streams_ok=1`.

**Medians over five passes**, straight from `reports/final/dataset.csv` (2026-08-06) — that
CSV is the authority; this table is a rendering of it:

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

Use **b=4 or b=6, never b=5** — the host's vector ops are `omp schedule(static)` over the
batch on 4 A53 cores, so they cost `ceil(b/4)` waves and b=5 pays b=6's price for 5 streams.

### Perf/W — the headline

Against a Xeon W-2155 running the same model at the same weight format (`reports/host_cpu/`,
RAPL package+DRAM), at b=6:

⚠️ KRIA columns are the 5-pass medians above; **Xeon columns are still 3-pass** and the
2.7B row is flagged UNSTABLE, so the advantage ratios move when passes 4-5 land. Do not
publish this table until they do.

| model | KRIA tok/s | KRIA W | KRIA tok/J | Xeon tok/s | Xeon W | Xeon tok/J | advantage |
|---|---|---|---|---|---|---|---|
| 370M | 103.16 | 5.39 | 19.1 | 209.11 | 98.76 | 2.12 | **9.0x** |
| 1.3B | 51.36 | 5.90 | 8.71 | 97.69 | 111.97 | 0.872 | **10.0x** |
| 2.7B | 34.41 | 6.22 | 5.53 | 49.05 | 115.52 | 0.425 | **13.0x** |

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
| threaded readback | **+20.4% (370M), +11.1% (2.7B)** | the largest software lever found; default on since 2026-08-06 |
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
