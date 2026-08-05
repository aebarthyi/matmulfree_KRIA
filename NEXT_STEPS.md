# Next steps — handoff as of 2026-08-05 (250 MHz + power sweep landed, TO DISSECT)

Data is pulled and safe. This section is the agenda for the dissection, not the analysis.

## Z1. WHAT LANDED

Six-arm FPGA sweep, **all at a true 250 MHz**, POWER=1, TAG=pwr. In `~/results/` (with the
raw SOM rail samples under `~/results/board_power/`). **All six gates PASS and every arm
reports `streams_ok=1` at all six batch sizes** — so `colBankSize=4` and the `ActMem`
BlackBox are now validated IN SILICON, not just simulation. That was the main risk.

| arm | b=1 | b=2 | b=3 | b=4 | b=5 | b=6 | SOM avg W | peak W |
|---|---|---|---|---|---|---|---|---|
| 370M 2-bit | 40.32 | 63.31 | 76.94 | 91.19 | 94.43 | 102.24 | 5.35 | 5.76 |
| 370M 1.6-bit | 41.67 | 65.32 | 79.00 | 93.10 | 95.84 | 102.63 | 5.37 | 5.77 |
| 1.3B 2-bit | 19.03 | 31.15 | 40.47 | 47.23 | 50.62 | *47.57* | 5.90 | 6.90 |
| 1.3B 1.6-bit | 20.40 | 33.30 | 42.75 | 49.18 | 51.73 | 55.43 | 5.96 | 7.18 |
| 2.7B 2-bit | 10.71 | 18.05 | 23.55 | 28.47 | 29.72 | 32.98 | 5.91 | 7.36 |
| 2.7B 1.6-bit | 11.72 | 19.27 | 25.16 | 29.88 | 30.97 | 34.41 | 6.14 | 7.61 |

## Z2. THE HEADLINE: perf/W IS NOW MEASURED ON BOTH SIDES

The gap this whole effort kept running into is closed. Xeon W-2155 figures from
`reports/host_cpu/xeon_best.csv` (measured package+DRAM), KRIA from SOM rails, b=6:

| model | KRIA tok/s | KRIA W | KRIA tok/J | Xeon tok/s | Xeon W | Xeon tok/J | KRIA advantage |
|---|---|---|---|---|---|---|---|
| 370M | 102.63 | 5.37 | 19.1 | 209.11 | 95.7 | 2.19 | **8.8x** |
| 1.3B | 55.43 | 5.96 | 9.30 | 97.69 | 112.0 | 0.872 | **10.7x** |
| 2.7B | 34.41 | 6.14 | 5.61 | 49.05 | 115.5 | 0.425 | **13.2x** |

So: the Xeon wins on raw throughput by 1.4-2.0x, the KRIA wins on energy by 8.8-13.2x, and
**the KRIA's energy advantage GROWS with model size** while its throughput deficit shrinks.
That is a far more defensible framing than either "the FPGA is faster" (it is not) or the
raw-tok/s comparison alone.

⚠️ NOT the same kind of measurement: SOM rails include the PS, PL and the SOM's DDR; RAPL
covers CPU package + DRAM and excludes board/PSU losses. Both are "compute silicon plus its
memory", so the comparison is meaningful — label it, do not claim equivalence.

## Z3. AGENDA FOR THE DISSECTION

1. **1.3B 2-bit b=6 = 47.57 is non-monotonic** — below its own b=5 (50.62) and well below its
   packed sibling (55.43), where every other arm rises monotonically. Everything else about
   the row is clean (streams_ok=1, gate PASS). Most likely a one-off; re-run that single arm
   before it goes in any table.
2. **Separate the POWER tax from the clock gain.** These rows are POWER=1; the 200 MHz rows in
   `~/results` are POWER=0, and POWER=1 costs ~2%. So 2.7B packed reads 34.41 vs 33.43 =
   +2.9% apparent, i.e. **~+5% true** for 250 over 200 — consistent with the 2-5% predicted
   from the earlier clock sweep, and confirming the clock is still the least valuable axis.
   For an apples-to-apples number, one POWER=1 run of the 200 MHz pair is needed.
3. **Power is one batch per arm, not the curve.** `power_bench.sh` names its output by the
   GEOMETRY tag, so b=1..6 overwrote each other and only the last (b=6) survived. Fine for
   the b=6 table above; fix the tagging if per-batch perf/W is wanted (the runtime batch is
   already passed to the script, it just is not in the filename).
4. **Where the 370M energy advantage goes.** 370M draws the least power (5.35 W) AND has the
   best tok/J, yet its KRIA-vs-Xeon energy ratio is the SMALLEST (8.8x) because the Xeon is
   disproportionately fast at 370M (fits its 13.75 MB L3). Worth stating explicitly so the
   trend is not misread as the FPGA scaling badly.
5. Idle/dynamic split: SOM avg 5.35-6.14 W against peaks of 5.76-7.61 W, and the board idles
   ~4-5 W. So the DYNAMIC cost of inference is only ~1-2 W. That is the number that makes the
   perf/W case, and it is worth extracting from `board_power/*.samples` properly.

## Z4. STILL OPEN FROM THE HOST SIDE

- 2.7B b=2..5 dip on the Xeon (~30% worse per row than b=1/b=6), unexplained. Not thread
  count. The clue is that at 1.3B b=2 both weight formats timed identically to 0.001%, so the
  matmul is not the bottleneck there — suspect OpenMP barrier cost (`parallel_rows` spawns
  threads to run only b iterations).
- 370M small-N x86 intrinsics still lose to the auto-vectorized scalar path; the dispatch
  works around it rather than fixing it.
- `reports/host_cpu/2026-08-04-final/README.md` still carries the 20-thread comparison and is
  superseded by the 10-thread run — rewrite it with the Z2 table.

---

# Next steps — handoff as of 2026-08-04 (afternoon session)

Two items from §G below were taken on: **G1, the clock mismatch (CLOSED)** and **G4, the
250 MHz attempt (IN FLIGHT)**. Everything under "# Next steps — handoff as of 2026-08-04"
further down is the morning session and is unchanged except where this section corrects it.

---

## AA. THE CLOCK STORY IS SETTLED — and §B below was wrong about the size of the cut

§B said "one rebuild with the new instrumentation prints the period and settles it". It did
not need a rebuild. `scripts/probe_pl_clk.tcl` (new) builds the real block design once and
then reads `PSU__CRL_APB__PL0_REF_CTRL__ACT_FREQMHZ` back out of the PS IP across a list of
requests — **minutes, no synthesis, and it produces the whole rung table instead of one
point.** Run it as:

```
set -a && . generated/<any-built-preset>/preset.env && set +a && unset PL_CLK_MHZ
vivado -mode batch -nojournal -notrace -source scripts/probe_pl_clk.tcl \
       -tclargs generated/<any-built-preset>
```

### The rung table (MEASURED, part xck26-sfvc784-2LV-c, SRCSEL=IOPLL)

| reachable rung | period |
|---|---|
| 299.997009 | 3.3334 ns |
| 249.997498 | 4.0000 ns |
| 199.998001 | 5.0000 ns |
| 166.664993 | 6.0001 ns |
| 142.855713 | 7.0001 ns |
| 99.999001  | 10.0001 ns |

Requests in between snap **DOWN**: 215/220/225/230/240 → **199.998**, and
275/285/290/295 → **249.997**. Vivado's rungs and the board's fclk0 rungs are the SAME set,
so one table serves both sides.

### What that changes

1. **Every `_230` and `_225` preset was a 200 MHz build from the start** — not a 230 MHz
   build being run out of spec, and not the "20% cut" §B framed as a regrettable
   consequence. The constraint was 5.000 ns the whole time. The 225/230 bitstreams being
   byte-identical is now explained rather than suspected.
2. **The recorded WNS numbers were slack against the wrong period, so every Est. Fmax in
   `reports/` was wrong.** Corrected:
   - `b6_dsp_s8_230` +0.118 ns → Fmax **204.8** MHz (the .rpt claimed 235.2)
   - `b6_dsp_s8_packed` +0.003 → **200.1** MHz — razor-thin at its REAL rate, which the
     230 label had been hiding
   - `b4_dsp_s8_packed` +0.096 → **203.9** MHz
3. **The overlays already knew.** Every `_230`/`_225` dtbo carries
   `assigned-clock-rates = <0xbebba31>` = **199,998,001 Hz**, and the kernel programs that on
   overlay load. So the fabric was at 200 after `loadapp` — and it was `deploy_kria.sh`'s own
   fclk write of 230 that pushed it UP to 250 afterwards. The snap-down fix from §B was
   treating a symptom of the mislabel.
4. **You cannot over-constrain through `PL_CLK_MHZ`.** The 275/285/290/295 "ceiling sweep"
   was four runs of the same 250 MHz constraint, which is why all four reported WNS 0.000.
   The board-measured "+33% clock → +2.5% tok/s" is still a real measurement (it came from
   fclk0, not from STA) but the STA half of [[fclk_runtime_vs_sta_clock]] is void. For
   genuine margin use `set_clock_uncertainty`, not a fictional clock.
5. **§C's cost/benefit for chasing 250 stands and is now better grounded.** The deployable
   2.7B/1.3B builds are closed at 200 with +0.003…+0.118 ns, so 250 is a real +25% fabric
   clock rather than the +8.7% §B assumed — but §A measured that same 200→250 step as worth
   only 2–5% at b=6, and that measurement was taken on these exact bitstreams.

### Fixes landed (no Vivado run needed for any of them)

- `CoreConfig.plClkRungs` + a `require`: an off-rung `plClkMhz` is now a **build failure**.
  Two CoreConfigSpec cases pin it, including one that walks every named preset.
- Presets renamed so name == manifest == constraint == fabric:
  `k26_mmfree{1_3b,2_7b}_a16_b6_dsp_s8_230` → `…_200`, `k26_mmfree1_3b_a16_b6_230` → `…_200`.
  The `_packed` presets keep their names; only their `plClkMhz` moved 230 → 200.
- **`_225` and `_240` presets DELETED.** Proven duplicates: both snap to the rung their
  parent already used, so each was 40 minutes of Vivado per model size for a second copy of
  the same bitstream. `K26_MMFree2_7B_A16_B8_DSP_S8_PACKED` moved 215 → 200 (same thing —
  it never had the ~0.30 ns of margin its comment claimed).
- `build_project.tcl` now reads the CONSTRAINED period off `get_clocks clk_pl_0`, reports
  "requested → CONSTRAINED", computes Est. Fmax from the real period, shouts if the request
  snapped, and drops `build/pl_clk_actual.env`. `build_all.sh` appends that to the shipped
  manifest as `PL_CLK_ACT_MHZ`; `deploy_kria.sh` prefers it for fclk0.
- `deploy_kria.sh` also cross-checks the **dtbo's** `assigned-clock-rates` against the
  manifest and warns on a mismatch in either direction, falling back to the dtbo rate. This
  immediately caught a pre-existing one the other way round: **`k26_mmfree1_3b_a16_b4` ships
  a 250 MHz manifest over a 199998001 Hz dtbo** — a bundle relabelled without a rebuild. Not
  in the 12-arm set, but do not quote a number from it.
- `sweep_all.sh` / `transfer.mk` updated to the `_200` names.
- Bundles in `transfer/presets/` were relabelled **in place** rather than rebuilt: a 200
  request produces a byte-identical constraint to the 230 request, and `Core.sv`/`CoreTop.sv`
  were diffed to confirm the ActMem refactor left the legacy SV byte-identical. So the
  existing bitstreams ARE the 200 MHz builds; only `preset.env`/`preset.json` and the
  directory names changed.

**Board action required:** `make deploy` from the build server before the next sweep, or the
`_200` presets will not exist on the board (the old `_230` directories are still there).
Two now-orphaned directories can be deleted by hand whenever convenient:
`transfer/presets/k26_mmfree{1_3b,2_7b}_a16_b6_dsp_s8_packed_225/`.

**The §A/§B numbers do NOT need re-measuring.** The 200 MHz rows are correct and in spec;
only the `clk_mhz=230` label on them was wrong, and it is now 200 everywhere.

---

## BB. THE 250 MHz ATTEMPT — **IT CLOSES.** WNS +0.042 ns, Est. Fmax 252.7 MHz

`k26_mmfree2_7b_a16_b6_dsp_s4_acb2`, built 2026-08-04 12:13, **250.000 MHz constrained**
(the new instrumentation confirms no snap). Against the same stack's −0.376 at
`colBankSize=8` with the inferred actBram, that is a **0.418 ns swing** — so the answer to
"is 250 possible on maxN=8192 B6" is yes, and §C's "probably not worth fixing" was a
judgement about VALUE (2–5% tok/s), not about feasibility. Feasibility was never tested
properly because every earlier attempt moved one of two paths that fail 0.005 ns apart.

**Both target paths left the top 30 entirely.** `actMem` appears in **zero** of the 30 worst
paths; the DSP-accumulator broadcast that was −0.371 now sits at ~+0.05. The new binding
path is a *third* net in the same banked pipeline:

```
0.042  logic 0.602  route 3.057 (83.5%)  3 levels
  arr/nAccPipe_3_REG_reg[5]/C -> arr/arr_5_12/nAccReg_reg[12]/D
```

i.e. the 13-bit **nAcc** broadcast from a bank's pipeline register to that bank's PEs.
All 30 worst paths are this family, clustered in 0.042…0.063 — **distributed congestion,
not one fixable path**, so do not expect another 0.4 ns from a fourth lever.

Cost of closing: essentially nothing. LUTs 65859 → 65440 (*down* 419), registers
82701 → 83247 (+546, the deeper per-bank pipeline), CARRY8 unchanged at 1619, BRAM 64 → 64.5
tiles, URAM 24, DSP 1248/1248. Latency +7 cycles/op instead of +3, against an nAcc of
2048–8192.

### The attribution build was CANCELLED, because this report already answers it

`k26_mmfree2_7b_a16_b6_dsp_s8_acb2` (actBram fix alone) was queued to test whether
`colBankSize=4` was needed. It is: the DSP-accum broadcast structure measures −0.371 at
`colBankSize=8` and ~+0.05 at 4, so with S8 retained that path binds at −0.371 no matter what
actBram does. Killed mid-run and the machine given to the deployable packed build instead.
If you want the isolation datapoint on paper anyway it is one 50-minute build, but it cannot
change the decision — both levers are free, so both stay.

### The deployable packed build ALSO CLOSES — WNS +0.025 ns, Est. Fmax 251.6 MHz

`k26_mmfree2_7b_a16_b6_dsp_s4_acb2_packed`, built 2026-08-04 14:04, 250.000 MHz constrained.
**So 250 MHz survives the unpack gearbox and the shipping configuration is a real 250 MHz
bitstream** — this is the deployable result, not just the feasibility one.

**The gearbox costs 0.017 ns on this engine, not ~0.098.** 0.042 → 0.025 with packing as the
only change. The 0.098 figure came from a single 2.7B B4 measurement and was already
contradicted by 370M (~0.004: S8 control +0.034 vs packed +0.030); this is the third data
point and it sides with 370M. Mechanism: the gearbox sits on the s_axis→inQ path, which after
the actBram fix is nowhere near critical, so what is left is placement perturbation rather
than a lengthened path. **Stop using 0.098 as the gearbox budget** — the TIMING BUDGET note in
the frozen-set comment above was built on it.

Cost is area, not timing:

| | s4_acb2 (2-bit) | s4_acb2_packed | delta |
|---|---|---|---|
| CLB LUTs | 65440 (55.87%) | 73192 (62.49%) | +7752, +6.6 pp |
| CLB Registers | 83247 | 84503 | +1256 |
| CARRY8 / BRAM / URAM / DSP | 1619 / 64.5 / 24 / 1248 | identical | — |

Still ~37 pp of LUT headroom, so the gearbox is not near a capacity wall here.

### What is left to do on this

1. **1.3B sibling**: `k26_mmfree1_3b_a16_b6_dsp_s4_acb2_packed` already exists as a preset
   (identical RTL, 1.3B manifest). Assemble it from this bitstream with
   `scripts/clone_preset_hw.sh` — seconds, not a second 40-minute Vivado run.
2. **`make deploy`**, which is needed anyway for the §AA `_200` renames.
3. **Re-measure the 1.3B/2.7B FPGA arms at 250** and compare against the 200 MHz rows in §A.
   Expect 2–5% at b=6 on the strength of that same comparison run the other way. **Pass
   `TAG=`** so the 200 MHz rows survive this time (§A's lesson, learned the hard way).
4. Gate first, as always — `colBankSize=4` and the ActMem swap are both numerically
   transparent in simulation, but neither has run on hardware yet.

Not worth doing unless something regresses: `k26_mmfree2_7b_a16_b6_dsp_s8_acb2` (the
attribution build), and `actCascadeHeight=1` / `colBankSize=2` (extra margin on a path that
already closes).

### Also fixed: `build_queue.sh` reported `WNS=?` for every build

Latent since 2026-08-03, when `build_project.tcl` started writing `<tag>.timing.rpt`: the
summary's `*.rpt` glob matched it too, and since the timing report is written *after* the
utilization report, `ls -t | head -1` picked it every time. Its table has a `WNS(ns)` column
rather than a `WNS: <n>` line, so the grep found nothing. Now anchored on `*MHz.rpt`, and the
row also records the **constrained** clock. The 2026-08-03 `b6_dsp_s8` row and the first row
of this session both read `WNS=?` for that reason; the real numbers are −0.376 and +0.042.

<details><summary>original plan text (superseded by the result above)</summary>

## BB-orig. THE 250 MHz ATTEMPT — built, timing result pending

§C priced this as "probably not worth fixing" and that judgement still holds on tok/s
grounds (2–5%). It is being tried anyway because §C also established the thing that made
every previous attempt futile: **two structurally different paths fail 0.005 ns apart**, so
each single-lever build was capped by the path it did not touch. Both are now addressed:

| path | bound by | fix | preset field |
|---|---|---|---|
| −0.376, 3.206 ns logic, 7 levels | 8-deep RAMB36E2 hard cascade (`actBram` → `inQ`) | cap the chain at 1 hop + a 4:1 fabric mux | `actRamStyle="block"`, `actCascadeHeight=2` |
| −0.371, 3.189 ns route (74%) | fanout-103 banked broadcast net crossing the die | 8 banks instead of 4, halving fanout | `colBankSize=4` |

The cascade diagnosis is not inference — the full timing report names every hop:
`Prop_RAMB36E2_CLKBWRCLK_CASDOUTB` 1.588 ns, then 7 × `Prop_RAMB36E2_CASDINB_CASDOUTB`
0.247 ns, then `CASDINB_DOUTBDOUT` 0.136 ns = 3.206 ns of logic before a single net routes.
370M (maxN=4096, 4-deep) closes 250 with no levers at all, which is the control: chain DEPTH
is the variable, not batch width.

New RTL: `src/main/scala/control/ActMem.scala`, a BlackBox carrying `ram_style` +
`cascade_height` (CIRCT cannot attach those to a `SyncReadMem` — same reason `OutMemUltra`
exists). Deliberately **not** pipelined: a read-latency stage would desynchronise the
weight/activation pairing `inQ` depends on, and that pairing has a history of off-by-one-beat
bugs. Read semantics are identical to `SyncReadMem`, so this is a mapping change only —
4 new CoreSpec cases verify identical results on the baseline's own seeds, plus the re-LOAD
write path and every `(ram_style, cascade_height)` variant. Full suite: 137 tasks green.

Queued (`reports/build_queue/summary.txt` has the WNS when they land):

1. `k26_mmfree2_7b_a16_b6_dsp_s4_acb2` @250 — the attempt. Direct A/B against
   `k26_mmfree2_7b_a16_b6_dsp_s8` @250 (−0.376): same DSP+B6 stack, two fields changed.
2. `k26_mmfree2_7b_a16_b6_dsp_s8_acb2` @250 — attribution: the actBram fix ALONE. If this
   also closes, `colBankSize=4` was unnecessary; if it misses by ~0.37, both paths had to go.

Not yet built, and only worth building if #1 closes with real margin (the unpack gearbox
costs ~0.098 ns on this engine): `k26_mmfree2_7b_a16_b6_dsp_s4_acb2_packed` and its 1.3B
sibling — the latter via `clone_preset_hw.sh`, not a second Vivado run.

**If it closes**, the deployable path is packed@250 for both sizes and the expected gain is
2–5% at b=6 on top of the 33.43 / 54.75 tok/s in §A. **If it misses**, read the new
`reports/<tag>.paths.txt` before spending another build: the useful question is whether the
worst path moved off `actBram` (fix worked, something else binds) or stayed on it (the fabric
mux cost as much as the cascade, and URAM `ram_style="ultra"` is the next lever).

(Outcome: it moved off `actBram` entirely — the fix worked and `ram_style="ultra"` was never
needed.)
</details>

---

# Next steps — handoff as of 2026-08-04

The 2026-07-26 handoff follows below, unchanged, from "# Next steps — handoff as of
2026-07-26". Everything in this new section is from the 2026-08-03/04 session.

---

## A. THE NUMBERS (12-arm matrix, all gates PASS, all streams_ok=1)

Ordered by model size; within each size: CPU 2-bit, CPU 1.6-bit, FPGA 2-bit, FPGA 1.6-bit.
Decode tok/s. **The FPGA rows exist at two fabric clocks — see §B, this is not a typo.**

### FPGA at 250 MHz (out of spec for 1.3B/2.7B — see §B)

| | b=1 | b=2 | b=3 | b=4 | b=5 | b=6 |
|---|---|---|---|---|---|---|
| 370M FPGA 2-bit  | 40.30 | 64.85 | 80.51 | 93.96 | 95.95 | 105.10 |
| 370M FPGA 1.6-bit| 41.61 | 66.48 | 82.21 | 94.45 | 97.15 | **105.93** |
| 1.3B FPGA 2-bit  | 18.97 | 31.90 | 41.64 | 48.67 | 52.04 | 55.38 |
| 1.3B FPGA 1.6-bit| 20.46 | 34.23 | 43.89 | 50.72 | 53.45 | **56.22** |
| 2.7B FPGA 2-bit  | 10.76 | 18.37 | 24.16 | 29.24 | 30.36 | 33.78 |
| 2.7B FPGA 1.6-bit| 11.72 | 19.89 | 25.71 | 30.69 | 31.61 | **35.23** |

**THESE CSVs WERE OVERWRITTEN** by the 200 MHz re-run (same preset -> same filename, no TAG).
The 1.3B/2.7B rows above exist ONLY in this file now. The 370M rows are still on the board
(they were not re-run — 370M is built at 250 and runs at 250, already in spec).
Lesson: pass `TAG=` when re-running a preset under different conditions.

### FPGA at 200 MHz (in spec), 1.3B/2.7B only

| | b=1 | b=2 | b=3 | b=4 | b=5 | b=6 | vs 250 @ b=6 |
|---|---|---|---|---|---|---|---|
| 1.3B FPGA 2-bit  | 18.09 | 30.74 | 40.18 | 47.22 | 50.69 | 54.21 | −2.1% |
| 1.3B FPGA 1.6-bit| 18.58 | 31.67 | 41.23 | 48.12 | 51.47 | 54.75 | −2.6% |
| 2.7B FPGA 2-bit  | 10.20 | 17.63 | 22.52 | 27.97 | 29.33 | 32.75 | −3.0% |
| 2.7B FPGA 1.6-bit| 10.48 | 18.19 | 23.87 | 28.79 | 30.25 | 33.43 | −5.1% |

### CPU (unaffected by fabric clock)

| | b=1 | b=6 |
|---|---|---|
| 370M CPU 2-bit / 1.6-bit | 9.04 / 7.62 | 9.46 / 7.89 |
| 1.3B CPU 2-bit / 1.6-bit | 2.46 / 2.08 | 2.55 / 2.11 |
| 2.7B CPU 2-bit / 1.6-bit | 1.24 / 1.03 | 1.27 / 1.05 |

### What the matrix says

- **A 20% clock cut costs only 2–5% at b=6** (5–11% at b=1). Strongest confirmation yet that
  decode is weight-bandwidth-bound, not fabric-compute-bound. The in-spec numbers are
  essentially free — use them.
- **Packing gain shrinks with batch and with lower clock**, grows with model size. At 250:
  +3.3/+7.8/+9.0% at b=1 (370M/1.3B/2.7B) decaying to +0.8/+1.5/+4.3% at b=6. At 200 MHz the
  2.7B b=6 gain is only +2.1% (vs +4.3% at 250) — a slower engine makes DDR less binding, so
  there is less for packing to recover.
- **CPU barely batches**: +4.6/+3.5/+1.7% over b=1..6 vs 2.6–3.0x on the FPGA. This contrast
  is the cleanest single result in the set.
- **CPU 2-bit beats CPU 1.6-bit by ~20% at every size** (see §D).
- Speedup at b=6, best FPGA vs best CPU arm: 11.2x / 22.0x / 27.8x at 250 MHz;
  1.3B 21.5x and 2.7B 26.4x at 200 MHz.
- b=5 is no longer an absolute dip but still gains ~2% where b=6 gains ~9% — the ceil(b/4)
  wave effect from [[batch_wave_quantization]] is intact.

---

## B. THE CLOCK IS NOT WHAT THE PRESET NAME SAYS — resolved, with a correction

`PL0_REF` exists only on discrete rungs. **MEASURED 2026-08-04: the rungs are 1000/N MHz —
250, 200, 166.67.** An earlier comment in `deploy_kria.sh` guessed 1500/N (250 / 214.29 /
187.5); that was wrong and is now corrected in the script. So there is no 214.29 rung: a
"230 MHz" build walked down lands on **200**, a 20% cut, not the 14% we expected.

Chronology of what went wrong and what is now true:
1. Every `_230` preset was silently running at **250 MHz** — 8.7% past its STA closure. Proven
   by the deploy logs (`requested 230000000, achieved 249999998`).
2. `deploy_kria.sh` already had a snap-down loop meant to prevent this. It never worked: it
   stepped down from the ACHIEVED rate by 1 MHz, so from 250 it asked 249, snapped back to
   250, and the "no movement" guard aborted with `tries=0`. **Fixed** — it now steps the
   REQUEST down 5% per round, which crosses the gap and terminates.
3. Re-ran the four 1.3B/2.7B FPGA arms in spec: all landed on 200 MHz, all four gates PASS.
4. Vivado DOES constrain exactly what it is asked: the timing report header for a
   `PL_CLK_MHZ=250` build shows `clk_pl_0 ... 4.000 ns ... 250.000 MHz`.

**Still open:** whether a `PL_CLK_MHZ=230` build is constrained at 230 or at an
`ACT_FREQMHZ`-snapped rung. The 225 and 230 builds being byte-identical (same sha256, same
WNS 0.003) strongly suggests the PS IP snaps them to a common value. One rebuild with the new
instrumentation prints the period and settles it. Does not block anything: running those
bitstreams at 200 is in spec under either reading.

**Action for tomorrow:** decide whether to rebuild the 1.3B/2.7B presets with
`plClkMhz = 200` so that the constraint, the manifest, the CSV label and the fabric all agree.
Right now `clk_mhz` in the CSVs says 230, which is none of those things. Mitigated today by
adding an `fclk_hz` column to the runner that records the ACHIEVED rate per row, so future
rows are self-describing — but the existing CSVs predate it.

---

## C. TRUE 250 MHz: TWO failing paths, 0.005 ns apart — probably not worth fixing

Builds now emit `reports/<tag>.timing.rpt` and `reports/<tag>.paths.txt` (added to
`build_project.tcl`; before this, a build recorded only a single WNS number and diagnosing a
critical path cost a 40-minute rebuild). From `k26_mmfree2_7b_a16_b6_dsp_s8` @ 250, WNS −0.376:

| slack | logic | route | route% | levels | path |
|---|---|---|---|---|---|
| −0.376 | 3.206 | 1.033 | 24% | 7 | `actBram_ext/…bram_8/CLKBWRCLK` → `inQ/ram_ext/…/RAME_D1/I` |
| −0.371 | 1.125 | 3.189 | **74%** | 3 | `arr/REG_11_0_reg[0]/C` → `arr/arr_4_11/accumReg_0_reg/…/ALU_OUT[n]` |

29 of the 30 worst paths are the second (one per accumulator bit), which is why it was
previously called "the" critical path — but the true worst is a different structure, and
**fixing either alone gains nothing.**

- **Path 1, logic-bound, specific to maxN=8192.** `actBram = SyncReadMem(8192, Vec(6,
  UInt(16.W)))` (`Core.scala:148`) is 8192x96 = an 8-deep RAMB36 cascade; the cascade mux is
  the 7 levels / 3.2 ns. 370M (maxN=4096, 4-deep) closes 250 at +0.034 — depth is the
  variable. Pipelineable: register the BRAM read output (+1 cycle LOAD latency), or move
  actBram to URAM (`ram_style="ultra"`, 4 URAMs, URAM at 37.5%, `OutMemUltra` is precedent).
- **Path 2, route-bound at 74% — pipelining will NOT help.** Banked activation broadcast into
  a DSP accumulator, 3.19 ns of pure route over 3 levels. Physical cause: DSPs 100% used
  (1248/1248) while B6 needs 32·6·8 = 1536 accumulators, so ~288 spill to fabric and the
  broadcast must reach DSP columns scattered across the die. Levers: `colBankSize=4`
  (fanout 8→4, one parameter, one build), per-row replication of the broadcast register, or
  Pblock floorplanning.

**Cost/benefit: don't chase it.** Today's data prices the clock directly — 250→200 (−20%) cost
2–5% at b=6, so 200→250 buys back ~3–5% for two nontrivial RTL changes plus rebuild cycles.
The 295 MHz ceiling sweep said the same thing (+33% clock → +2.5%).

---

## D. CPU-side weight packing — landed, verified, and a negative result worth keeping

`matmulfreellmCPU/cpp/include/mmfree/tq1.hpp` adds two packed CPU weight formats so the CPU
arm can be measured at the FPGA's encodings:
- `--backend cpu-1p6` — llama.cpp TQ1_0, 5 trits/byte, 1.625 bits/weight, base-3
  multiply/compare extraction (`(q>=86)+(q>=171)`, proven equal to `((uint16)q*3)>>8`).
- `--backend cpu-2bit` — 4 trits/byte, exactly 2.0 bits/weight, shift/mask extraction.
- `--backend cpu` — unchanged int8 (8 bits), still the CPU's FASTEST configuration.

Verification (`software/integration/tests/ && make tq1`): exhaustive over all 243 five-trit
and all 81 four-trit/two-bit groups; `dot_i32` bit-EXACT vs `simd::ternary_dot_i32` at every
real inner dim and over 2000 random vectors; backends equal to `CpuBackend` over a full
projection; 64/128-token generations byte-identical across all three formats. NEON path was
compiled and run on the actual A53, not just x86.

**The negative result:** the A53 ternary dot is ALU/issue-bound, NOT weight-bandwidth-bound —
int8 reaches only ~3.4 GB/s of weight reads (~1.5 cycles/weight/core), well under the DDR
ceiling. So cutting weight traffic buys nothing and the unpack is pure added cost. Per-shape
microbench (`make tq1` then `build/test_tq1 --bench`): 1.6-bit 0.52–0.82x of int8; 2-bit
0.96–1.25x (it wins at the largest inner dims, where shift/mask is cheap enough that the
traffic saving shows). The e2e sweep agrees: CPU 2-bit beats CPU 1.6-bit by ~20% everywhere.

Consequence for the write-up: quote `cpu` (int8) as the CPU baseline — it is the CPU's best
number and therefore the fair denominator. The packed CPU arms answer the narrower question
"same weight format on both sides".

---

## E. CMA: was never leaking

`CmaFree` shrinking across a run looked like a leak. It is not: the CMA region doubles as
backing for MOVABLE allocations, so the kernel lends idle CMA pages to page cache and migrates
them back on demand. Proof — with **zero** udmabuf nodes (no CMA clients at all), `CmaFree`
was 511 MiB of 1536 while `Cached` was 2.95 GB, exactly the 2.7B model. And it goes back UP
(361 MB → 755 MB between two arms); a leak never returns memory.

The releases were working: after an arm loaded, the next arm's pre-flight showed exactly its
MiB returned. `deploy_kria.sh` reporting "reclaimed 0 MiB" is correct — `sweep_all.sh` already
unloads after each arm, so deploy's unload is a genuine no-op.

**Removed a misleading metric I had added**: "this overlay took N MiB" from a CmaFree delta
reported 199 MiB for a 624 MiB allocation, because the kernel was concurrently migrating ~425
MiB of borrowed page cache out. It now reports the actual udmabuf total. `CmaFree` is a poor
pre-flight signal in both directions; the authoritative check is the per-node verification.

Also fixed: an empty `$(( ))` operand in the CMA pre-flight was a FATAL expansion error that
aborted the whole spec loop, so a host without CMA would run one arm and report success.

---

## F. Infrastructure added this session

- `scripts/clone_preset_hw.sh` — build a differently-named preset from an existing bitstream
  when the RTL is byte-identical (used to make the three 1.3B B6 presets from the 2.7B ones,
  seconds instead of ~40 min each). Refuses unless the SV matches and the manifest differs
  only in `MMFREE_PRESET/SHAPES/MODEL/UDMABUF_WT_SZ`; verifies the dtbo patch by dtc
  round-trip.
- `k26_mmfree370m_a16_b6_s8` — the matched 2-bit control for the 370M packing A/B (was
  confounded by column banking). Closed 250 at WNS +0.034, LUT 75.11%. NOTE: predicted +0.13
  from removing the gearbox; got +0.034. The area came out (−6.8 pp LUT) but the gearbox is
  not on the 370M critical path.
- `BACKEND=` in `bench_batch.sh` / `make sweep`, and a 5th `backend` field in `sweep_all.sh`
  specs, so CPU arms run in the same driver (skipping overlay, CMA and gate).
- `sweep_all.sh` default is now the 12-arm matrix grouped by size.
- `fclk_hz` CSV column (the achieved fabric clock, per row).
- Blobs packed: 2-bit for 1.3B and 370M under the `ridger/MMfreeLM-*` slugs.

---

## G. Open items for tomorrow

1. ~~**Decide the clock story** (§B)~~ **CLOSED 2026-08-04 — see §AA.** It needed no rebuild: the rungs were read straight out of the PS IP. 230 was never a real constraint, so the existing bitstreams already WERE the 200 MHz builds and only the labels moved.
2. **Unexplained +15%**: 2.7B 2-bit b=6 measured 33.78 on 2026-08-03 vs **29.30** on
   2026-07-30 for the same preset, same blob, POWER=0 both. The board binary was rebuilt in
   between; this repo has documented precedent of a session running on a stale binary ~12%
   slow. Do not mix numbers across those two sessions until this is understood.
3. **370M 2-bit blob under the legacy slug**: `transfer/models/1.3b/model.mmfree` is actually
   the 2.7B checkpoint (hidden 2560, 32 layers). The blob beside it IS 1.3B. Nothing
   references it now — decide whether to delete the legacy `1.3b/` and `370m/` slug dirs.
4. ~~**Optional**: `colBankSize=4` + the actBram URAM/pipeline change~~ **IN FLIGHT — see
   §BB.** Both levers landed as RTL/config (`ActMem` BlackBox bounding the cascade rather
   than the URAM or pipeline options sketched here — a pipeline stage would have broken the
   inQ activation pairing). Two builds queued; the deployable packed@250 pair waits on the
   first result.
5. **STILL NOTHING IS COMMITTED**, including the submodule. See §0 of the older handoff below;
   `reports/` and `transfer/` remain gitignored, so every bitstream and CSV lives only on this
   build server and the board.

---
---

# Next steps — handoff as of 2026-07-26

Written at the end of the batching/benchmark session. Everything below is either measured,
built-and-unmeasured, or designed-and-unbuilt — the state is called out per item so nothing
reads as more finished than it is.

---

## 0. READ FIRST — nothing is committed

All of this session's work is in the working tree, not in git:

```
 M scripts/{build_all.sh,transfer.mk}
 M software/integration/fpga_runner/{Makefile,fpga_backend.cpp,fpga_backend.hpp,main_fpga.cpp}
 M software/integration/libmmfree/{mmfree_lib.c,mmfree_lib.h}
 M src/main/scala/control/CoreConfig.scala
 M src/test/scala/control/CoreSpec.scala
 ?? scripts/{bench_batch.sh,build_queue.sh,split_sweep_csv.py,sweep_all.sh,sweep_split_ab.sh,sweep_split_k.sh}
 ?? software/integration/tests/{Makefile,test_split_chunks.c}
 m matmulfreellmCPU          <- SUBMODULE also dirty
```

The submodule `matmulfreellmCPU` has its own uncommitted changes
(`cpp/include/mmfree/{model.hpp,ternary_backend.hpp}`, `cpp/src/kernels/bitlinear.cpp`,
`cpp/src/model/hgrn_model.cpp`) on branch `main` at `fab501d`. It needs its **own commit
first**, then a pointer bump in this repo — otherwise a fresh clone builds against the old
submodule and the new `matmul_batch_chunked` / `generate_serve(on_token)` calls vanish.

Also note `reports/` and `transfer/` are **gitignored**. So the benchmark CSVs
(`reports/sweeps/`) and every built bitstream (`transfer/presets/`) exist only on this build
server and on the board. If the machine is wiped, the bitstreams are hours of Vivado to
regenerate and the measurements are gone. Decide whether results deserve a versioned home.

---

## 1. Measurements queued (built, shipped to the board, never run)

The board needs a password for `sudo`, so these have to be run by hand from `~/transfer`.

### 1a. The dequant-hiding change — ANSWERED 2026-07-29: +0.6%, knee did not move

**Result (2.7B packed-1p6, b=4 @230, all 7 gates PASS, all streams_ok=1):**

| k | 0 | 2 | **4** | 6 | 8 | 12 | 16 |
|---|---|---|---|---|---|---|---|
| decode tok/s | 26.15 | 27.40 | **27.49** | 27.09 | 26.79 | 26.23 | 25.68 |

27.32 → **27.49** at k=4 = **+0.6%**, against ±0.13–0.35% rep-to-rep noise. k=0 reproduced
26.15 vs 26.17, so nothing else drifted. **The knee stayed at k=4** — the expectation below
that it would rise was wrong, and the curve above 4 still decays monotonically.

Why so small: at k=4 hidden readback rose 957→1179 ms (+222, mechanism working) and CPU
`matmul` fell 7529→7478 (−50), but engine `bl_total_ms` rose 4264→4455 (**+191**). Net
op_total 10122→10066 ms (−56 ms/run). **~75% of the hidden work came back as a longer
window** — the host was already busy inside it, so the apparent free capacity (2571 ms window
vs 957 ms readback) was mostly illusory.

Raw data: `reports/sweeps/2026-07-29_split_k_dequant/` (pulled off the board; gitignored).

Two follow-ups this exposed:
- ~~The sweep only ever measures **uniform** k.~~ **CLOSED 2026-07-30: the ladder is fine.**
  Controlled same-session A/B, POWER=0 both arms: ladder **27.44** vs forced k=4 **27.53**
  (+0.34%), per-rep ranges overlapping (27.34–27.51 vs 27.47–27.58). Do not change the ladder's
  top rung. k=4 hides 31% more readback (1174 vs 895 ms) but runs a 2.0% longer engine window
  (4449 vs 4363 ms) — nets to nothing, same "window is not idle" result as 1a.
- **`POWER=1` costs ~2.0% decode** (26.88 vs 27.44, same preset/blob/overlay/session). This is
  why the `sweep_all` ladder run looked like a regression. `sweep_all.sh` defaulted POWER=1 while
  `bench_batch.sh`/`sweep_split_k.sh` default 0, so cross-script comparisons were invalid;
  **the default is now 0** (2026-07-30). Only compare POWER=1 against POWER=1.
- **No CMA regression:** POWER=0 uniform k=4 was 27.49 at `cma=1000M` and 27.53 at `cma=1536M`.
  Raising CMA cost nothing — the page-cache worry was unfounded (weights live resident in
  udmabuf, not streamed through page cache during decode).
- `sweep_split_k.sh` now gates every k; see the correction below.

<details><summary>original plan text (superseded)</summary>
Per-chunk callback now runs dequant inside the engine's COMPUTE window (see
`memory: column-tile-splitting`, step 2). Built, unit-tested host+board, **never run on
hardware**.

```
cd ~/transfer && sudo ./sweep_split_k.sh
```

- **Baseline to beat: 27.32 tok/s** (2.7B packed, b=4, k=4) from the previous k-sweep. Same
  bitstream and model, so the delta isolates dequant-in-window.
- Run the **whole k range**, not just k=4: more hideable CPU work means more COMPUTE windows
  are worth having, so **the knee is expected to move above k=4**. The previous curve turned
  over at k=8 only because there wasn't enough work to fill the extra windows.
</details>

- A wrong column range here corrupts values silently rather than crashing — dequant applied
  twice, or not at all. **Correction (2026-07-29): the script did NOT gate** — it ran
  `deploy_kria.sh` then `bench_batch.sh`, and neither compares against the CPU. `streams_ok`
  does not cover this: its reference is a single-stream run on the *same* FPGA backend
  (`main_fpga.cpp:423`), and splitting applies at b=1 too, so a uniform chunk-boundary bug
  corrupts the reference and every stream alike and still reports `streams_ok=1`.
  `sweep_split_k.sh` now gates every k (`--backend both`, `GATE_GEN=4` tokens) before timing
  it, and skips any k that fails. It also gates the `packed-1p6` blob, not the manifest's
  2-bit default.

### 1b. RUN 2026-07-30 — B6@230 is the new 2.7B best; runtime-batch works but is worth ~0.6%

All three gates PASS (CPU==FPGA), all `streams_ok=1`, POWER=0 throughout. Raw data in
`reports/sweeps/2026-07-30_1b/`.

**2.7B 2-bit B6 @230 — NEW BEST, 29.30 tok/s at b=6** (per-rep 29.25/29.31/29.34):

| b | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|
| decode | 9.85 | 16.68 | 21.64 | 26.33 | 26.29 | **29.30** |

Comparable 2.7B picture (all POWER=0 unless noted):

| build | b | decode |
|---|---|---|
| 2-bit B4 @250 | 4 | 25.64 *(POWER=1; ~26.2 adjusted)* |
| 2-bit B6 @230 | 4 | 26.33 |
| **2-bit B6 @230** | **6** | **29.30** |
| packed-1p6 B4 @230 | 4 | 27.44 |

- **B6 beats B4 by ~+11.8%** at each one's best b — *not* the ~38% claimed below, which was an
  analytical estimate and is off by 3×.
- B6 2-bit at b=6 beats the packed B4 best by **+6.8%**, so the widest array wins even without
  1.6-bit packing.
- At equal b=4 and equal clock, packing is worth **+4.2%** (27.44 vs 26.33) — the two gains are
  independent, so **B6 + 1.6-bit packing projects to ~30.5 tok/s.** That build still does not
  exist and is now clearly the top hardware item.
- CMA: this run is why the raise mattered — the 2-bit B6 preset needs 624 MiB.
- The **b=5 dip reproduces on 2.7B** (26.33 → 26.29 → 29.30), independently confirming the
  `ceil(b/4)` wave effect in §3 on a second model.

**Runtime-batch A/B (370M @250, `_rb` vs config-identical legacy) — works, worth ~0.6%:**

| b | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|
| ctl | 35.77 | 57.24 | 71.27 | 82.61 | 81.57 | 88.28 |
| rb | 35.98 | 57.41 | 70.77 | 82.27 | 82.40 | 88.66 |

Decode deltas are mostly scatter, but the b=1 per-rep ranges don't overlap (rb 35.95–36.00 vs ctl
35.74–35.80), so the win is real. The mechanism is confirmed exactly by the COMPUTE phase, where
the drain lives: **b=1 388.1→379.4 ms (−2.2%, 5 of 6 rows skipped), b=4 −0.4% (2 of 6), b=6
+0.4% = nil (nothing to skip).** A textbook positive verification.

**But the premise below — "b=1 on `_rb` should drop toward B1 cost" — was wrong.** The weight
stream is batch-count-independent, so a B6 bitstream at b=1 was *already* paying ~B1 cost; the
drain was only a small tail. Useful corollary: **no need for separate low-B bitstreams**, one
high-B build already serves low-b decode well. Consider this feature closed.

Also independently reconfirmed the POWER tax: 370M ctl b=6 = 88.28 at POWER=0 vs 86.08 at
POWER=1 (+2.6%).

<details><summary>original plan text (superseded)</summary>

Both built 2026-07-26, in `transfer/presets/` and already on the board.

**Corrections found while prepping this, 2026-07-29:**
- **`k26_mmfree2_7b_a16_b6_dsp_s8_230` is a 2-bit build** — its manifest has
  `MMFREE_PACKED_WEIGHTS=0`. `packedWeights` is an RTL parameter (the `WeightUnpackGearbox` in
  `Core.scala:196` only exists when true) and the runner hard-errors on a blob/bitstream
  mismatch (`main_fpga.cpp:333-343`), so it **cannot** run the `packed-1p6` blob. It will not
  compound with the 1.6-bit gain, contrary to the framing below; its baseline is the 2-bit
  stack (25.02 → 25.83 at b=4), not 27.49. No B6+packed preset exists — that combination is
  the real throughput-max and needs a new `CoreConfig` entry plus a Vivado run.
- **CMA was the binding constraint for that preset — FIXED 2026-07-30 by raising `cma=` to
  1536M** (`cma_boot_raise.sh 1536M`, verified in bootargs; `CmaTotal` 1536, `CmaFree` 1531 on a
  fresh boot). It needs 624 MiB of udmabuf-wt (4 × 0x09c22000); at the old `cma=1000M` only
  ~523 MiB was reachable. Also worth recording: on the fresh 1536M boot, `zocl`/`pynq`/jupyter
  are all still active yet only ~5 MiB of CMA is in use — so the ~477 MiB measured on 07-29 was
  **transient session accumulation, not a boot reservation**. A reboot does return it. It can
  still accumulate within a long session, which is why the pre-flight warning stays.
  `sweep_all.sh` now prints this arithmetic per preset and warns before deploying. The same
  624 MiB *did* allocate on 2026-07-26 (`k26_mmfree2_7b_a16_b4_dsp_s8`), so it is achievable.
  The two 370M presets need only 81 MiB and are safe regardless. Two scripts added 2026-07-29:
  - **`cma_reclaim.sh`** (preferred) — stops jupyter, unloads the `pynq` overlay, drops `zocl`,
    recovering the ~477 MiB. Reversible by rebooting. Deliberately does **not** touch
    `dfx-mgr.service`: `deploy_kria.sh` drives the overlay via `xmutil unloadapp/loadapp`, which
    talks to dfx-mgr, so stopping it would break every deploy. `--report` inspects only.
  - **`cma_boot_raise.sh <size>`** — persistent raise, only needed to exceed 1000 MiB total.
    There is no cmdline.txt and no usable U-Boot env, so it rebuilds `/boot/firmware/
    boot.scr.uimg` (payload = plain text after a 64-byte uImage header **plus** an 8-byte
    length prefix; text starts at byte 73). Edits only the Kria arm — `cma="1000M"` appears
    twice and `test -n $kria` four times, so it anchors on the unique "Configuring the cma
    value" comment. Verifies a byte-exact payload round-trip and a stock-matching header before
    writing, and backs up first. Raising CMA is safe for the 2.95 GB `model.mmfree` because it
    is mmap'd read-only and viewed in place (`weights.cpp:200`) — evictable page cache, not a
    resident allocation, so a bigger CMA slows the CPU-side gate rather than OOMing.
- The runtime-batch **software side has landed** (`mmfree_lib.c:377`, `mmfree_inst_compute_b`
  sets `flags=b`); the memory note calling it pending is stale. `K26_MMFree370M_A16_B6_RB` is
  a bare alias of `K26_MMFree370M_A16_B6` (`CoreConfig.scala:505`) with no config delta, so the
  A/B isolates the RTL exactly. Legacy bitstreams ignore `flags` and fall back to a full-B
  drain, which stays correct, so one software build drives both sides.

| preset | WNS | what it is |
|---|---|---|
| `k26_mmfree2_7b_a16_b6_dsp_s8_230` | +0.118 ns | B6 + DSP + banking @230, **2-bit**. maxN=8192; runs 1.3B **and** 2.7B. |
| `k26_mmfree370m_a16_b6_rb` | +0.048 ns | Config-identical to `k26_mmfree370m_a16_b6` but rebuilt from a tree WITH the runtime-batch RTL. |

CMA was raised to **1536M** on 2026-07-30, so no reclaim step is needed: budget is 1530 MiB vs
the 624 MiB the 2.7B preset wants. Just run:

```
cd ~/transfer && sudo ./sweep_all.sh \
    "k26_mmfree2_7b_a16_b6_dsp_s8_230" \
    "k26_mmfree370m_a16_b6:370m:ctl" \
    "k26_mmfree370m_a16_b6_rb:370m"
```

2.7B goes first (most CMA-hungry, run while memory is least fragmented). The two 370M runs are
adjacent and both at 250 MHz so the runtime-batch A/B sees identical conditions —
`:ctl` tags the control so it does not clobber the 2026-07-26 baseline CSV. `sweep_all.sh`
gates every preset before timing it (`GATE_GEN=8`, trimmed from the default 32 because the CPU
reference makes a 2.7B gate ~10 min).

- **`_rb` needs `MODEL=370m`** (the `:370m` above). Its manifest asks for
  `ridger/MMfreeLM-370M`, which is not provisioned on the board; `models/370m/` is.
- `_rb` vs `k26_mmfree370m_a16_b6` is the **runtime-batch A/B**: on the old bitstream a b<B
  call still drains all 6 rows, so low-b rows are flat. If runtime batch works, b=1 on `_rb`
  should drop toward B1 cost. That has never been demonstrated on hardware.
- B6@230 matters because **the split gain grows with batch** (+0.5% at b=1 → +4.4% at b=4),
  so b=5/b=6 should extend the trend and compound with B6's ~38% throughput edge over B4.
- fclk: `deploy_kria.sh` sets fclk0 from the manifest, so 230 is handled. Don't bypass it.
</details>

---

## 2. Designed, not built

### 2a. swiglu chunk-pairing — DOWNGRADED by 1a's result (2026-07-29)

**Read 1a first.** This item's premise was that the COMPUTE window has leftover capacity to
spend. 1a is the experiment that priced that capacity, and it converted at only ~25%: ~222 ms
of hidden work bought ~56 ms of wall clock. swiglu is 1147 ms, so the same conversion implies
**~+2–3%**, for a much more invasive change (an explicit chunk *schedule* rather than derived
sequential ranges). Prefer 2b (STORE‖COMPUTE) — the cost side of the k-sweep says the tax is
structural (one-op-outstanding), and attacking that raises the ceiling for everything else.
Not dead, just no longer "the biggest remaining software win". Original analysis below.


swiglu is **1170 ms/run** at b=4 (11.1% of decode), the largest CPU vector op, and it is
still fully serialized with the engine.

Blocker: `gate_proj` emits `[B, 2I]` with gate in cols `[0,I)` and up in `[I,2I)`, and swiglu
pairs column `i` with `i+I` — so a contiguous column chunk **cannot** be swiglu'd alone.

Fix: give the split driver an explicit chunk **schedule** instead of deriving sequential
ranges, so the model can request gate-chunk `j` then up-chunk `j` adjacently and fire swiglu
on the pair. `mmfree_split_plan()` already returns an inspectable plan and the blob layout
supports arbitrary tile ranges, so this is a scheduling change, not a data one. Note
`down_proj`/`o_proj` outputs are column-local and would work with the existing sequential
callback today.

Budget check before building: the COMPUTE window at k=4 is ~2571 ms and readback already
consumes ~957 ms of it. Whatever 1a leaves unused is what swiglu can claim.

### 2b. STORE ‖ COMPUTE (RTL) — ~+5%
`m_axis` (STORE) is a **separate physical port** from `s_axis`, so these can overlap. The
only conflict is `outBram` (STORE reads while COMPUTE's drain writes) → needs double
buffering. **Resource-feasible**: packed B4 build is URAM 25% (16/64), LUT 43%, BRAM 40%;
doubling outBram lands ~50% URAM. DSP is the tight resource at 82% and this adds none.

Worth ~486 ms/run (store is 314 ms at k=0, 486 at k=4) ≈ +5% decode at the ~95 ms-per-1%
exchange rate the split established. It also retires the per-chunk store tax, which is half
of what caps chunking.

### 2c. Deeper instruction queue — only bundled with 2b
On its own this does **not** buy concurrency: `AxiInstructionHandler` runs
`idle→decoding→dispatching→awaitingDone→completed` and the Core FSM
(`sIdle/sLoad/sCompute/sStore`) executes one op to completion regardless of how many are
queued. It also misses most of the per-op cost, because **the CPU programs the DMA**
(`mmfree_seq_compute_issue` does 4 `mmfree_dma_mm2s_start()` AXI-Lite writes before pushing
the instruction). Removing that needs descriptor/scatter-gather DMA, not a queue.

Explicitly **not worth doing**: `LOAD ‖ COMPUTE`. `s_axis` carries both activations and
weights, distinguished only by FSM state — one physical port. Would need a second slave port
plus a double-buffered `actBram`, for a phase that is only 3.9% of engine time.

### 2d. Packer per-chunk padding (small, unblocks 370M/1.3B)
1.6-bit chunk boundaries need `t*N*olt` divisible by 5 and beat-aligned; since `olt=256` and
`256%5==1` that means **`N%5==0`**. 2.7B (N=2560) is fine, but 370M/1.3B `gate_proj`
(N=1024/2048) falls back to k=1 on the packed blob, and `down_proj` (N=6912) only allows
boundaries at multiples of 5 tiles. Having the packer emit chunks as independently-padded
entries would unblock splitting on the smaller models. Cost is ≤4 trits + ≤1 beat per chunk.

---

## 3. Open questions

- ~~**370M b=5 anomaly.**~~ **SOLVED 2026-07-30 — host thread scheduling, not a drain path.**
  It reproduces exactly (b=4 80.78, b=5 79.91, b=6 86.08; token latency +13.06 ms at b=5 vs
  +6.4–7.8 elsewhere). `parallel_rows` (`parallel.hpp:22-24`) is
  `#pragma omp parallel for schedule(static)` over rows = **the batch**, on 4 A53 cores, so the
  critical path is `ceil(b/4)` rows. b=5 and b=6 both cost **two waves**; only b=6 has the
  streams to amortize it. Measured b=4→5→6: rmsnorm 66.4→111.8→118.1, swishgate
  75.6→136.2→140.4, scan 20.9→34.5→36.4 — each ~doubles then plateaus, while engine time grows
  smoothly (+2.35→+3.25 ms/step). **Rule: use b=4 or b=6, never b=5.** b=8 would be ideal on a
  4-core host but B8 doesn't fit the K26 (95% LUT). Fixing b=5 needs a bit-exact rows×columns
  work decomposition (a row's reduction is deliberately serial); payoff ~1.5% decode at b=6 plus
  making b=5 usable. See `memory: batch-wave-quantization`.
- **~217 ms of unexplained per-chunk cost** at k=4. The balance sheet closes overall, but
  after accounting store (+171 ms) and pack (+5 ms) there is a residual charged to compute.
  Hypothesis is weight-DMA ramp-up — k separate MM2S transfers instead of one long one, so k
  ramp-ups and possible engine stalls between chunks. **Unmeasured.** Instrumenting the DMA
  start/first-beat latency would confirm or kill it, and it decides whether 2b is worth more
  than the +5% estimated.

---

## 4. Where things stand (measured, for reference)

2.7B packed 1.6-bit @230 MHz, b=4, all gates PASS / all `streams_ok=1`:

| config | decode tok/s |
|---|---|
| 2-bit, no split (session start) | 24.47 |
| 1.6-bit packing | 26.17 |
| + column-tile split (k=4) | **27.32** |
| + dequant hidden | **? — item 1a** |

370M B6 @250 scales 35.5 → 86.1 tok/s over b=1..6. Chunk-count sweep (2.7B packed, b=4):
k=2 → +4.0%, **k=4 → +4.4% (peak)**, k=8 → +1.3%, k=16 → **−2.0%**; benefit saturates as
`(k−1)/k` while cost grows linearly in k.

Tooling built this session: `bench_batch.sh` (batch sweep → CSV), `split_sweep_csv.py`
(wide CSV → tidy summary/ops/phases sheets), `sweep_all.sh`, `sweep_split_ab.sh`,
`sweep_split_k.sh`, `build_queue.sh` (unattended Vivado queue),
`software/integration/tests/` (`make split`). See `memory: batch-sweep-csv-tooling`.
