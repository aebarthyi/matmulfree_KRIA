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

class SystolicArrayOutputBundle(val aWidth: Int, val maxAcc: Int, val xDim: Int, val yDim: Int) extends Bundle {
    // Per row, one accumulator per (col, lane). All yDim rows are exposed.
    val accum = Vec(yDim, Vec((aWidth / 2) * xDim, UInt((log2Ceil(maxAcc) + aWidth).W)))
}

/**
  * 2D systolic array of ternary-MAC PEs.
  *
  *   - yDim rows, each row holding one streaming activation per cycle. The row
  *     index doubles as the batch dimension: row k accumulates a separate output
  *     for batch item k.
  *   - xDim columns; per cycle, the array consumes one full weight beat
  *     (xDim·nLanes ternary weights) and broadcasts it across all rows. There is
  *     no per-row weight delay chain — every row sees the same weight at the
  *     same cycle.
  *   - For each (row, col, lane), PE(row, col)'s lane-l accumulator computes
  *         sum_{t=0..nAcc-1} sign(weight_t[col][lane]) * activation_t[row]
  *     which is exactly the batched matrix-vector product
  *         O[row][col·nLanes + lane] = sum_t W[t][col·nLanes + lane] * A[row][t]
  *
  *   - Both `weights_i` and `activation_i` use a Decoupled handshake; in
  *     `running_state` they must both be valid each cycle for the array to step.
  *     `nAcc` is consumed once during the initial three-way rendezvous in idle.
  *
  *   - Back-to-back: PE.scala leaves accumCounter/accumReg dirty on done→idle,
  *     so callers must hard-reset between successive operations.
  */
class SystolicArray(val aWidth: Int, val maxAcc: Int, val xDim: Int, val yDim: Int,
                    val signedAct: Boolean = false) extends Module {
    require(aWidth >= 2 && aWidth % 2 == 0, "aWidth must be even and >= 2")
    require(maxAcc >= 2, "maxAcc must be >= 2")
    require(xDim >= 1, "xDim must be >= 1")
    require(yDim >= 1, "yDim must be >= 1")

    val input  = IO(new SystolicArrayInputBundle(aWidth, maxAcc, xDim, yDim))
    val output = IO(Decoupled(new SystolicArrayOutputBundle(aWidth, maxAcc, xDim, yDim)))

    val nLanes = aWidth / 2

    val arr = Seq.tabulate(yDim, xDim) { (_, _) => Module(new PE(aWidth, maxAcc, signedAct)) }

    val nAccReg = Reg(UInt(log2Ceil(maxAcc).W))

    val idle_state :: running_state :: done_state :: Nil = Enum(3)
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

    // External handshakes. In running, weights and activations gate each other so
    // both fire together per cycle. nAcc rendezvous fires only at start.
    input.nAcc.ready         := isLatching
    input.activation_i.ready := isLatching ||
                                ((stateReg === running_state) && input.weights_i.valid)
    input.weights_i.ready    := isLatching ||
                                ((stateReg === running_state) && input.activation_i.valid)

    // PE wiring: weights broadcast (no delay chain), activations per-row each cycle.
    for (row <- 0 until yDim; col <- 0 until xDim) {
        val pe = arr(row)(col)
        pe.input.bits.weights_i    := externalWeightsByCol(col)
        pe.input.bits.activation_i := input.activation_i.bits(row)
        pe.input.bits.nAcc         := Mux(isLatching, input.nAcc.bits, nAccReg)
        pe.input.valid             := isLatching ||
                                      ((stateReg === running_state) && canStep)
        pe.output.ready            := output.ready
    }

    // All yDim rows exposed at the boundary.
    for (row <- 0 until yDim; col <- 0 until xDim; lane <- 0 until nLanes) {
        output.bits.accum(row)(col * nLanes + lane) := arr(row)(col).output.bits.accum(lane)
    }
    // PEs run in lockstep; pick any one to detect done.
    output.valid := arr(0)(0).output.valid

    switch(stateReg) {
        is(idle_state) {
            when(startable) {
                nAccReg  := input.nAcc.bits
                stateReg := running_state
            }
        }
        is(running_state) {
            when(arr(0)(0).output.valid) {
                stateReg := done_state
            }
        }
        is(done_state) {
            when(output.ready) {
                stateReg := idle_state
            }
        }
    }
}
