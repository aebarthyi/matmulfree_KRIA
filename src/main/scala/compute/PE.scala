package compute

import chisel3._
import chisel3.util._
import chisel3.experimental.annotate
import firrtl.AttributeAnnotation

class PEInputBundle(val aWidth: Int, val maxAcc: Int) extends Bundle {
  val weights_i = Vec(aWidth/2, UInt(2.W))          //weights input
  val activation_i = UInt(aWidth.W)                     //activation input
  val nAcc = UInt(log2Ceil(maxAcc).W)                   //number of accumulations to do (handshakes)
}

class PEOutputBundle(val aWidth: Int, val accWidth: Int) extends Bundle {
  val accum = Vec(aWidth/2, UInt(accWidth.W)) //accumulator output (aWidth + headroom bits to prevent overflow)
}

/**
  * Ternary-MAC PE, the cell of [[MacArray]]: each of aWidth/2 lanes adds (code 1),
  * subtracts (code 3) or holds (code 0) the activation into its accumulator for nAcc
  * handshaked beats.
  *
  * `signedAct` sign-extends the activation into the accumulator (two's complement);
  * false zero-extends. The two agree for non-negative activations.
  */
class PE(val aWidth: Int, val maxAcc: Int, val signedAct: Boolean = false,
         val useDspAccum: Boolean = false) extends Module {
  require(maxAcc >= 2, "PE requires maxAcc >= 2 (hardware counter limitation)")
  // Accumulator = aWidth + log2Ceil(maxAcc) headroom (worst-case growth over maxAcc beats).
  val accHeadroom = log2Ceil(maxAcc)
  val accWidth = aWidth + accHeadroom
  val input = IO(Flipped(Decoupled(new PEInputBundle(aWidth, maxAcc))))
  val output = IO(Decoupled(new PEOutputBundle(aWidth, accWidth)))
  val accumReg = RegInit(VecInit(Seq.fill(aWidth/2)(0.U(accWidth.W))))
  // use_dsp="yes" maps each lane's add/sub accumulator onto a DSP48E2 (P-register feedback,
  // ALUMODE = sign, CE = hold). Synthesis-only; costs one DSP per lane per PE.
  if (useDspAccum) {
    accumReg.foreach { lane =>
      annotate(Seq(lane))(Seq(AttributeAnnotation(lane.toNamed, "use_dsp = \"yes\"")))
    }
  }
  val accumCounter = Counter(maxAcc)
  val nAccReg = Reg(UInt(log2Ceil(maxAcc).W))

  val idle_state :: running_state :: done_state :: Nil = Enum(3)

  val stateReg = RegInit(idle_state)

  val accumulation = Wire(Vec(aWidth/2, UInt(accWidth.W)))

  (input.bits.weights_i zip accumReg zip accumulation).map {
    case ((weight, accReg), accWire) =>
    if (signedAct) {
      // Two's-complement accumulate; the := to accWire truncates to accWidth (mod
      // 2^accWidth wrap, same as the unsigned path and the host reference).
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

  // Ready through idle+running so every beat, including the final one, is a real
  // valid&&ready handshake (sampling the final beat without one desyncs the stream).
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
