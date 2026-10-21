package systolic

import chisel3._
import chisel3.util._

class SystolicArrayInputBundle(val aWidth: Int, val maxAcc: Int, val xDim: Int, val yDim: Int) extends Bundle {
    // One weight beat per cycle: xDim columns × nLanes lanes of ternary weights.
    val weights_i    = Flipped(Decoupled(Vec((aWidth / 2) * xDim, UInt(2.W))))
    // One activation beat per cycle: yDim activations (one per row / batch item).
    val activation_i = Flipped(Decoupled(Vec(yDim, UInt(aWidth.W))))
    // One-shot rendezvous: number of accumulation cycles for this operation.
    val nAcc         = Flipped(Decoupled(UInt(log2Ceil(maxAcc).W)))
}

class SystolicArrayOutputBundle(val aWidth: Int, val xDim: Int, val yDim: Int, val accWidth: Int) extends Bundle {
    // Per row, one accumulator per (col, lane). All yDim rows are exposed.
    val accum = Vec(yDim, Vec((aWidth / 2) * xDim, UInt(accWidth.W)))
}

/**
  * 2D array of ternary-MAC PEs. yDim rows = batch items (one activation per row per
  * cycle); xDim columns consume one full weight beat (xDim·nLanes weights) per cycle:
  *     O[row][col·nLanes + lane] = sum_t W[t][col·nLanes + lane] * A[row][t]
  * `weights_i` and `activation_i` must both be valid for the array to step; `nAcc`
  * is consumed once in the initial three-way rendezvous.
  *
  * Column banking (`colBankSize`): columns are split into banks and bank `b` sees the
  * WHOLE input beat (weights, activation, valid, nAcc) delayed by `b` cycles, cutting
  * the activation fanout from xDim to colBankSize columns. Delaying the whole beat keeps
  * results numerically identical; PEs hold their results in done_state, so no output
  * de-skew is needed and `output.valid` follows the deepest bank (+numBanks-1 cycles).
  * colBankSize <= 0 or >= xDim is a single bank: the plain broadcast array.
  */
class SystolicArray(val aWidth: Int, val maxAcc: Int, val xDim: Int, val yDim: Int,
                    val signedAct: Boolean = false,
                    val useDspAccum: Boolean = false, val colBankSize: Int = 0) extends Module {
    require(aWidth >= 2 && aWidth % 2 == 0, "aWidth must be even and >= 2")
    require(maxAcc >= 2, "maxAcc must be >= 2")
    require(xDim >= 1, "xDim must be >= 1")
    require(yDim >= 1, "yDim must be >= 1")
    require(colBankSize >= 0, s"colBankSize=$colBankSize must be >= 0 (0 = no banking)")

    // Accumulator width = aWidth + headroom (see PE.scala).
    val accWidth = aWidth + log2Ceil(maxAcc)

    val input  = IO(new SystolicArrayInputBundle(aWidth, maxAcc, xDim, yDim))
    val output = IO(Decoupled(new SystolicArrayOutputBundle(aWidth, xDim, yDim, accWidth)))

    val nLanes = aWidth / 2

    // Number of banks and each column's bank index (= its pipeline delay).
    val numBanks: Int = if (colBankSize <= 0 || colBankSize >= xDim) 1
                        else (xDim + colBankSize - 1) / colBankSize
    def bankOf(col: Int): Int = if (numBanks == 1) 0 else math.min(col / colBankSize, numBanks - 1)
    // Completion comes from a deepest-bank PE (col xDim-1); unbanked keeps col 0 so
    // its emitted SV is unchanged.
    private val doneCol = if (numBanks == 1) 0 else xDim - 1

    val arr = Seq.tabulate(yDim, xDim) { (_, _) => Module(new PE(aWidth, maxAcc, signedAct, useDspAccum)) }

    val nAccReg = Reg(UInt(log2Ceil(maxAcc).W))

    // idle → running (accepting input) → draining (banked only: deeper banks finishing,
    // NO new input, else a streaming caller's next-op beats would be swallowed) → done.
    // Unbanked keeps a 3-state enum so its FSM encoding and emitted SV are unchanged.
    val idle_state :: running_state :: fsmRest = Enum(if (numBanks == 1) 3 else 4)
    val done_state      = fsmRest.last
    val draining_state  = if (numBanks == 1) done_state else fsmRest.head
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

    // Bank 0 has consumed all nAcc beats the cycle it finishes, so (banked) input must
    // stop that same cycle, before the registered running→draining transition lands.
    // Unbanked keeps the plain `running` gate (Core's !outPending covers that case).
    val inputDone     = arr(0)(0).output.valid
    val stillAccepting = if (numBanks == 1) (stateReg === running_state)
                         else (stateReg === running_state) && !inputDone

    // In running, weights and activations gate each other so both fire together;
    // nAcc fires only at the start.
    input.nAcc.ready         := isLatching
    input.activation_i.ready := isLatching ||
                                (stillAccepting && input.weights_i.valid)
    input.weights_i.ready    := isLatching ||
                                (stillAccepting && input.activation_i.valid)

    // ── Per-bank input pipeline ──────────────────────────────────────────────
    // Stage d feeds bank d: stage 0 is the live boundary beat, stage d a register of d-1.
    // Only valid is reset (incl. by arrLocalReset); it gates every PE's accumulation.
    val stage0Valid   = isLatching || (stillAccepting && canStep)
    val stage0NAcc    = Mux(isLatching, input.nAcc.bits, nAccReg)

    val validPipe   = Wire(Vec(numBanks, Bool()))
    val nAccPipe    = Wire(Vec(numBanks, UInt(log2Ceil(maxAcc).W)))
    val weightsPipe = Wire(Vec(numBanks, Vec(xDim, Vec(nLanes, UInt(2.W)))))
    val actPipe     = Wire(Vec(numBanks, Vec(yDim, UInt(aWidth.W))))

    validPipe(0)   := stage0Valid
    nAccPipe(0)    := stage0NAcc
    weightsPipe(0) := externalWeightsByCol
    actPipe(0)     := input.activation_i.bits
    for (d <- 1 until numBanks) {
        validPipe(d)   := RegNext(validPipe(d - 1), false.B)
        nAccPipe(d)    := RegNext(nAccPipe(d - 1))
        weightsPipe(d) := RegNext(weightsPipe(d - 1))
        actPipe(d)     := RegNext(actPipe(d - 1))
    }

    // PE wiring: each PE reads its bank's (delayed) slice of the beat.
    for (row <- 0 until yDim; col <- 0 until xDim) {
        val pe = arr(row)(col)
        val d  = bankOf(col)
        pe.input.bits.weights_i    := weightsPipe(d)(col)
        pe.input.bits.activation_i := actPipe(d)(row)
        pe.input.bits.nAcc         := nAccPipe(d)
        pe.input.valid             := validPipe(d)
        pe.output.ready            := output.ready
    }

    // All yDim rows exposed at the boundary.
    for (row <- 0 until yDim; col <- 0 until xDim; lane <- 0 until nLanes) {
        output.bits.accum(row)(col * nLanes + lane) := arr(row)(col).output.bits.accum(lane)
    }
    // The deepest bank finishes last and gates completion; earlier banks hold.
    val allDone   = arr(0)(doneCol).output.valid
    output.valid := allDone

    switch(stateReg) {
        is(idle_state) {
            when(startable) {
                nAccReg  := input.nAcc.bits
                stateReg := running_state
            }
        }
        is(running_state) {
            // Bank 0 done ⇒ all input consumed. Unbanked, bank 0 is the deepest bank.
            when(inputDone) {
                stateReg := (if (numBanks == 1) done_state else draining_state)
            }
        }
        is(done_state) {
            when(output.ready) {
                stateReg := idle_state
            }
        }
    }
    // draining → done. Outside the switch (and absent when unbanked) so the unbanked
    // FSM's emitted SV is unchanged.
    if (numBanks > 1) {
        when(stateReg === draining_state && allDone) {
            stateReg := done_state
        }
    }
}
