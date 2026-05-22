// See README.md for license details.

package systolic

import chisel3._
import chisel3.util._

class PEInputBundle(val aWidth: Int, val maxAcc: Int) extends Bundle {
  val weights_i = Vec(aWidth/2, UInt(2.W))          //weights input
  val activation_i = UInt(aWidth.W)                     //activation input
  val nAcc = UInt(log2Ceil(maxAcc).W)                   //number of accumulations to do (handshakes)
}

class PEOutputBundle(val aWidth: Int, val maxAcc: Int) extends Bundle {
  val accum = Vec(aWidth/2, UInt((log2Ceil(maxAcc) + aWidth).W)) //accumulator output (adds log2(N) bits to prevent overflow)
}

/**
  * PE accumulates activations based on ternary weights
*/
class PE(val aWidth: Int, val maxAcc: Int) extends Module {
  require(maxAcc >= 2, "PE requires maxAcc >= 2 (hardware counter limitation)")
  val input = IO(Flipped(Decoupled(new PEInputBundle(aWidth, maxAcc))))
  val output = IO(Decoupled(new PEOutputBundle(aWidth, maxAcc)))
  val accumReg = RegInit(VecInit(Seq.fill(aWidth/2)(0.U((log2Ceil(maxAcc) + aWidth).W))))
  val accumCounter = Counter(maxAcc)
  val nAccReg = Reg(UInt(log2Ceil(maxAcc).W))

  val idle_state :: running_state :: done_state :: Nil = Enum(3)
  
  val stateReg = RegInit(idle_state)

  val accumulation = Wire(Vec(aWidth/2, UInt((log2Ceil(maxAcc) + aWidth).W)))

  (input.bits.weights_i zip accumReg zip accumulation).map {
    case ((weight, accReg), accWire) => 
    when(weight === 1.U){
        accWire := accReg + input.bits.activation_i 
    }.elsewhen(weight === 3.U){
        accWire := accReg - input.bits.activation_i
    }.otherwise{
        accWire := accReg
    }
  }

  input.ready := !(accumCounter.value === nAccReg)
  output.valid := (stateReg === done_state)
  output.bits := DontCare
  
  switch(stateReg){
    is(idle_state){
        when(input.valid){
            nAccReg := (input.bits.nAcc-1.U)
            (accumReg zip accumulation).map{ case (r, a) => r := a}
            stateReg := running_state
            accumCounter.inc()
        }
    }
    is(running_state){
        when(input.valid){
            (accumReg zip accumulation).map{ case (r, a) => r := a}
            accumCounter.inc()
        }
        when(accumCounter.value === nAccReg){
            (accumReg zip accumulation).map{ case (r, a) => r := a}
            stateReg := done_state
        }
    }
    is(done_state){
        output.bits.accum := accumReg
        when(output.ready){
            stateReg := idle_state
            // Reset counter + lane accumulators so the next op starts clean
            // without requiring an external reset between ops.
            accumCounter.reset()
            accumReg.foreach(_ := 0.U)
        }
    }
  }
}
