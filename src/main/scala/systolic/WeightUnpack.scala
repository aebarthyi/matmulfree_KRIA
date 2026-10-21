package systolic

import chisel3._
import chisel3.util._

/**
  * 1.6-bit ternary weight packing: since 3^5 = 243 <= 256, five trits fit in one
  * byte as base-3 digits (20% fewer weight bytes than the 2-bit PE code).
  *
  * Digit convention (packer and hardware MUST agree):
  *   logical trit  ->  base-3 digit  ->  PE code
  *        0 (hold)          0               0  (0b00)
  *       +1 (add)           1               1  (0b01)
  *       -1 (sub)           2               3  (0b11)
  *
  * Byte value = d0 + 3*d1 + 9*d2 + 27*d3 + 81*d4, trit i at digit i (LSB-first),
  * matching Core's LSB-first lane order (weightVec(i) <- tdata[2i+1:2i]). Byte
  * values 243..255 are unused; the packer must never emit them.
  */
object TernaryPack {
  val TritsPerByte = 5
  private val digitToCode = Seq(0, 1, 3)          // base-3 digit -> PE code
  private val codeToDigit = Map(0 -> 0, 1 -> 1, 3 -> 2)

  /** The 5 PE codes a packed byte unpacks to (index 0 = LSB trit). Invalid
    * bytes (>= 243) are don't-cares — return holds. */
  def unpackByte(byte: Int): Seq[Int] = {
    require(byte >= 0 && byte < 256, s"byte $byte out of range")
    if (byte >= 243) Seq.fill(TritsPerByte)(0)
    else (0 until TritsPerByte).map(k => digitToCode((byte / math.pow(3, k).toInt) % 3))
  }

  /** Reference packer: PE codes (each 0/1/3) -> bytes, 5 trits/byte, the final
    * partial group padded with holds. The inverse of [[unpackByte]]. */
  def packCodes(codes: Seq[Int]): Seq[Int] =
    codes.grouped(TritsPerByte).map { g =>
      val padded = g.padTo(TritsPerByte, 0)
      padded.zipWithIndex.map { case (c, k) =>
        codeToDigit.getOrElse(c, throw new IllegalArgumentException(s"non-ternary code $c")) *
          math.pow(3, k).toInt
      }.sum
    }.toSeq

  /** Packed byte count for `nTrits` ternary weights. */
  def packedBytes(nTrits: Int): Int = (nTrits + TritsPerByte - 1) / TritsPerByte

  /** The 256-entry unpack ROM as 10-bit words (5 codes x 2 bits, code k at
    * [2k+1:2k]) for building a hardware VecInit. */
  def lutWords: Seq[Int] =
    (0 until 256).map { b =>
      unpackByte(b).zipWithIndex.map { case (code, k) => code << (2 * k) }.sum
    }
}

/**
  * Streaming unpacker: dense packed bytes in (inWidthBits/8 * 5 trits per beat),
  * `outLanes` 2-bit weights out per beat. An elastic trit buffer regroups the input
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
