package packing

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
  def packedBytes(nTrits: Long): Long = (nTrits + TritsPerByte - 1) / TritsPerByte

  /** The 256-entry unpack ROM as 10-bit words (5 codes x 2 bits, code k at
    * [2k+1:2k]) for building a hardware VecInit. */
  def lutWords: Seq[Int] =
    (0 until 256).map { b =>
      unpackByte(b).zipWithIndex.map { case (code, k) => code << (2 * k) }.sum
    }
}
