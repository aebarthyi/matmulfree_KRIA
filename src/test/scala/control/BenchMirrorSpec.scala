package control

import scala.util.Random
import chisel3._
import chisel3.util.log2Ceil
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/**
  * Mirror of the `software/bench/bench.c` flow at K26_Bench parameters
  * (aWidth=16, xDim=4, batchSize=1, outBeatLanes=1). CoreSpec tests Core in
  * isolation with weights pre-arranged in the order Core expects; this spec
  * exercises the SOFTWARE-side packing — the layout bench.c writes to udmabuf
  * for the DMA to stream — and asserts it produces results matching a
  * straightforward on-host reference matmul.
  *
  * If the random-buffer test passes, the only remaining gap to a working board
  * run is the DMA-side mechanics (start / length register pokes), a much
  * smaller surface to debug than the full pack/load/compute/store flow.
  *
  * The "regression guard" test re-packs the same weight matrix in the
  * row-major order that bench.c used before the 2026-05-23 fix, and asserts
  * the output does NOT match — proving this spec would have caught yesterday's
  * bug.
  */
class BenchMirrorSpec extends AnyFreeSpec with Matchers with ChiselSim {
  import InstructionEncoder._

  // K26_Bench-aligned params. maxAcc / maxN / maxM scaled down for sim speed —
  // the packing logic is identical at any size so a 2-3 col-tile workload is
  // sufficient to catch ordering bugs.
  private val aWidth       = 16
  private val maxAcc       = 256
  private val xDim         = 4
  private val batchSize    = 1
  private val maxN         = 128
  private val maxM         = 128

  private val nLanes          = aWidth / 2                  //  8
  private val outLanesPerTile = xDim * nLanes               // 32
  private val tileAccWidth    = log2Ceil(maxAcc) + aWidth   // 24 here (28 on the real K26_Bench)
  private val outAccWidth     = tileAccWidth
  private val outAccModulus   = BigInt(1) << outAccWidth
  private val outLaneWidth    = 1 << log2Ceil(outAccWidth)
  private val axisDataWidth   = math.max(xDim, batchSize) * aWidth  // 64

  private val effOutBeatLanes = 1
  private val outSubBeats     = outLanesPerTile / effOutBeatLanes
  private val outBeatWidth    = effOutBeatLanes * outLaneWidth

  private val ADDR_WIDTH = 32
  private val DATA_WIDTH = 128
  private val ID_WIDTH   = 6
  private val STRB_ALL   = (BigInt(1) << (DATA_WIDTH / 8)) - 1

  private val OFF_INSTR   = BigInt(0x000)
  private val OFF_IRQ_ACK = BigInt(0x020)
  private val OKAY        = 0

  // ───────────────────────── Setup ─────────────────────────

  private def mkCore = new Core(
    aWidth       = aWidth,
    maxAcc       = maxAcc,
    xDim         = xDim,
    batchSize    = batchSize,
    maxN         = maxN,
    maxM         = maxM,
    outBeatLanes = effOutBeatLanes,
    axiAddrWidth = ADDR_WIDTH,
    axiDataWidth = DATA_WIDTH,
    axiIdWidth   = ID_WIDTH
  )

  private def resetDut(dut: Core): Unit = {
    dut.s_axi.awvalid.poke(false.B)
    dut.s_axi.wvalid.poke(false.B)
    dut.s_axi.bready.poke(false.B)
    dut.s_axi.arvalid.poke(false.B)
    dut.s_axi.rready.poke(false.B)
    dut.s_axi.awid.poke(0.U)
    dut.s_axi.arid.poke(0.U)
    dut.s_axis.tvalid.poke(false.B)
    dut.s_axis.tdata.poke(0.U)
    dut.s_axis.tlast.poke(false.B)
    dut.m_axis.tready.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  // ───────────────────────── AXI4 helpers ─────────────────────────
  // (Duplicated from CoreSpec to keep each spec self-contained — refactor into
  // a shared trait if a third spec needs them.)

  private def axiWrite(dut: Core, addr: BigInt, data: BigInt, maxCycles: Int = 50): Int = {
    dut.s_axi.awid.poke(0.U)
    dut.s_axi.awaddr.poke(addr.U)
    dut.s_axi.awlen.poke(0.U)
    dut.s_axi.awsize.poke(4.U)
    dut.s_axi.awburst.poke(1.U)
    dut.s_axi.awvalid.poke(true.B)
    dut.s_axi.wdata.poke(data.U)
    dut.s_axi.wstrb.poke(STRB_ALL.U)
    dut.s_axi.wlast.poke(true.B)
    dut.s_axi.wvalid.poke(true.B)
    dut.s_axi.bready.poke(true.B)

    var awA = true; var wA = true; var bR = true
    var resp = 0; var done = false; var cycles = 0
    while (!done && cycles < maxCycles) {
      val awF = awA && dut.s_axi.awready.peek().litToBoolean
      val wF  = wA  && dut.s_axi.wready.peek().litToBoolean
      val bF  = bR  && dut.s_axi.bvalid.peek().litToBoolean
      if (bF) { resp = dut.s_axi.bresp.peek().litValue.toInt; done = true }
      dut.clock.step()
      if (awF) { dut.s_axi.awvalid.poke(false.B); awA = false }
      if (wF)  { dut.s_axi.wvalid.poke(false.B);  wA  = false }
      if (bF)  { dut.s_axi.bready.poke(false.B);  bR  = false }
      cycles += 1
    }
    require(done, s"axiWrite to 0x${addr.toString(16)} timed out")
    dut.s_axi.awvalid.poke(false.B)
    dut.s_axi.wvalid.poke(false.B)
    dut.s_axi.bready.poke(false.B)
    resp
  }

  // ───────────────────────── AXI-Stream helpers ─────────────────────────

  private def pushAxisBeats(dut: Core, beats: Seq[BigInt], maxCyclesPerBeat: Int = 50): Unit = {
    var idx    = 0
    var budget = beats.length * maxCyclesPerBeat + 50
    while (idx < beats.length && budget > 0) {
      dut.s_axis.tdata.poke(beats(idx).U)
      dut.s_axis.tvalid.poke(true.B)
      dut.s_axis.tlast.poke((idx == beats.length - 1).B)
      val accepts = dut.s_axis.tready.peek().litToBoolean
      dut.clock.step()
      if (accepts) idx += 1
      budget -= 1
    }
    dut.s_axis.tvalid.poke(false.B)
    dut.s_axis.tlast.poke(false.B)
    require(idx == beats.length, s"s_axis push: only $idx/${beats.length} beats accepted")
  }

  private def pullAxisBeats(dut: Core, count: Int, maxCycles: Int = 4000): Seq[BigInt] = {
    dut.m_axis.tready.poke(true.B)
    val beats  = scala.collection.mutable.ArrayBuffer.empty[BigInt]
    var cycles = 0
    while (beats.length < count && cycles < maxCycles) {
      if (dut.m_axis.tvalid.peek().litToBoolean) {
        beats += dut.m_axis.tdata.peek().litValue
      }
      dut.clock.step()
      cycles += 1
    }
    dut.m_axis.tready.poke(false.B)
    require(beats.length == count, s"m_axis pull: got ${beats.length}/$count beats")
    beats.toSeq
  }

  private def waitForIrq(dut: Core, maxCycles: Int = 8000): Unit = {
    var c = 0
    while (!dut.irq.peek().litToBoolean && c < maxCycles) { dut.clock.step(); c += 1 }
    require(dut.irq.peek().litToBoolean, s"IRQ never asserted within $maxCycles cycles")
  }

  private def ackIrq(dut: Core): Unit = {
    axiWrite(dut, OFF_IRQ_ACK, BigInt(1)) mustBe OKAY
    dut.clock.step()
  }

  // ───────────────────────── Bench-mirroring packers ─────────────────────────

  /** One activation per s_axis beat: low aWidth bits = activation, rest = 0.
    * Matches `bench.c`'s `act_dma[i] = (uint64_t)v` write. */
  private def packActBeat(a: Int): BigInt =
    BigInt(a) & ((BigInt(1) << aWidth) - 1)

  /** Ternary signed value → 2-bit code: +1 → 1, -1 → 3, 0 → 0. */
  private def ternaryCode(v: Int): Int = v match {
    case  1 => 1
    case -1 => 3
    case  0 => 0
    case other => throw new IllegalArgumentException(s"non-ternary value $other")
  }

  /** Pack one s_axis weight beat for a given (n, t): outLanesPerTile codes,
    * lane 0 at LSB (matches Chisel's Core.scala weight-vec extraction). */
  private def packWeightBeat(weightMat: Array[Array[Int]], n: Int, m: Int, row: Int, t: Int): BigInt = {
    var beat = BigInt(0)
    for (lane <- 0 until outLanesPerTile) {
      val col = t * outLanesPerTile + lane
      val code = if (col < m) ternaryCode(weightMat(row)(col)) else 0
      beat |= (BigInt(code) & 0x3) << (lane * 2)
    }
    beat
  }

  /** Bench-style col-tile-major weight buffer.
    * Layout matches Core.scala::sCompute consumption order:
    *   [t=0, n=0..N-1], [t=1, n=0..N-1], ..., [t=T-1, n=0..N-1]
    */
  private def packBenchWeightBuffer(weightMat: Array[Array[Int]], n: Int, m: Int): Seq[BigInt] = {
    require(m % outLanesPerTile == 0, s"m=$m must be a multiple of outLanesPerTile=$outLanesPerTile")
    val numColTiles = m / outLanesPerTile
    val buf = scala.collection.mutable.ArrayBuffer.empty[BigInt]
    for (t <- 0 until numColTiles; row <- 0 until n) {
      buf += packWeightBeat(weightMat, n, m, row, t)
    }
    buf.toSeq
  }

  /** Yesterday's bug: row-major outer loop. Kept here so the regression-guard
    * test can verify this spec would have caught the bench.c bug. */
  private def packBenchWeightBuffer_RowMajorBuggy(
      weightMat: Array[Array[Int]], n: Int, m: Int): Seq[BigInt] = {
    val numColTiles = m / outLanesPerTile
    val buf = scala.collection.mutable.ArrayBuffer.empty[BigInt]
    for (row <- 0 until n; t <- 0 until numColTiles) {
      buf += packWeightBeat(weightMat, n, m, row, t)
    }
    buf.toSeq
  }

  /** Software reference. out[m] = sum_n A[n] * W[n][m].
    * Wraps mod outAccModulus to match Core's UInt accumulator rollover. */
  private def refMatmul(acts: Array[Int], wt: Array[Array[Int]], n: Int, m: Int): Seq[BigInt] = {
    val out = Array.fill(m)(BigInt(0))
    for (i <- 0 until n; j <- 0 until m) {
      out(j) += BigInt(wt(i)(j)) * BigInt(acts(i))
    }
    out.map(v => ((v % outAccModulus) + outAccModulus) % outAccModulus).toSeq
  }

  /** Unpack m_axis beats. effOutBeatLanes=1 → each beat is one accumulator. */
  private def unpackOutputs(beats: Seq[BigInt]): Seq[BigInt] = {
    val mask = outAccModulus - 1
    beats.flatMap { beat =>
      (0 until effOutBeatLanes).map { i =>
        (beat >> (i * outLaneWidth)) & mask
      }
    }
  }

  private def runBenchFlow(
      dut: Core, acts: Array[Int], weightBeats: Seq[BigInt], n: Int, m: Int
  ): Seq[BigInt] = {
    axiWrite(dut, OFF_INSTR, encode(LOAD_ACT, BigInt(0x100), BigInt(n), 0)) mustBe OKAY
    pushAxisBeats(dut, acts.map(packActBeat).toSeq)
    waitForIrq(dut); ackIrq(dut)

    axiWrite(dut, OFF_INSTR,
      encode(COMPUTE_MM, BigInt(0x300), BigInt(n), BigInt(m))) mustBe OKAY
    pushAxisBeats(dut, weightBeats, maxCyclesPerBeat = 200)
    waitForIrq(dut, maxCycles = 16000); ackIrq(dut)

    val totalOutputs = batchSize * m
    axiWrite(dut, OFF_INSTR,
      encode(STORE_OUT, BigInt(0x400), BigInt(totalOutputs), 0)) mustBe OKAY
    val numBeats = totalOutputs / effOutBeatLanes
    val raw = pullAxisBeats(dut, numBeats, maxCycles = 8000)
    waitForIrq(dut); ackIrq(dut)
    unpackOutputs(raw)
  }

  /** Random ternary weight matrix matching bench.c's distribution
    * (25% +1, 25% -1, 50% 0 — via 2-bit RNG with c_map[2]==0). */
  private def randomTernaryMatrix(rng: Random, n: Int, m: Int): Array[Array[Int]] =
    Array.fill(n, m) {
      rng.nextInt(4) match {
        case 1 => 1
        case 3 => -1
        case _ => 0
      }
    }

  // ──────────────────────────────────────────────────────────────────────
  // Tests
  // ──────────────────────────────────────────────────────────────────────

  "Bench mirror at K26_Bench params (aWidth=16, xDim=4, batchSize=1)" - {

    "single col tile: bench-style packing matches ref matmul" in {
      simulate(mkCore) { dut =>
        resetDut(dut)
        val rng = new Random(1L)
        val n   = 16
        val m   = outLanesPerTile        // exactly one col tile
        val acts = Array.fill(n)(rng.nextInt(16))
        val wt   = randomTernaryMatrix(rng, n, m)
        val beats = packBenchWeightBuffer(wt, n, m)
        runBenchFlow(dut, acts, beats, n, m) mustBe refMatmul(acts, wt, n, m)
      }
    }

    "multi col tile: bench-style packing matches ref matmul (3 seeds)" in {
      Seq(7L, 42L, 256L).foreach { seed =>
        simulate(mkCore) { dut =>
          resetDut(dut)
          val rng = new Random(seed)
          val numColTiles = 2 + rng.nextInt(2)             // 2..3
          val m = numColTiles * outLanesPerTile
          val n = 16 + rng.nextInt(33)                     // 16..48
          val acts = Array.fill(n)(rng.nextInt(16))
          val wt   = randomTernaryMatrix(rng, n, m)
          val beats = packBenchWeightBuffer(wt, n, m)
          val actual   = runBenchFlow(dut, acts, beats, n, m)
          val expected = refMatmul(acts, wt, n, m)
          withClue(s"seed=$seed n=$n m=$m") { actual mustBe expected }
        }
      }
    }

    "edge: negative-only accumulator wraps to outAccWidth correctly" in {
      simulate(mkCore) { dut =>
        resetDut(dut)
        val n = 16
        val m = outLanesPerTile
        val acts = Array.fill(n)(15)                       // max activation
        val wt   = Array.fill(n, m)(-1)                    // every weight -1
        val beats = packBenchWeightBuffer(wt, n, m)
        // Expected sum per col: -15 * 16 = -240, mod 2^outAccWidth
        val actual = runBenchFlow(dut, acts, beats, n, m)
        val expectedOne = ((BigInt(-240) % outAccModulus) + outAccModulus) % outAccModulus
        (0 until m).foreach { col =>
          actual(col) mustBe expectedOne
        }
      }
    }

    "regression guard: row-major (yesterday's bug) does NOT match reference" in {
      simulate(mkCore) { dut =>
        resetDut(dut)
        val rng = new Random(1234L)
        val n = 16
        val numColTiles = 2
        val m = numColTiles * outLanesPerTile
        val acts = Array.fill(n)(1 + rng.nextInt(15))      // nonzero so wt order matters
        val wt   = randomTernaryMatrix(rng, n, m)

        val correctBeats = packBenchWeightBuffer(wt, n, m)
        val buggyBeats   = packBenchWeightBuffer_RowMajorBuggy(wt, n, m)

        // Sanity: same beat count, but ordering differs (else the test would
        // be vacuous — bug must actually permute the stream).
        buggyBeats.length mustBe correctBeats.length
        buggyBeats        must not equal correctBeats

        val buggyOut = runBenchFlow(dut, acts, buggyBeats, n, m)
        val expected = refMatmul(acts, wt, n, m)
        buggyOut must not equal expected
      }
    }
  }
}
