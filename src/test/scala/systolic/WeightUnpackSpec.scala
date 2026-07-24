package systolic

import scala.util.Random
import chisel3._
import testutil.SingleThreadVerilator
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class WeightUnpackSpec extends AnyFreeSpec with Matchers with SingleThreadVerilator {

  private val ternaryCodes = Seq(0, 1, 3)

  // ── Pure-Scala reference: pack ∘ unpack is identity (up to 5-trit padding) ──
  "TernaryPack round-trips codes through bytes" in {
    val rng = new Random(1)
    for (_ <- 0 until 200) {
      val n     = rng.nextInt(37)
      val codes = Seq.fill(n)(ternaryCodes(rng.nextInt(3)))
      val bytes = TernaryPack.packCodes(codes)
      bytes.length mustBe TernaryPack.packedBytes(n)
      bytes.foreach(b => (b >= 0 && b < 243) mustBe true)     // never an unused code
      val back = bytes.flatMap(TernaryPack.unpackByte)
      back.take(n) mustBe codes                                // padding-holds ignored
      back.drop(n).foreach(_ mustBe 0)
    }
  }

  "unpackByte matches the 10-bit ROM words" in {
    for (b <- 0 until 256) {
      val codes = TernaryPack.unpackByte(b)
      val word  = codes.zipWithIndex.map { case (c, k) => c << (2 * k) }.sum
      TernaryPack.lutWords(b) mustBe word
    }
  }

  // ── Hardware gearbox vs the LUT reference ──────────────────────────────────
  private def runGearbox(inWidthBits: Int, outLanes: Int, nBeats: Int,
                         seed: Int, backpressure: Boolean): Unit = {
    val rng     = new Random(seed)
    val inBytes = inWidthBits / 8
    // Random packed beats (bytes restricted to the 243 valid codes) + the flat
    // trit stream they must unpack to, LSB-first per beat.
    val beats   = Seq.fill(nBeats) {
      val bs = Seq.fill(inBytes)(rng.nextInt(243))
      val beat = bs.zipWithIndex.map { case (b, j) => BigInt(b) << (8 * j) }.sum
      (beat, bs.flatMap(TernaryPack.unpackByte))
    }
    val expectedTrits = beats.flatMap(_._2)
    val expectedBeats = expectedTrits.grouped(outLanes).filter(_.length == outLanes).toVector

    simulate(new WeightUnpackGearbox(inWidthBits, outLanes)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.clock.step()

      var inIdx  = 0
      val got    = scala.collection.mutable.ArrayBuffer[Seq[BigInt]]()
      val maxCyc = (nBeats + expectedBeats.length) * 8 + 200
      var cyc    = 0
      while (got.length < expectedBeats.length && cyc < maxCyc) {
        val haveIn   = inIdx < beats.length
        val outReady = !backpressure || (rng.nextInt(3) != 0)
        dut.io.in.valid.poke(haveIn.B)
        if (haveIn) dut.io.in.bits.poke(beats(inIdx)._1.U(inWidthBits.W))
        dut.io.out.ready.poke(outReady.B)

        val inFire  = haveIn && dut.io.in.ready.peek().litToBoolean
        val outFire = outReady && dut.io.out.valid.peek().litToBoolean
        if (outFire) got += (0 until outLanes).map(i => dut.io.out.bits(i).peek().litValue)

        dut.clock.step()
        if (inFire) inIdx += 1
        cyc += 1
      }

      got.length mustBe expectedBeats.length
      got.zip(expectedBeats).zipWithIndex.foreach { case ((g, e), bi) =>
        g.map(_.toInt) mustBe e
      }
    }
  }

  "WeightUnpackGearbox(128b in, 256 lanes) unpacks a stream" in {
    runGearbox(inWidthBits = 128, outLanes = 256, nBeats = 96, seed = 2, backpressure = false)
  }
  "WeightUnpackGearbox(128b in, 256 lanes) survives backpressure" in {
    runGearbox(inWidthBits = 128, outLanes = 256, nBeats = 96, seed = 3, backpressure = true)
  }
  "WeightUnpackGearbox(16b in, 8 lanes) unpacks a stream" in {
    runGearbox(inWidthBits = 16, outLanes = 8, nBeats = 40, seed = 4, backpressure = false)
  }
  "WeightUnpackGearbox(16b in, 8 lanes) survives backpressure" in {
    runGearbox(inWidthBits = 16, outLanes = 8, nBeats = 40, seed = 5, backpressure = true)
  }
}
