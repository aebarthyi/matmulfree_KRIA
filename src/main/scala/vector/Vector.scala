package vector

import chisel3._
import chisel3.util._

class VectorInputBundle(val nLanes: Int, val aWidth: Int, val maxAcc: Int) extends Bundle {
    val weights_i    = Flipped(Decoupled(Vec(nLanes, UInt(2.W))))
    val activation_i = Flipped(Decoupled(Vec(nLanes, UInt(aWidth.W))))
    val nAcc         = Flipped(Decoupled(UInt(log2Ceil(maxAcc).W)))
}

class VectorOutputBundle(val nLanes: Int, val aWidth: Int, val maxAcc: Int) extends Bundle {
    // sign bit + activation magnitude + tree growth (log2 nLanes) + accumulation growth (log2 maxAcc)
    val accum = UInt((aWidth + 1 + log2Ceil(nLanes) + log2Ceil(maxAcc)).W)
}

class AccumulatorStage(val nPairs: Int, val inWidth: Int) extends Module {
    val input = IO(new Bundle {
        val pairInput = Input(Vec(nPairs, Vec(2, SInt(inWidth.W))))
    })
    val output = IO(new Bundle {
        val sumOutput = Output(Vec(nPairs, SInt((inWidth + 1).W)))
    })

    (input.pairInput zip output.sumOutput).foreach { case (pair, sum) =>
        sum := pair(0) +& pair(1)
    }
}

class Vector(val nLanes: Int, val aWidth: Int, val maxAcc: Int) extends Module {
    require(maxAcc >= 2, "Vector requires maxAcc >= 2 (hardware counter limitation)")
    require(nLanes >= 2 && (nLanes & (nLanes - 1)) == 0, "nLanes must be a power of 2 >= 2")

    val input  = IO(new VectorInputBundle(nLanes, aWidth, maxAcc))
    val output = IO(Decoupled(new VectorOutputBundle(nLanes, aWidth, maxAcc)))

    val contribWidth = aWidth + 1                       // sign + activation
    val laneSumWidth = contribWidth + log2Ceil(nLanes)  // after reduction tree
    val outWidth     = laneSumWidth + log2Ceil(maxAcc)  // after accumulation

    val accumReg = RegInit(0.S(outWidth.W))
    val counter  = RegInit(0.U(log2Ceil(maxAcc).W))
    val nAccReg  = Reg(UInt(log2Ceil(maxAcc).W))

    val idle_state :: running_state :: done_state :: Nil = Enum(3)
    val stateReg = RegInit(idle_state)

    // Per-lane ternary contribution as a sign-extended SInt: 0, +act, or -act
    val contributions = Wire(Vec(nLanes, SInt(contribWidth.W)))
    (input.weights_i.bits zip input.activation_i.bits zip contributions).foreach {
        case ((w, a), c) =>
            val aSigned = Cat(0.U(1.W), a).asSInt
            c := MuxLookup(w, 0.S(contribWidth.W))(Seq(
                1.U -> aSigned,
                3.U -> (0.S(contribWidth.W) - aSigned)
            ))
    }

    // Balanced reduction tree
    val nStages = log2Ceil(nLanes)
    val stages = Seq.tabulate(nStages) { i =>
        Module(new AccumulatorStage(nLanes >> (i + 1), contribWidth + i))
    }

    for (j <- 0 until (nLanes / 2)) {
        stages(0).input.pairInput(j)(0) := contributions(2 * j)
        stages(0).input.pairInput(j)(1) := contributions(2 * j + 1)
    }
    for (i <- 1 until nStages) {
        val prev   = stages(i - 1).output.sumOutput
        val nPairs = nLanes >> (i + 1)
        for (j <- 0 until nPairs) {
            stages(i).input.pairInput(j)(0) := prev(2 * j)
            stages(i).input.pairInput(j)(1) := prev(2 * j + 1)
        }
    }

    val laneSum = stages.last.output.sumOutput(0)  // SInt(laneSumWidth.W)

    // Streaming inputs (weights, activations) gate each other; nAcc rendezvous only at start
    val canStep   = input.weights_i.valid && input.activation_i.valid
    val startable = canStep && input.nAcc.valid

    input.nAcc.ready         := (stateReg === idle_state) && startable
    input.weights_i.ready    := ((stateReg === idle_state)    && startable) ||
                                ((stateReg === running_state) && input.activation_i.valid)
    input.activation_i.ready := ((stateReg === idle_state)    && startable) ||
                                ((stateReg === running_state) && input.weights_i.valid)

    output.valid      := (stateReg === done_state)
    output.bits.accum := accumReg.asUInt

    switch(stateReg) {
        is(idle_state) {
            when(startable) {
                nAccReg  := input.nAcc.bits - 1.U
                accumReg := laneSum
                counter  := 1.U
                stateReg := running_state
            }
        }
        is(running_state) {
            when(canStep) {
                accumReg := accumReg + laneSum
                when(counter === nAccReg) {
                    stateReg := done_state
                }.otherwise {
                    counter := counter + 1.U
                }
            }
        }
        is(done_state) {
            when(output.ready) {
                accumReg := 0.S
                counter  := 0.U
                stateReg := idle_state
            }
        }
    }
}
