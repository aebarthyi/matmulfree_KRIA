# Where things stand — 2026-08-09

## 0aa. IN FLIGHT — the banked output memory is being promoted into the shipped presets

Acting on §0a's recommendation (ship the banked memory alone, at 250, for margin not speed).

**Done in RTL/config.** All three deployable presets now carry `outPipeStyle = "banked"` +
`outCascadeHeight = 1`. `CoreConfigSpec` pins it on every deployable preset and pins that the
two maxN=8192 presets agree, because the style and the cascade are both baked into
OutMemUltra's module NAME and `clone_preset_hw.sh` hashes the SV.

**✅ 2.7B REBUILT, 2026-08-09 13:31 — and it reproduces the `_bank` arm exactly.**

    mmfree_2_7b (shipped, was legacy memory)   post-route WNS  +0.002 ns
    mmfree_2_7b_bank (the arm, 2026-08-08)     post-route WNS  +0.020 ns
    mmfree_2_7b (shipped, NOW banked)          post-route WNS  +0.020 ns

Identical to the `_bank` arm not just in WNS but in every resource line — 98 207 LUT, 116 543
registers, 3 795 CARRY8, 71.5 BRAM, 32 URAM, 1 248 DSP — which is the confirmation worth
having: same RTL at the same rung really did produce the same design, so the promotion moved
the config and nothing else. **+0.002 → +0.020 is a 10x margin improvement** on the thinnest
closure anything here has shipped with, for the same URAM count and no read-latency change.

**✅ 1.3B RE-CLONED** from the new 2.7B bitstream (`clone_preset_hw.sh`): RTL sha256 identical
across all 13 SV files, manifest delta confined to the four allow-listed non-RTL keys, overlay
udmabuf-wt patch verified by dtc round-trip. Its timing record is 2.7B's, as always.

**✅ 370M REBUILT, 2026-08-09 17:05 — and banking bought it NOTHING.**

    mmfree_370m_b8 (legacy memory, 2026-08-06)   post-route WNS  +0.011 ns
    mmfree_370m    (banked,        2026-08-09)   post-route WNS  +0.009 ns

Within noise, and if anything slightly worse. **The banked memory is a 2.7B fix, not a
universal one** — quote it as +0.018 ns on the maxN=8192 geometry and ~0 on 370M. That is
consistent rather than surprising: 370M's limiter was never the output memory. Its worst path
is the array's banking pipe into a DSP accumulate (`arr/REG_*` → `accumReg/DSP_OUTPUT/ALU_OUT`,
71.8% route), and the new endpoint distribution says that family is **94.7% of its 5 000
worst endpoints** — the same family that was 24% on 2.7B, here almost the whole population.
370M keeps the banked memory for uniformity (one OutMemUltra variant in the tree, so the
clone check and CoreConfigSpec's invariant stay simple), not for margin.

⚠️ Worth noting for whoever picks up the array work: the one lever that directly targets
370M's dominant family is `trueSystolic`, which deletes those banking registers. That arm has
never been built on the 370M geometry — every systolic build so far was 2.7B.

⚠️ **Not yet on the board.** No gate has been run against any of this. Until it is, the
CLAUDE.md preset table's WNS column still describes the OLD bundles, and the 370M bundle in
`transfer/presets/` IS still the old one.

**Still to do**: 370M build, `make gate` on the board for all three, update the CLAUDE.md
table, and move the systolic array to `exploration` with §0a's result attached + restore
`candidates mustBe empty` (blocked on the 300 MHz arm below, which uses it).

### Candidate 1 (the capture enable) is IMPLEMENTED but unmeasured

`captureMaxFanout` exists as a config knob and is **0 everywhere that ships**, which is a
hard requirement, not a default: at 0 the extra `cCapture` state is not generated at all and
the emitted SV is byte-identical to what the bitstreams were built from (verified by
re-emitting `mmfree_2_7b` over the pre-change emission — all .sv and both manifests identical;
only `component.xml`/`xgui` differ, and those come from the Vivado IP packager, not EmitCore).

Set >0 it does two things at once, because doing either alone reads as no change: the tile
capture is pipelined one cycle into `cCapture` so its enable becomes a plain register, and
that register *and* `drainBusy` — the two CE drivers of the 59 392-bit tileAccums bank —
carry `max_fanout = N`. Cost is +1 cycle per column tile (~0.04% at N>=2560).

Tested in `CoreSpec` ("Core with the pipelined tile capture"): 5 seeds against the reference,
a per-col-tile-distinct case where an early or late load ships a neighbouring tile's numbers,
the drain-still-busy boundary (cmpRows = 2 against a 2-row drain, so the `!drainBusy` gate is
live on every tile), and composition with both the banked memory and the systolic array.

⚠️ It is a placement/fanout effect: OOC synthesis cannot see it and no full build has been run
with it. Its 74.2% share is a share of *failing endpoints at 300 MHz*, and §0a's own verdict
is that 300 is not reachable on this design — so the honest framing is 250 MHz margin work,
not a route to the next rung.

### 0ab. ONE MORE SHOT AT 300: the maximal arm, queued

`mmfree_2_7b_max300` — banked memory + `captureMaxFanout = 128` + `trueSystolic`, at 300 MHz.
Queued behind the shipping build (both need `build/t_matmul`, and two `make build` runs cannot
overlap); log in `reports/build_queue/mmfree_2_7b_max300.log`.

**It stacks three levers on purpose, which is the opposite of every other arm here.** 300 is a
congestion wall, not a path — 94 071 failing endpoints, level 5-6, 86% LUT, 100% DSP — so the
question worth one more build is *is 300 reachable at all*, not *what is lever X worth*. The
stack covers 98% of the failing population:

    core/tileAccums_reg[*]              74.2%   <- captureMaxFanout (pipelined enable + replication)
    arr/*/accumReg/DSP_OUTPUT/CEP       24.0%   <- trueSystolic DELETES the colBankSize pipe regs
    arr/*/nAccReg_reg[*]                 0.6%       driving this family
    core/wtGearbox/buf_reg[*]            0.1%

**Read the result as a bound, not an attribution.** If it fails P&R, 300 is settled on this
design and no decomposition is needed — the remaining 2% cannot carry 0.63 ns. Only if it
closes is it worth the build time to split the levers apart and find out which one paid.

`captureMaxFanout = 128` is a first shot, not a tuned value (≈464 replicas of the enable,
~464 flops against 116 469 used). If the arm lands CLOSE rather than clearly failing, sweep
64/256 before concluding anything. Verified in the emitted SV that the attribute lands on
BOTH CE drivers — `(* max_fanout = 128 *)` on `capturePend` and on `drainBusy` — since
replicating one and leaving the other is how this fix reads as no change at all.

Prior expectation, unchanged by anything above: the failing population sits at 0.02–0.035 ns
slack at 250, i.e. roughly **−0.63 ns at 300**. Recovering that globally from fanout
replication and one dataflow swap would be surprising. This arm is worth its build time
because it is cheap to queue and it ENDS the question either way — not because it is likely
to close.

### ❌/⚠️ RESULT 2026-08-09: still fails 300 — but the wall it fails against is a different one

    arm                   WNS       TNS       failing / total     Fmax     LUT      Reg
    mmfree_2_7b_300b   -0.268  -13241.762    94 071 / 370 050    ~276   100 930  116 469
    mmfree_2_7b_max300 -0.310    -798.596     9 113 / 377 400    274.5   99 216  123 278

**The levers did exactly what the endpoint distribution said they would, and it was not
enough.** Failing endpoints fell **90%**, TNS fell **94%** — and WNS did not move (it is
0.042 ns worse, which at 84% LUT / 100% DSP is placement noise). The `tileAccums` family that
was 74.2% of the problem does not appear anywhere in the worst 30 paths. LUT went DOWN 1 714
despite adding ~464 enable replicas, because the systolic dataflow deletes the colBankSize
banking logic; registers went up 6 809 (skew + replicas), as predicted.

**So the "this settles it" framing was wrong, and should not be quoted.** The prediction that
it would fail held, but the REASON given for it — a global congestion wall the remaining 2%
could not carry — did not. The design is no longer congestion-bound in the same way; it is
bound by a much smaller, sharper population. What survives the worst-30:

    core/wtGearbox/buf_0_reg[*]        <- core/state_reg[*]           67-81% route, 5-6 levels
    arr/*/accumReg_*/DSP_OUTPUT/RSTP   <- cState_reg[2]_replica_9     87-90% route, 2 levels
    core/actMem/mem_reg_bram_*/ADDRB   <- state_reg[0]_replica_16     81% route,   3 levels
    arr/*/nAccReg_reg[*]               <- state_reg[0]_replica_17     78% route,   5 levels

**Every one of them is the same shape as the two that were just fixed: one control net off
the FSM, fanning out across the array footprint, dominated by route.** `wtGearbox/buf_0` is
the gearbox flush/enable (~1 150 registers); the DSP `RSTP` family is `arrLocalReset` reaching
all 1 248 DSP reset pins at 2 logic levels and 90% route — a pure fanout/placement path with
no logic to pipeline away. The previously-0.1% gearbox family is now the headline, exactly as
the "always take the distribution, not the headline path" rule predicts when the big families
are removed.

The honest read is a trajectory, not a wall: three iterations in, each fix works on its own
family and uncovers the next one of the same kind. But **Fmax is unchanged at ~274.5 MHz**,
and PL0_REF has no rung between 250 and 300, so every one of these iterations has bought a
deployable clock of exactly zero — and will keep doing so until ALL of them clear at once.

⚠️ **The congestion numbers for this arm are gone.** `build_all.sh` uses one shared Vivado
project dir and the queued 370M build clobbered `impl_1/runme.log` before it was read, so
whether congestion actually improved (the thing the TNS collapse implies) cannot be checked.
This was the SECOND time it cost a measurement — §0a lost a routed DCP the same way.

### ✅ FIXED so it cannot happen a third time (`scripts/build_project.tcl`)

The analysis now runs while the routed design is still in memory, and lands in `reports/`
alongside the other per-preset artefacts:

* `<tag>.endpoints.txt` — **the endpoint-family distribution**, the artefact this section
  needed twice and had to hand-derive both times. The 5 000 worst endpoints, bucketed by name
  with bit indices and numeric suffixes collapsed (`arr_1_16` → `arr_*_*`, `_replica_9` →
  `_replica_*`), each row carrying its count, share, worst slack and — the field that picks
  the lever — **the driver family**. Header states the cap, because a capped query is a
  censored sample: if COUNT sums to 5 000 only the SHAPE of the ranking is trustworthy.
* `<tag>.congestion.rpt` — `report_design_analysis -congestion -complexity`.
* `<tag>.routed.dcp` — archived **when timing FAILS** (or `ARCHIVE_DCP=1`), which is the only
  case anybody re-opens one. 104 MB a copy, so it is not written for passing builds.

Validated before shipping, not after: the bucketing was run standalone against the real
max300 pin names (it reproduces the four families above), then the whole block was executed
in `vivado -mode batch` against the 370M routed checkpoint. That check is what produced the
94.7% figure quoted above — the tooling paid for itself before its first real build.

## 0a. THE TRUE SYSTOLIC ARRAY IS BUILT — and 300 MHz is blocked by something else

Executed 2026-08-08, against §1's plan below (which is now history; read it for the design
reasoning, not for what to do next).

**Built and green.** `systolic.TrueSystolicArray` — activations march right, weights march
down, one register per PE, triangular (r, c) input skew, every data net inside the array at
fanout 1. `SystolicArray.scala` was NOT touched and the shipped 2.7B RTL re-emits
**byte-identical across all 13 .sv files**, so `clone_preset_hw.sh` still accepts the 1.3B
clone. Whole suite green (137/137), including the tests that matter: same-stimulus
equivalence against the broadcast array on four geometries, stalled-stream tests on BOTH
arrays (skew is exactly what could mis-pair a weight beat with the wrong activation), a drain
test that the array stops accepting the cycle PE(0,0) finishes, and a Core-level block that
puts the 38-cycle wavefront through Core's capture → arrLocalReset → next-tile sequence.

### THE HEADLINE: the array is not what stops 300 MHz. The output memory is.

Five OOC synthesis arms on the 2.7B geometry (`build/vivado-ooc/<preset>@<mhz>/`):

    arm                          clk    WNS      TNS     failing   worst path THROUGH the array
    mmfree_2_7b (shipped, cb4)   300  -0.315  -13.078      115          +1.005
    mmfree_2_7b_cb1  (control)   300  -0.315  -13.078      115          +1.111
    mmfree_2_7b_sys  (variant)   300  -0.315  -13.078      115          +1.163
    mmfree_2_7b_300     (+c2)    300  -0.287  -17.108      116          +1.002
    mmfree_2_7b_sys_300 (+c2)    300  -0.287  -17.108      116          +1.179
    mmfree_2_7b (shipped, cb4)   250  +0.352    0.000        0

Three structurally different dataflows produced **identical WNS to three decimals**, because
the top 40 failing paths in every arm are one family and it is not the array:

    core/mem/mem_reg_uram_4/CLK -> ... -> core/mem/rpipe_reg[1][18]/D
    3.569 ns data path, 81.7% LOGIC, 5 levels (URAM288 x3 + LUT5 x2)

That is OutMemUltra's URAM288 read cascade — the output memory. Meanwhile the worst path
*inside* the array had **over a nanosecond of slack in every arm**.

The dataflow ranking is real and in the predicted direction (+0.158 ns for systolic over
shipped), it just does not matter yet.

### ✅ 300 MHz MEETS IN SYNTHESIS — the URAM was never pipelined at all

The section below is the state BEFORE the output memory was actually looked at; it is kept
because its calibration number is still what post-route has to be judged against. What
changed: Vivado's URAM mapping report for the SHIPPED build says, and always said,

    WARNING [Synth 8-6057] Memory "mem_reg" ... implemented as Ultra-Ram
                           has no pipeline registers.
    FF provided for Pipeline: 0      FF absorbed: 0

`OutMemUltra` hands the tool FOUR read-pipeline stages and Vivado takes **none** of them.
The module's central design claim — that owning the module is what lets those registers pack
into the URAM cascade — has never held, and the read has been one combinational run from a
URAM's CLK through the whole CAS_IN_DOUT → CAS_OUT_DOUT chain and the group mux.

Three fixes tried, at 300 MHz, shipped and systolic dataflow each (identical results, so one
column):

    outPipeStyle / cascade            WNS      TNS    fail   what happened
    legacy, cascade 4  (shipped)   -0.315  -13.078     115   the baseline
    legacy, cascade 2              -0.287  -17.108     116   +0.028 ns; chasing the symptom
    absorb (UG901 template)        -0.620  -48.448     116   WORSE — see below
    banked, cascade 1              +0.753    0.000       0   ✅ MEETS, 0 failing endpoints

**"absorb" is an instructive negative.** Rewriting to the UG901 single-always-block template
changed nothing about absorption (still `FF provided: 0`) and cost 0.3 ns, because dropping
`shreg_extract="no"` let Vivado extract the read pipeline into SRL16Es — the failing endpoint
is literally `rpipe_reg[1][18]_srl2/D`. That attribute is load-bearing exactly as the
original comment said, and coding style was never the blocker: **Vivado will not put these
registers in the cascade, whatever you write.**

**"banked" stops asking and places them.** The address space is split into one explicit
sub-array per URAM chain (16 banks x 4096 at the 2.7B geometry, `cascade_height=1` so there
is no chain left), every bank output is registered, and the mux over REGISTERED outputs gets
its own cycle. Vivado maps it as 16 independent `4K x 116` memories, **Matrix Shape 1x1**.

    worst path, banked:  bank[3].mem_reg_uram_0/CLK -> mux_q_reg[0]/D
                         2.501 ns, 3 levels (LUT6 + MUXF7 + MUXF8)   vs 3.333 ns available

And it is close to free:

    arm                     LUT     Reg   BRAM  URAM   DSP
    mmfree_2_7b           81899   94363     29    32  1248
    mmfree_2_7b_300b      82139   94367     29    32  1248     +240 LUT, +4 reg, same URAM

Read latency is UNCHANGED at 4 — the two structural stages replace two existing pass-through
stages — so Core's `outReadLat`, primeCtr and drain-flush accounting are untouched and no
cycles were traded away after all. The registers barely moving (+4) is the tell that Vivado
absorbed the per-bank output flops into the URAMs' own output registers: the primitive did
get pipelined harder, once the structure let it.

⚠️ Two caveats before anyone quotes 300 MHz. This is POST-SYNTH; the calibration below says
this design pays ~0.350 ns going to routing, which would leave ~+0.40 ns — real margin, but
a place-and-route build is the only proof, and one is queued (`mmfree_2_7b_300b`). And the
array STILL is not the binding path even now (both dataflows read +0.753), so whether the
systolic dataflow matters at 300 is a question only P&R can answer.

⚠️ `bankDepth = 1` (one row per bank) produced a malformed `raddr[-1:0]` slice and failed to
compile under Verilator — caught by the CoreSpec bank-crossing test, which uses exactly that
degenerate config on purpose. Fixed; the production geometry (bankAw=12) was never affected
and the +0.753 result stands against unchanged SV.

### The state before the output memory was looked at — the calibration still applies

`outCascadeHeight` is now a config knob (mirroring `actCascadeHeight`, same idea, same
reason: bound a hard-block memory cascade). Shortening it 4 → 2 bought only **+0.028 ns** and
made TNS worse: the chain collapses to one URAM but the group mux grows from 2 to 4 LUT
levels, and URAM288 CLK→DOUT is ~1.837 ns on its own — 55% of a 3.333 ns period before any
mux or route. Shortening the chain further cannot pay for itself.

And the OOC numbers are optimistic. The calibration point:

    shipped mmfree_2_7b, post-SYNTH @250 : WNS +0.352, zero failing endpoints
    shipped mmfree_2_7b, post-ROUTE @250 : WNS +0.002   (the shipped record)
    => the synth->route penalty on this design is ~0.350 ns

Apply that to -0.287 and a real 300 MHz build lands near **-0.64 ns**, i.e. ~3.97 ns ≈ 252
MHz — which is exactly where the design already is. **300 MHz is roughly a 20% timing ask,
not a margin ask, and no array dataflow closes it.** Reaching it needs the output memory
restructured (explicit banks, each registered, muxed *after* a flop) — a second project, and
one whose payoff is capped by §1's own argument: decode is weight-bandwidth-bound and the
200→250 rung measured ~5%.

⚠️ The one thing this does NOT settle: post-synth uses ESTIMATED routing, and route delay is
precisely where the broadcast array is weak (74% route over 3 levels on the shipped B6
build). So the +1.005/+1.163 array ranking understates the systolic gain, and the 0.350 ns
route penalty was measured on a build whose route-bound path IS the broadcast the systolic
array deletes. Only place-and-route can price that.

### ✅ THE SYSTOLIC ARM'S POST-ROUTE NUMBER AT 250: +0.010 ns. It bought 8 picoseconds.

`make build PRESET=mmfree_2_7b_sys` closed 2026-08-08 (bundle in `transfer/presets/mmfree_2_7b_sys/`):

    shipped broadcast, cb4   post-route WNS  +0.002 ns
    true systolic            post-route WNS  +0.010 ns      +0.008 ns

That is inside placement-seed noise. On the throughput question the array is a **clean
negative result**: correct, cheaper in fanout, and worth nothing on this design — the
broadcast was not the binding constraint post-route either. What actually moved the clock was
the output memory (+1.068 ns, above).

**But the worst path says exactly why, and it is not "the idea was wrong":**

    cState_reg[1]_replica_5/C -> core/arr/arr_5_2/nAccReg_reg[12]/D
    3.910 ns, 80.6% ROUTE, 5 levels

That is the **nAcc broadcast** — the one net TrueSystolicArray deliberately left at fanout 256
(see its header: "If the worst path in an implementation run lands on nAccReg, that is the
next thing to pipeline"). The systolic dataflow removed the activation broadcast and the
limiter moved straight to the other 256-fanout net, so the measured +0.008 ns is the value of
fixing one of the two, not the value of the dataflow. Pipelining nAcc alongside the weight
wave costs ~9 800 register bits, which was judged not worth spending on speculation — it now
has a measurement behind it, and it is the cheapest remaining array lever.

### ❌ 300 MHz FAILS PLACE-AND-ROUTE: -0.268 ns, 94 071 failing endpoints. Stop here.

`mmfree_2_7b_300b` (banked memory, shipped dataflow, 300 MHz) built 2026-08-08 19:35:

    post-synth   WNS +0.753   TNS       0.000   failing        0
    post-route   WNS -0.268   TNS -13241.762   failing   94 071  of 370 050

Routing cost **1.02 ns**, three times the 0.350 ns the shipped 250 build pays, and a QUARTER
of the design's endpoints fail. This is not a critical path any more — it is a global
congestion wall at 86% LUT and 100% DSP:

    LUT 100 930 / 117 120 (86%)   Reg 116 469   BRAM 71.5   URAM 32   DSP 1248 / 1248 (100%)

The worst path has moved off the memory entirely — banking worked, the URAM is gone from the
report — and onto the packed-weight gearbox:

    state_reg[0]_replica_1/C -> core/wtGearbox/buf_0_reg[1061]/D
    3.639 ns, 77.4% ROUTE, 6 levels

but with 94k failing endpoints, fixing that one buys nothing. **Post-route the design reaches
~276 MHz** (3.333 + 0.285), and PL0_REF has no rung between 250 and 300, so 276 MHz snaps
DOWN to 250 and is worth exactly zero deployable clock.

**Conclusion: 300 MHz is not reachable by fixing paths on this design.** It needs less
congestion — a smaller array (B6), floorplanning, or fewer DSP spills — and the payoff would
be the ~5% that the 200→250 rung measured, against a decode that is weight-bandwidth-bound.
That is a bad trade. Do not spend more on 300.

### 📊 WHAT ACTUALLY FAILS AT 300 — the worst path was a red herring

⚠️ `build_all.sh` uses ONE shared Vivado project dir (`build/t_matmul`) and archives no
per-preset impl reports, so the 250 build overwrote the 300 build's routed DCP two hours
later. Recovered by exploiting the fact that `mmfree_2_7b_bank` and `mmfree_2_7b_300b` are
**identical RTL** differing only in `plClkMhz`: the surviving 250 routed checkpoint is a
faithful stand-in, and 1/250 - 1/300 = 0.667 ns, so any endpoint with slack < 0.667 ns at 250
is one that fails at 300. **Archive impl reports per preset** — this was luck, not design.

The 40 000 worst such endpoints (the query cap; the 300 build reported 94 071):

    ENDPOINT FAMILY                          COUNT   SHARE   WORST  AVG_DLY   driven by
    core/tileAccums_reg[*]                   29684   74.2%   0.035    3.566   core/state_reg[*]
    arr/arr_R_C/accumReg_reg/DSP_OUTPUT/CEP   9612   24.0%   0.034    3.749   arr/REG_r_c (bank pipe)
    arr/arr_R_C/accumReg_reg[*] (spilled)      397    1.0%   0.020    3.672
    arr/arr_R_C/nAccReg_reg[*]                 248    0.6%   0.022    3.719
    core/wtGearbox/buf_reg[*]                   54    0.1%   0.055    3.722
    axi_dma_*/DATAMOVER                          3    0.0%   0.137    3.003

**The `wtGearbox` path reported earlier as "what binds now" is 54 endpoints — 0.1%.** It was
the single worst path and it is statistically irrelevant; fixing it would move WNS and nothing
else. Always take the distribution, not the headline path.

**74% of the problem is ONE CONTROL NET.** `core/state_reg[*]` drives 29 681 of the 29 684
tileAccums endpoints. That is the `capture` enable in Core's cStream state —

    when(capture) { tileAccums := arrOutput.bits.accum.asTypeOf(tileAccums) }

— a combinational term off the FSM fanning out to the enable pin of every flop in a
yDim x outLanesPerTile x tileAccWidth = 8 x 256 x 29 = **59 392-bit** register bank spread
across the whole array footprint. It is a high-fanout ENABLE problem, not a wide-datapath
problem, which makes it far more tractable than it looks.

The other 24% is the array's DSP accumulate path — `arr/REG_r_c` (the colBankSize=4 pipeline
registers) and `armSlice_s/ram` into the DSP48 CE/data pins.

Congestion confirms it is global, not local: post-place WNS was **-1.089 ns** and the whole
AggressiveExplore phys_opt + route flow clawed back only 0.82 ns.

    Route 35-448: Global/Short routing congestion level 5 (32x32)
    Route 35-581: Timing congestion level 6 (64x64)
    LONG congestion 64x64 over 17-25% of tiles (north/south); 72 142 initial node overlaps

### 🔧 OPTIMIZATION CANDIDATES, ranked by share of the failing population

1. **`max_fanout` on the tileAccums capture enable — 74% of endpoints, ~1 line.**
   The machinery already exists: `actMaxFanout` annotates a register with
   `max_fanout = N` (Core.scala) for exactly this reason on the activation broadcast. Applying
   the same to the capture enable lets synthesis replicate the driver so each replica enables a
   local slice of tileAccums. **Zero functional change**, so no re-validation beyond the gate.
   Cannot be evaluated by OOC synth — it is a placement/fanout effect and needs a full build.

2. **Pipeline the capture by one cycle — same 74%, still cheap.**
   `RegNext(capture)` with the array's `output.ready` deferred a cycle. Safe because PEs HOLD
   their accumulators in done_state until ready, so the data is stable; costs 1 cycle per col
   tile (~0.04% at N>=2560). Do this if (1) alone is not enough.

3. **Trim `accHeadroomBits` — shrinks the 74% family by 17%.**
   accWidth is 16 + log2Ceil(8192) = 29 bits, so tileAccums is 59 392 bits. At
   accHeadroomBits = 8 it is 49 152, and outBram and the spilled CARRY8 chains shrink with it.
   Already a documented lever. ⚠️ Overflow correctness becomes the model's problem — needs the
   real activation dynamic range measured first, and a gate run.

4. **The systolic array + banked memory together — addresses the 24% family.**
   The 24% is driven by the `colBankSize=4` pipeline registers, which is exactly what
   TrueSystolicArray replaces. Its own 250 build put the limiter on nAccReg (0.6% here), so
   the two fixes are complementary and the combination is the one arm never built.

5. **Pipeline nAcc — 0.6%.** ~9 800 register bits for a small share. Only worth it after 1-4.

6. **Ignore the wtGearbox path.** 0.1%.

**Expected value, honestly.** The failing population sits at ~0.02-0.035 ns slack at 250,
i.e. roughly **-0.63 ns at 300**, across 94 071 endpoints, at congestion level 5-6 and 86%
LUT / 100% DSP. Item 1 is worth trying because it is nearly free and covers three quarters of
the population, but recovering 0.63 ns globally from fanout replication alone would be
surprising. The realistic outcome is more 250 MHz margin, not a 300 MHz closure — and the
prize at 300 is the ~5% the 200->250 rung measured, on a decode that is weight-bandwidth-bound.
Treat 1-3 as margin/de-risking work whose upside is robustness, and only chase 300 if
something else forces the clock up.

### ✅ BANKED @250 CLOSED: +0.020 ns, 0 failing endpoints — 10x the shipped margin

`mmfree_2_7b_bank` built 2026-08-08 21:31. The three 250 MHz arms, all closed, same flow:

    arm                              post-route WNS   worst path is now
    mmfree_2_7b      (shipped)            +0.002      activation broadcast (route-bound)
    mmfree_2_7b_sys  (systolic)           +0.010      nAcc broadcast          80.6% route
    mmfree_2_7b_bank (banked URAM)        +0.020      array banking pipe ->   79.6% route
                                                      spilled fabric accumulator

**The two fixes attack different limiters, and each one exposes the next.** Banking the memory
moved the worst path INTO the array —

    arr/REG_10_27_6_reg[1]__0_rep/C -> arr/arr_7_27/accumReg_6_reg[28]/D
    3.761 ns, 79.6% ROUTE, 5 levels (CARRY8 x4 + LUT4)

that is a colBankSize=4 pipeline register feeding one of the ~800 lane accumulators that
spilled out of the DSPs into CARRY8 fabric. It is precisely the path TrueSystolicArray was
built to delete. So systolic+banked together is the one combination not yet measured, and its
remaining limiter would be the nAcc broadcast.

**But every one of these is worth ~0.01-0.02 ns, and that is the real finding.** At 250 this
design is bound by DISTRIBUTED ROUTING at 86% LUT / 100% DSP, not by any single path: fix the
memory and the array binds, fix the array and nAcc binds, each swap worth picoseconds. The
one lever that moved a full nanosecond (banking, +1.068 ns post-synth) gave it all back to
the router. There is no path-level fix that reaches the next rung.

**Recommendation.** Ship-candidate: `mmfree_2_7b_bank` alone. It turns the +0.002 ns closure —
the thinnest anything here has shipped with, never seed-swept — into +0.020, a 10x margin
improvement, for +240 LUT, +4 registers, the same 32 URAMs, no read-latency change, and no
host or blob change. That is the whole case: seed robustness, not speed.

Do NOT ship the systolic array. It costs ~6 400 registers and ~1 400 LUTs to buy +0.008 ns
that a rung cannot use. Its value was diagnostic and it delivered that: it proved the
activation broadcast — the thing `actMaxFanout`, `colBankSize` and the maxN=8192 two-path
fight were all built around — is worth 8 picoseconds once fixed. Move it to `exploration`
with this result attached, and restore `candidates mustBe empty`.

### Superseded plan: keep the banked memory at 250 as a de-risking change

The URAM fix is real and independent of the 300 MHz question. It removed a genuine 82%-logic
wall that no placement could have fixed, for +240 LUT, +4 registers, the same 32 URAMs and NO
change in read latency. Its value now is margin, not speed: the shipped 2.7B closes 250 at
**+0.002 ns**, the thinnest anything here has ever shipped with and explicitly never
seed-swept (see the ⚠️ in CLAUDE.md). A build of `mmfree_2_7b_bank` (banked, 250 MHz, shipped
dataflow) was launched 2026-08-08 19:37 to measure exactly that. If it turns +0.002 into real
headroom, it is worth shipping on those grounds alone — it retires the standing risk that a
different placement seed misses.

⚠️ It changes OutMemUltra's module name, so it is NOT bit-compatible with the current
bundles: promoting it means rebuilding 2.7B and re-cloning 1.3B, and re-running the gate.

### Superseded: the 300 MHz banked place-and-route build

`mmfree_2_7b_300b` — shipped dataflow, banked output memory, 300 MHz — started 2026-08-08
after the systolic build freed the shared Vivado project dir (`build/t_matmul`; two
`make build` runs CANNOT overlap). Post-synth said +0.753 with zero failing endpoints, and the
calibration says ~0.350 ns goes to routing, so the expectation is roughly +0.40 ns. That
build is the proof-or-not for 300 MHz.

If it closes, the follow-up ordering is: (1) 300 MHz is a memory fix, not an array fix — ship
the banked memory; (2) re-run the systolic arm at 300 with nAcc pipelined, since at 300 the
array has less room to spare than it did at 250 and the nAcc net is now the known limiter.

### Superseded: the post-route WNS of the systolic arm at 250

A full `make build PRESET=mmfree_2_7b_sys` was launched 2026-08-08. Compare its post-route
WNS against the shipped **+0.002 ns** at the same rung. That single number is the whole
result: it says how much of the 0.64 ns gap the dataflow actually buys once routing is real,
and it is the only measurement that can rescue or bury the 300 MHz idea.

* If it lands well above +0.002 → the dataflow does buy route headroom; the next lever is the
  output memory, and 300 becomes a two-part project with a known cost.
* If it lands near +0.002 → the broadcast was never the binding constraint post-route either,
  and the systolic array is a clean negative result: correct, cheaper in fanout, and worth
  nothing on this design. Move it to `exploration` and restore `candidates mustBe empty`.

Do NOT bench tok/s to decide this. Decode is bandwidth-bound; §1 predicted throughput would
not move and nothing here changes that. `bl_compute_us_call` is the only phase that could,
and the systolic drain costs +38 cycles/op (~1.5% of a col-tile pass at N≥2560) against
banking's +7, so a small COMPUTE regression is the expected outcome, not a bug.

### Housekeeping from the same session

* **`make build` was broken for everyone** — `scripts/build_all.sh:171` read `$STAGE_ONLY`
  under `set -euo pipefail` with no default, so any invocation that did not export it died
  with "unbound variable" before Vivado started. Fixed to `${STAGE_ONLY:-0}`.
* **`CoreConfig.buildCore`/`buildTop` now pass named arguments.** Core and CoreTop had each
  grown their own parameter tail, the two orders no longer matched, and the positional lists
  silently bound a Boolean to an Int the moment a parameter was added. Caught at compile
  time here; it would not have been if the types had happened to line up.
* **`cmpRows = 1` hangs the engine, on BOTH arrays.** PE.scala increments accumCounter on the
  idle→running edge, so `accumCounter.value === nAccReg` is first tested at counter=1 against
  nAcc-1 = 0 and only matches after the counter wraps at maxAcc. Pre-existing, unreachable in
  practice (real projections have N ≥ 1024), documented in CoreSpec. n=2 is the smallest
  COMPUTE the PE supports.
* `candidates` is no longer empty, so `CoreConfigSpec`'s `candidates mustBe empty` was
  relaxed to what it was protecting (disjoint from deployable, every buildable name
  resolves). **Restore it when the systolic arm closes.**

## 0. THE DATA SET IS COMPLETE — 2026-08-07. Analysis, not collection.

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
