# Where things stand — 2026-08-05

`main` was cleaned down to three deployable presets on this date. **The full session-by-session
handoff, every ablation preset and the harness that produced these numbers are on the
`exploration` branch** — `git show exploration:NEXT_STEPS.md` for the long-form history,
including the reasoning behind each lever and the dead ends that were ruled out.

---

## 1. The result

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
| 370M | 102.63 | 5.37 | 19.1 | 209.11 | 95.7 | 2.19 | **8.8x** |
| 1.3B | 55.43 | 5.96 | 9.30 | 97.69 | 112.0 | 0.872 | **10.7x** |
| 2.7B | 34.41 | 6.14 | 5.61 | 49.05 | 115.5 | 0.425 | **13.2x** |

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
| B4 → B6 | +11.8% | the big one. B8 is a ~95% LUT capacity wall even with DSP accum |
| 2-bit → 1.6-bit packing | +4.3% at 2.7B | grows with model size, decays with batch — only ~+1.4% at 1.3B b=6 |
| column-tile split (k=4) | +4.4% at b=4 | hides readback under COMPUTE; the ladder is already optimal |
| 200 → 250 MHz | ~+5% | **the least valuable axis** — decode is weight-bandwidth-bound |

---

## 2. Open

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

## 3. Things that will bite you

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
