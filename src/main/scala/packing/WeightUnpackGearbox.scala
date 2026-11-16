package packing

import chisel3._
import chisel3.util._

/**
  * Streaming 1.6-bit weight unpacker (format: [[TernaryPack]]): dense packed bytes in
  * (inWidthBits/8 * 5 trits per beat), `outLanes` 2-bit weights out per beat.
  * An elastic trit buffer regroups the input
  * into full output beats and carries the remainder across input beats, so the
  * packed stream needs no per-beat padding. Capacity = outLanes + inTrits guarantees
  * an input beat is acceptable whenever no output can drain, so it cannot deadlock.
  */
class WeightUnpackGearbox(val inWidthBits: Int, val outLanes: Int) extends Module {
  require(inWidthBits % 8 == 0, s"inWidthBits=$inWidthBits must be a byte multiple")
  require(outLanes >= 1, s"outLanes=$outLanes must be >= 1")

  val inBytes  = inWidthBits / 8
  val inTrits  = inBytes * TernaryPack.TritsPerByte
  val capacity = outLanes + inTrits              // trits
  private val bufBits = capacity * 2

  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(UInt(inWidthBits.W)))
    val out = Decoupled(Vec(outLanes, UInt(2.W)))
    // Drop all buffered trits. A stream may leave padding trits after its last full
    // beat; flush between streams so they never bleed into the next one.
    val flush = Input(Bool())
  })

  // Unpack the incoming beat's bytes through the ROM into a flat trit vector
  // (LSB = byte0's trit0), purely combinational.
  private val lut = VecInit(TernaryPack.lutWords.map(_.U(10.W)))
  private val inBeatTrits = Cat((0 until inBytes).reverse.map { b =>
    lut(io.in.bits((b + 1) * 8 - 1, b * 8))
  })                                             // width = inTrits*2

  private val buf   = RegInit(0.U(bufBits.W))
  private val count = RegInit(0.U(log2Ceil(capacity + 1).W))

  io.out.valid := count >= outLanes.U
  io.out.bits  := VecInit(Seq.tabulate(outLanes)(i => buf(2 * i + 1, 2 * i)))

  private val emitFire     = io.out.fire
  private val afterEmitCnt = Mux(emitFire, count - outLanes.U, count)
  private val afterEmitBuf = Mux(emitFire, buf >> (outLanes * 2).U, buf)

  // Room for a full input beat after this cycle's (possible) emit.
  io.in.ready := (afterEmitCnt +& inTrits.U) <= capacity.U
  private val enqFire = io.in.fire

  private val inserted = (afterEmitBuf | (inBeatTrits << (afterEmitCnt << 1)))(bufBits - 1, 0)
  when(io.flush) {
    buf   := 0.U
    count := 0.U
  }.otherwise {
    buf   := Mux(enqFire, inserted, afterEmitBuf(bufBits - 1, 0))
    count := Mux(enqFire, afterEmitCnt + inTrits.U, afterEmitCnt)
  }
}
