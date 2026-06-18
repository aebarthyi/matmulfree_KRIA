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
  * PE accumulates activations based on ternary weights.
  *
  * `signedAct` selects how the aWidth-bit activation is extended into the wider
  * accumulator: false (default) zero-extends — the legacy/bring-up behavior, so
  * deployed presets and their tests are unchanged; true sign-extends, treating
  * the activation as two's-complement (real BitLinear activations, and the
  * 16-bit fixed-point the HGRN carries). For non-negative activations the two
  * paths are identical, so this only matters once negative values are streamed.
*/
class PE(val aWidth: Int, val maxAcc: Int, val signedAct: Boolean = false) extends Module {
  require(maxAcc >= 2, "PE requires maxAcc >= 2 (hardware counter limitation)")
  val accWidth = log2Ceil(maxAcc) + aWidth
  val input = IO(Flipped(Decoupled(new PEInputBundle(aWidth, maxAcc))))
  val output = IO(Decoupled(new PEOutputBundle(aWidth, maxAcc)))
  val accumReg = RegInit(VecInit(Seq.fill(aWidth/2)(0.U(accWidth.W))))
  val accumCounter = Counter(maxAcc)
  val nAccReg = Reg(UInt(log2Ceil(maxAcc).W))

  val idle_state :: running_state :: done_state :: Nil = Enum(3)

  val stateReg = RegInit(idle_state)

  val accumulation = Wire(Vec(aWidth/2, UInt(accWidth.W)))

  (input.bits.weights_i zip accumReg zip accumulation).map {
    case ((weight, accReg), accWire) =>
    if (signedAct) {
      // Sign-extend the activation and accumulate in two's complement. accReg
      // holds a signed sum reinterpreted as UInt; the := to the accWidth-wide
      // accWire truncates the grown sum back to accWidth (modular wrap — same
      // as the unsigned path below, matching the host's mod-2^accWidth ref).
      val a = input.bits.activation_i.asSInt
      when(weight === 1.U){
          accWire := (accReg.asSInt + a).asUInt
      }.elsewhen(weight === 3.U){
          accWire := (accReg.asSInt - a).asUInt
      }.otherwise{
          accWire := accReg
      }
    } else {
      when(weight === 1.U){
          accWire := accReg + input.bits.activation_i
      }.elsewhen(weight === 3.U){
          accWire := accReg - input.bits.activation_i
      }.otherwise{
          accWire := accReg
      }
    }
  }

  // Ready through idle+running so every beat (including the final one) is a clean
  // valid&&ready handshake. The old `!(counter === nAccReg)` form deasserted ready
  // on the final accumulation cycle and grabbed that beat's data WITHOUT a
  // handshake — if tvalid gapped on exactly that cycle the PE accumulated garbage
  // and finished one beat early, desyncing the upstream DMA stream.
  input.ready := (stateReg =/= done_state)
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
            when(accumCounter.value === nAccReg){
                stateReg := done_state
            }
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
