package systolic

import chisel3._
import chisel3.util._

/**
  * Nearest-neighbour systolic array — the dataflow [[SystolicArray]] is named for
  * but does not implement.
  *
  * ── WHY THIS EXISTS ─────────────────────────────────────────────────────────
  * [[SystolicArray]] instantiates a 2D PE grid, but no PE's input is ever driven
  * by another PE's output: every PE reads the boundary beat and writes straight to
  * the output port. It is an output-stationary BROADCAST MAC array — one activation
  * drives xDim*nLanes PE inputs (256 at the shipped 32x8 geometry), one weight beat
  * drives yDim rows. That single broadcast net is the whole timing story of this
  * project: `actMaxFanout`, `colBankSize` and the maxN=8192 two-path fight are all
  * attempts to beat it from the outside.
  *
  * This module attacks it from the inside. Activations march LEFT->RIGHT and weights
  * march TOP->BOTTOM through one register per PE, so the fanout of every data net in
  * the array is exactly 1 and every wire is a nearest-neighbour hop. The arithmetic
  * that justified building it (shipped 32x8, aWidth=16):
  *
  *     dataflow                 pipeline regs   fanout of one activation net
  *     shipped  colBankSize=4      5 120 bits       32 PE inputs
  *     control  colBankSize=1     20 480 bits        8
  *     true systolic              16 576 bits        1   (8192 pass-through + 8384 skew)
  *
  * i.e. it is CHEAPER in registers than the existing banking mechanism taken to its
  * limit, and it gets fanout to 1.
  *
  * ── SEMANTICS: IDENTICAL TO THE BROADCAST ARRAY ─────────────────────────────
  * Same IO bundle, same output-stationary accumulation, all yDim rows exposed, so it
  * is a drop-in for [[control.Core]] and `SystolicArrayModel.compute` applies
  * UNCHANGED. Every PE still computes
  *     out[row][col*nLanes+lane] = sum_t sign(W_t[col*nLanes+lane]) * A_t[row]
  * The array is only RETIMED — results must be bit-identical, which is what
  * SystolicArraySpec's equivalence tests pin.
  *
  * ── THE SKEW, AND WHY IT IS THE MINIMAL ONE ─────────────────────────────────
  * A PE must see W_t and A_t on the same cycle. Injecting activation row r at
  * PE(r,0) delayed r cycles, it reaches PE(r,c) at t+r+c; injecting weight column c
  * at PE(0,c) delayed c cycles, it reaches PE(r,c) at t+c+r. They meet. Any pair of
  * skews differing by a constant works; (r, c) is the smallest, and the triangular
  * skew buffers are where 8384 of the 16576 bits go.
  *
  * The `valid` for a beat is skewed the same way and rides the activation path, so a
  * GAP in the input stream is carried through the array in lockstep with the data it
  * belongs to: at cycle t the boundary presents (W_t, A_t, v_t), all three chains
  * delay by exactly r+c, so PE(r,c) sees that triple together at t+r+c whether or not
  * the stream was contiguous. The data registers free-run (they shift every cycle);
  * the valid bit — not a shift enable — is what decides whether a PE accumulates.
  *
  * ── nAcc IS STILL BROADCAST, DELIBERATELY ───────────────────────────────────
  * The one net that is not nearest-neighbour. A PE latches nAcc once, on its first
  * valid cycle, and nAccReg is constant for the whole operation, so it is off the
  * accumulate path — but it is still log2Ceil(maxAcc) bits at yDim*xDim fanout.
  * Pipelining it alongside the weights would cost ~9 800 more register bits (13b per
  * PE plus its own triangular skew), which is more than the entire dataflow change,
  * so it is left broadcast until a timing report says it binds. If the worst path in
  * an implementation run lands on nAccReg, that is the next thing to pipeline.
  *
  * ── COST ────────────────────────────────────────────────────────────────────
  * Latency +(xDim-1)+(yDim-1) cycles per operation (`drainCycles`, 38 at 32x8) versus
  * +(numBanks-1) = 7 for colBankSize=4. Against a col-tile pass of N>=2560 that is
  * ~1.5% of COMPUTE cycles, spent to buy fabric clock — a losing trade below a rung
  * step and a clear win at one (250 -> 300 MHz is +20%).
  *
  * ── BACK-TO-BACK ────────────────────────────────────────────────────────────
  * Like [[SystolicArray]], PE.scala leaves accumCounter/accumReg dirty on done->idle,
  * so Core pulses a local reset between col-tile passes. One divergence: the drain
  * here releases straight to idle when the consumer is already ready (see the
  * draining_state transition), which the banked array leaves to that external reset.
  */
class TrueSystolicArray(val aWidth: Int, val maxAcc: Int, val xDim: Int, val yDim: Int,
                        val signedAct: Boolean = false, val accHeadroomBits: Int = -1,
                        val useDspAccum: Boolean = false) extends Module {
    require(aWidth >= 2 && aWidth % 2 == 0, "aWidth must be even and >= 2")
    require(maxAcc >= 2, "maxAcc must be >= 2")
    require(xDim >= 1, "xDim must be >= 1")
    require(yDim >= 1, "yDim must be >= 1")

    // Accumulator width = aWidth + headroom (see PE.scala). Auto (-1) = legacy log2Ceil(maxAcc).
    val accWidth = aWidth + (if (accHeadroomBits < 0) log2Ceil(maxAcc) else accHeadroomBits)

    val input  = IO(new SystolicArrayInputBundle(aWidth, maxAcc, xDim, yDim))
    val output = IO(Decoupled(new SystolicArrayOutputBundle(aWidth, xDim, yDim, accWidth)))

    val nLanes = aWidth / 2

    /** Cycles between the boundary consuming the last beat and the deepest PE —
      * PE(yDim-1, xDim-1) — finishing. Exposed for the spec's drain poll. */
    val drainCycles: Int = (xDim - 1) + (yDim - 1)

    val arr = Seq.tabulate(yDim, xDim) { (_, _) =>
        Module(new PE(aWidth, maxAcc, signedAct, accHeadroomBits, useDspAccum))
    }

    val nAccReg = Reg(UInt(log2Ceil(maxAcc).W))

    // idle -> running (accepting input) -> draining (the wavefront crossing the array,
    // NO new input) -> done. running gates input-accept, done gates completion; the
    // split exists because PE(0,0) swallows the last beat drainCycles before PE(yDim-1,
    // xDim-1) finishes, and a streaming caller would otherwise feed the NEXT operation's
    // beats into the drain and lose them.
    val idle_state :: running_state :: draining_state :: done_state :: Nil = Enum(4)
    val stateReg = RegInit(idle_state)

    // Repack flat external weights as [xDim][nLanes].
    val externalWeightsByCol = VecInit(Seq.tabulate(xDim)(col =>
        VecInit(Seq.tabulate(nLanes)(lane =>
            input.weights_i.bits(col * nLanes + lane)
        ))
    ))

    val startable  = input.weights_i.valid && input.activation_i.valid && input.nAcc.valid
    val isLatching = (stateReg === idle_state) && startable
    val canStep    = input.weights_i.valid && input.activation_i.valid

    // PE(0,0) is the shallowest (skew 0), so it is the input consumer: the moment it
    // finishes it has swallowed all nAcc beats and the array must STOP accepting that
    // same cycle, before the registered running->draining transition lands.
    val inputDone      = arr(0)(0).output.valid
    val stillAccepting = (stateReg === running_state) && !inputDone

    // External handshakes — unchanged from the broadcast array. In running, weights and
    // activations gate each other so both fire together; nAcc rendezvous fires at start.
    input.nAcc.ready         := isLatching
    input.activation_i.ready := isLatching || (stillAccepting && input.weights_i.valid)
    input.weights_i.ready    := isLatching || (stillAccepting && input.activation_i.valid)

    val stage0Valid = isLatching || (stillAccepting && canStep)
    // Deeper PEs start after the isLatching cycle, by which point nAccReg holds the
    // latched value; PE(0,0) needs the live bits on the latching cycle itself.
    val stage0NAcc  = Mux(isLatching, input.nAcc.bits, nAccReg)

    // ── Boundary skew ────────────────────────────────────────────────────────
    // Activation row r enters delayed r cycles; weight column c enters delayed c.
    // Each element feeds exactly ONE destination, so the boundary fanout is 1.
    val actRowSkew   = Seq.tabulate(yDim)(r => ShiftRegister(input.activation_i.bits(r), r))
    val wtColSkew    = Seq.tabulate(xDim)(c => ShiftRegister(externalWeightsByCol(c), c))
    // valid resets to false so the array flushes on reset (incl. Core's arrLocalReset);
    // the wide data registers need no reset because valid gates every accumulation.
    val validRowSkew = Seq.tabulate(yDim)(r => ShiftRegister(stage0Valid, r, false.B, true.B))

    // ── Nearest-neighbour propagation ────────────────────────────────────────
    // act + valid march right (one register per hop), weights march down. Free-running:
    // they shift every cycle, and validWave says which cycles carry a real beat.
    val actWave   = Wire(Vec(yDim, Vec(xDim, UInt(aWidth.W))))
    val validWave = Wire(Vec(yDim, Vec(xDim, Bool())))
    val wtWave    = Wire(Vec(yDim, Vec(xDim, Vec(nLanes, UInt(2.W)))))
    for (row <- 0 until yDim; col <- 0 until xDim) {
        actWave(row)(col)   := (if (col == 0) actRowSkew(row)   else RegNext(actWave(row)(col - 1)))
        validWave(row)(col) := (if (col == 0) validRowSkew(row) else RegNext(validWave(row)(col - 1), false.B))
        wtWave(row)(col)    := (if (row == 0) wtColSkew(col)    else RegNext(wtWave(row - 1)(col)))
    }

    for (row <- 0 until yDim; col <- 0 until xDim) {
        val pe = arr(row)(col)
        pe.input.bits.weights_i    := wtWave(row)(col)
        pe.input.bits.activation_i := actWave(row)(col)
        pe.input.bits.nAcc         := stage0NAcc
        pe.input.valid             := validWave(row)(col)
        pe.output.ready            := output.ready
    }

    // All yDim rows exposed at the boundary.
    for (row <- 0 until yDim; col <- 0 until xDim; lane <- 0 until nLanes) {
        output.bits.accum(row)(col * nLanes + lane) := arr(row)(col).output.bits.accum(lane)
    }

    // The deepest PE gates completion; shallower ones sit in done_state holding their
    // accumulators, which is why no output de-skew buffer is needed.
    val allDone = arr(yDim - 1)(xDim - 1).output.valid
    output.valid := allDone

    switch(stateReg) {
        is(idle_state) {
            when(startable) {
                nAccReg  := input.nAcc.bits
                stateReg := running_state
            }
        }
        is(running_state) {
            when(inputDone) {
                stateReg := (if (drainCycles == 0) done_state else draining_state)
            }
        }
        is(draining_state) {
            // A consumer that is already ready when the last PE lands takes the result
            // on this very cycle (output.valid is combinational on allDone), so the
            // PEs self-clear and there is nothing left for done_state to hand over.
            when(allDone) {
                stateReg := Mux(output.ready, idle_state, done_state)
            }
        }
        is(done_state) {
            when(output.ready) {
                stateReg := idle_state
            }
        }
    }
}
