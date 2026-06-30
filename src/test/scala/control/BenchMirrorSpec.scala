package control

import scala.util.Random
import chisel3._
import chisel3.util.log2Ceil
import testutil.SingleThreadVerilator
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/**
  * Mirror of the `software/bench/bench.c` flow, parameterized over the bench
  * presets' geometry (see concrete subclasses at the bottom: K26_Bench through
  * K26_Bench64 shapes, all batchSize=1 / outBeatLanes=1). CoreSpec tests Core
  * in isolation with weights pre-arranged in the order Core expects; this spec
  * exercises the SOFTWARE-side packing — the layout bench.c writes to udmabuf
  * for the DMA to stream — and asserts it produces results matching a
  * straightforward on-host reference matmul.
  *
  * Drives [[CoreTop]] (not bare Core) so the N-way s_axis port join is in the
  * tested path: geometries wider than 128 bits split each beat into N 128-bit
  * slices fed on `s_axis_0..s_axis_{N-1}` in lockstep, exactly as N parallel
  * HP-port DMAs would on the board. N==1 geometries collapse to the legacy
  * single `s_axis` pass-through.
  *
  * If the random-buffer test passes, the only remaining gap to a working board
  * run is the DMA-side mechanics (start / length register pokes), a much
  * smaller surface to debug than the full pack/load/compute/store flow.
  *
  * The "regression guard" test re-packs the same weight matrix in the
  * row-major order that bench.c used before the 2026-05-23 fix, and asserts
  * the output does NOT match — proving this spec would have caught that bug.
  */
abstract class BenchMirrorSpecBase(
    geomName: String,
    aWidth:   Int,
    maxAcc:   Int,
    xDim:     Int,
    maxN:     Int,
    maxM:     Int,
    batchSize: Int = 1,
    outBeatLanes: Int = 1,
    signedAct: Boolean = false
) extends AnyFreeSpec with Matchers with SingleThreadVerilator {
  import InstructionEncoder._

  private val nLanes          = aWidth / 2
  private val outLanesPerTile = xDim * nLanes               // 32 (bench) / 64 (bench16)
  private val tileAccWidth    = log2Ceil(maxAcc) + aWidth
  private val outAccWidth     = tileAccWidth
  private val outAccModulus   = BigInt(1) << outAccWidth
  private val outLaneWidth    = 1 << log2Ceil(outAccWidth)
  private val axisDataWidth   = math.max(xDim, batchSize) * aWidth

  // Input-port split (mirrors CoreConfig/CoreTop): N 128-bit ports when > 128 b.
  private val numInPorts = if (axisDataWidth <= 128) 1 else math.min(4, (axisDataWidth + 127) / 128)
  private val portWidth  = axisDataWidth / numInPorts
  private val sliceMask  = (BigInt(1) << portWidth) - 1

  private val effOutBeatLanes = outBeatLanes
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

  private def mkTop = new CoreTop(
    aWidth       = aWidth,
    maxAcc       = maxAcc,
    xDim         = xDim,
    batchSize    = batchSize,
    maxN         = maxN,
    maxM         = maxM,
    outBeatLanes = effOutBeatLanes,
    axiAddrWidth = ADDR_WIDTH,
    axiDataWidth = DATA_WIDTH,
    axiIdWidth   = ID_WIDTH,
    signedAct    = signedAct
  )

  /** Activation generator: unsigned 0..15 (legacy presets), or a signed range
    * that exercises sign-extension (negative values set the activation's high
    * bit, which the engine must recover). */
  private def genAct(rng: Random): Int =
    if (signedAct) rng.nextInt(4096) - 2048 else rng.nextInt(16)

  private def resetDut(dut: CoreTop): Unit = {
    dut.s_axi.awvalid.poke(false.B)
    dut.s_axi.wvalid.poke(false.B)
    dut.s_axi.bready.poke(false.B)
    dut.s_axi.arvalid.poke(false.B)
    dut.s_axi.rready.poke(false.B)
    dut.s_axi.awid.poke(0.U)
    dut.s_axi.arid.poke(0.U)
    dut.sAxisPorts.foreach { p =>
      p.tvalid.poke(false.B)
      p.tdata.poke(0.U)
      p.tlast.poke(false.B)
    }
    dut.m_axis.tready.poke(false.B)
    dut.aresetn.poke(false.B)
    dut.aclk.step()
    dut.aresetn.poke(true.B)
    dut.aclk.step()
  }

  // ───────────────────────── AXI4 helpers ─────────────────────────
  // (Duplicated from CoreSpec to keep each spec self-contained — refactor into
  // a shared trait if a third spec needs them.)

  private def axiWrite(dut: CoreTop, addr: BigInt, data: BigInt, maxCycles: Int = 50): Int = {
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
      dut.aclk.step()
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

  /** Push wide beats, slicing each into numInPorts port-width slices fed in
    * lockstep (port 0 = LSBs — the CoreTop join's Cat order). With `stagger`
    * set, each port's tvalid is randomly deasserted some cycles, mimicking N
    * independent DMAs that are not cycle-aligned; the join must still accept
    * every beat on all ports simultaneously, so a single index suffices. */
  private def pushAxisBeats(
      dut: CoreTop, beats: Seq[BigInt],
      maxCyclesPerBeat: Int = 50, stagger: Option[Random] = None
  ): Unit = {
    var idx    = 0
    var budget = beats.length * maxCyclesPerBeat + 50
    while (idx < beats.length && budget > 0) {
      val gates = dut.sAxisPorts.indices.map(_ => stagger.forall(_.nextInt(4) > 0))
      for ((p, i) <- dut.sAxisPorts.zipWithIndex) {
        p.tdata.poke(((beats(idx) >> (i * portWidth)) & sliceMask).U)
        p.tvalid.poke(gates(i).B)
        p.tlast.poke((gates(i) && idx == beats.length - 1).B)
      }
      // A transfer happens only when every port is valid AND ready (the join
      // holds each port's tready low until all the others are valid).
      val accepts = gates.forall(identity) &&
        dut.sAxisPorts.head.tready.peek().litToBoolean
      dut.aclk.step()
      if (accepts) idx += 1
      budget -= 1
    }
    dut.sAxisPorts.foreach { p =>
      p.tvalid.poke(false.B)
      p.tlast.poke(false.B)
    }
    require(idx == beats.length, s"s_axis push: only $idx/${beats.length} beats accepted")
  }

  private def pullAxisBeats(dut: CoreTop, count: Int, maxCycles: Int = 4000): Seq[BigInt] = {
    dut.m_axis.tready.poke(true.B)
    val beats  = scala.collection.mutable.ArrayBuffer.empty[BigInt]
    var cycles = 0
    while (beats.length < count && cycles < maxCycles) {
      if (dut.m_axis.tvalid.peek().litToBoolean) {
        beats += dut.m_axis.tdata.peek().litValue
      }
      dut.aclk.step()
      cycles += 1
    }
    dut.m_axis.tready.poke(false.B)
    require(beats.length == count, s"m_axis pull: got ${beats.length}/$count beats")
    beats.toSeq
  }

  private def waitForIrq(dut: CoreTop, maxCycles: Int = 8000): Unit = {
    var c = 0
    while (!dut.irq.peek().litToBoolean && c < maxCycles) { dut.aclk.step(); c += 1 }
    require(dut.irq.peek().litToBoolean, s"IRQ never asserted within $maxCycles cycles")
  }

  private def ackIrq(dut: CoreTop): Unit = {
    axiWrite(dut, OFF_IRQ_ACK, BigInt(1)) mustBe OKAY
    dut.aclk.step()
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
      dut: CoreTop, acts: Array[Int], weightBeats: Seq[BigInt], n: Int, m: Int,
      stagger: Option[Random] = None
  ): Seq[BigInt] = {
    axiWrite(dut, OFF_INSTR, encode(LOAD_ACT, BigInt(0x100), BigInt(n), 0)) mustBe OKAY
    pushAxisBeats(dut, acts.map(packActBeat).toSeq, stagger = stagger)
    waitForIrq(dut); ackIrq(dut)

    axiWrite(dut, OFF_INSTR,
      encode(COMPUTE_MM, BigInt(0x300), BigInt(n), BigInt(m))) mustBe OKAY
    pushAxisBeats(dut, weightBeats, maxCyclesPerBeat = 200, stagger = stagger)
    waitForIrq(dut, maxCycles = 16000); ackIrq(dut)

    val totalOutputs = batchSize * m
    axiWrite(dut, OFF_INSTR,
      encode(STORE_OUT, BigInt(0x400), BigInt(totalOutputs), 0)) mustBe OKAY
    val numBeats = totalOutputs / effOutBeatLanes
    val raw = pullAxisBeats(dut, numBeats, maxCycles = 8000)
    waitForIrq(dut); ackIrq(dut)
    unpackOutputs(raw)
  }

  // ───────────────────── Batched (batchSize > 1) variants ─────────────────────
  // The engine is activation-stationary + weight-broadcast: one weight stream
  // feeds all `batchSize` PE rows, each row carrying its own activation vector.
  // So a batched op is: LOAD B-wide activation beats, COMPUTE the SAME weight
  // stream once, STORE B*m outputs (batch-major — see Core.scala drain rowAddr
  // = (batch*numColTiles + colTile)*outSubBeats + sub).

  /** One LOAD beat carrying all B batch items' activations for inner-dim row n:
    * item b occupies bits [(b+1)*aWidth-1 : b*aWidth], matching Core.scala sLoad's
    * `tdata((i+1)*aWidth-1, i*aWidth)` unpack. */
  private def packActBeatBatch(actsAtN: Seq[Int]): BigInt = {
    val mask = (BigInt(1) << aWidth) - 1
    var beat = BigInt(0)
    for (b <- 0 until batchSize) beat |= (BigInt(actsAtN(b)) & mask) << (b * aWidth)
    beat
  }

  /** Per-batch reference, flattened batch-major: out[b][j] = sum_n A[b][n]*W[n][j].
    * Layout matches the STORE stream order (all of batch 0's m columns, then
    * batch 1's, ...). */
  private def refMatmulBatch(acts: Array[Array[Int]], wt: Array[Array[Int]], n: Int, m: Int): Seq[BigInt] =
    (0 until batchSize).flatMap { b =>
      val out = Array.fill(m)(BigInt(0))
      for (i <- 0 until n; j <- 0 until m) out(j) += BigInt(wt(i)(j)) * BigInt(acts(b)(i))
      out.map(v => ((v % outAccModulus) + outAccModulus) % outAccModulus).toSeq
    }

  /** Batched LOAD_ACT → COMPUTE_MM → STORE_OUT. `acts` is [batchSize][n]; weights
    * are the same single-vector col-tile-major stream (broadcast to all rows).
    * Returns batchSize*m accumulators, batch-major. */
  private def runBenchFlowBatch(
      dut: CoreTop, acts: Array[Array[Int]], weightBeats: Seq[BigInt], n: Int, m: Int
  ): Seq[BigInt] = {
    axiWrite(dut, OFF_INSTR, encode(LOAD_ACT, BigInt(0x100), BigInt(n), 0)) mustBe OKAY
    val actBeats = (0 until n).map(i => packActBeatBatch((0 until batchSize).map(b => acts(b)(i))))
    pushAxisBeats(dut, actBeats)
    waitForIrq(dut); ackIrq(dut)

    axiWrite(dut, OFF_INSTR,
      encode(COMPUTE_MM, BigInt(0x300), BigInt(n), BigInt(m))) mustBe OKAY
    pushAxisBeats(dut, weightBeats, maxCyclesPerBeat = 400)
    waitForIrq(dut, maxCycles = 64000); ackIrq(dut)

    val totalOutputs = batchSize * m
    axiWrite(dut, OFF_INSTR,
      encode(STORE_OUT, BigInt(0x400), BigInt(totalOutputs), 0)) mustBe OKAY
    val numBeats = totalOutputs / effOutBeatLanes
    val raw = pullAxisBeats(dut, numBeats, maxCycles = 32000)
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

  s"Bench mirror at $geomName params (aWidth=$aWidth, xDim=$xDim, batchSize=$batchSize)" - {

    // Single-vector flow tests: these LOAD one activation/beat and check m
    // outputs, so they are only meaningful at batchSize == 1. The batched
    // subclasses run the B-wide tests below instead.
    if (batchSize == 1) {
    "single col tile: bench-style packing matches ref matmul" in {
      simulateRaw(mkTop) { dut =>
        resetDut(dut)
        val rng = new Random(1L)
        val n   = 16
        val m   = outLanesPerTile        // exactly one col tile
        val acts = Array.fill(n)(genAct(rng))
        val wt   = randomTernaryMatrix(rng, n, m)
        val beats = packBenchWeightBuffer(wt, n, m)
        runBenchFlow(dut, acts, beats, n, m) mustBe refMatmul(acts, wt, n, m)
      }
    }

    "multi col tile: bench-style packing matches ref matmul (3 seeds)" in {
      Seq(7L, 42L, 256L).foreach { seed =>
        simulateRaw(mkTop) { dut =>
          resetDut(dut)
          val rng = new Random(seed)
          val numColTiles = 2 + rng.nextInt(2)             // 2..3
          val m = numColTiles * outLanesPerTile
          val n = 16 + rng.nextInt(33)                     // 16..48
          val acts = Array.fill(n)(genAct(rng))
          val wt   = randomTernaryMatrix(rng, n, m)
          val beats = packBenchWeightBuffer(wt, n, m)
          val actual   = runBenchFlow(dut, acts, beats, n, m)
          val expected = refMatmul(acts, wt, n, m)
          withClue(s"seed=$seed n=$n m=$m") { actual mustBe expected }
        }
      }
    }

    "edge: negative-only accumulator wraps to outAccWidth correctly" in {
      simulateRaw(mkTop) { dut =>
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
      simulateRaw(mkTop) { dut =>
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

    if (maxN >= 2816) {
      "large non-pow2 inner dim (down_proj-like N=2816) matches ref matmul" in {
        simulateRaw(mkTop) { dut =>
          resetDut(dut)
          val rng = new Random(2816L)
          val n = 2816                       // MMfreeLM-370M down_proj inner dim
          val m = outLanesPerTile            // one col tile keeps sim time sane
          val acts = Array.fill(n)(genAct(rng))
          val wt   = randomTernaryMatrix(rng, n, m)
          val beats = packBenchWeightBuffer(wt, n, m)
          runBenchFlow(dut, acts, beats, n, m) mustBe refMatmul(acts, wt, n, m)
        }
      }
    }
    }  // end if (batchSize == 1) single-vector tests

    if (batchSize > 1) {
      s"batched: $batchSize vectors share one weight stream, match per-batch ref (2 seeds)" in {
        Seq(11L, 73L).foreach { seed =>
          simulateRaw(mkTop) { dut =>
            resetDut(dut)
            val rng = new Random(seed)
            val numColTiles = 1 + rng.nextInt(2)             // 1..2
            val m = numColTiles * outLanesPerTile
            val n = 12 + rng.nextInt(20)                     // 12..31
            val acts = Array.fill(batchSize, n)(genAct(rng)) // [B][N], distinct per row
            val wt   = randomTernaryMatrix(rng, n, m)
            val beats = packBenchWeightBuffer(wt, n, m)
            val actual   = runBenchFlowBatch(dut, acts, beats, n, m)
            val expected = refMatmulBatch(acts, wt, n, m)
            withClue(s"seed=$seed B=$batchSize n=$n m=$m") { actual mustBe expected }
          }
        }
      }

      "batched: distinct per-row activations are not cross-contaminated" in {
        simulateRaw(mkTop) { dut =>
          resetDut(dut)
          val n = 16
          val m = outLanesPerTile                            // one col tile
          // Row b is the constant (b+1); each row's column-sum is (b+1)*colWeightSum,
          // so any row/accumulator bleed shows up as a wrong multiple.
          val acts = Array.tabulate(batchSize, n)((b, _) => b + 1)
          val wt   = randomTernaryMatrix(new Random(5L), n, m)
          val beats = packBenchWeightBuffer(wt, n, m)
          val actual   = runBenchFlowBatch(dut, acts, beats, n, m)
          val expected = refMatmulBatch(acts, wt, n, m)
          actual mustBe expected
        }
      }
    }

    if (batchSize == 1 && numInPorts > 1) {
      s"staggered port valids: $numInPorts-way join loses no beats" in {
        simulateRaw(mkTop) { dut =>
          resetDut(dut)
          val rng = new Random(99L)
          val numColTiles = 2
          val m = numColTiles * outLanesPerTile
          val n = 24
          val acts = Array.fill(n)(genAct(rng))
          val wt   = randomTernaryMatrix(rng, n, m)
          val beats = packBenchWeightBuffer(wt, n, m)
          val actual = runBenchFlow(dut, acts, beats, n, m, stagger = Some(rng))
          actual mustBe refMatmul(acts, wt, n, m)
        }
      }
    }
  }
}

/** K26_Bench geometry (aWidth=16, xDim=4). maxAcc / maxN / maxM scaled down
  * for sim speed — the packing logic is identical at any size so a 2-3
  * col-tile workload is sufficient to catch ordering bugs. */
class BenchMirrorSpec extends BenchMirrorSpecBase(
  geomName = "K26_Bench", aWidth = 16, maxAcc = 256, xDim = 4, maxN = 128, maxM = 128)

/** K26_Bench16 geometry (aWidth=8, xDim=16 → 64-lane col tiles, 128-bit
  * s_axis). maxAcc kept at the real 4096 so outAccWidth=20 / outLaneWidth=32
  * match the deployed bitstream's m_axis packing exactly. maxM=192 fits the
  * multi-tile test's worst case of 3 col tiles x 64 lanes. */
class BenchMirror16Spec extends BenchMirrorSpecBase(
  geomName = "K26_Bench16", aWidth = 8, maxAcc = 4096, xDim = 16, maxN = 128, maxM = 192)

/** K26_Bench32 geometry (aWidth=8, xDim=32 → 256-bit s_axis split across N=2
  * 128-bit ports). maxM=384 fits 3 col tiles x 128 lanes. */
class BenchMirror32Spec extends BenchMirrorSpecBase(
  geomName = "K26_Bench32", aWidth = 8, maxAcc = 4096, xDim = 32, maxN = 128, maxM = 384)

/** K26_Bench64 geometry (aWidth=8, xDim=64 → 512-bit s_axis split across N=4
  * 128-bit ports). maxM=768 fits 3 col tiles x 256 lanes. */
class BenchMirror64Spec extends BenchMirrorSpecBase(
  geomName = "K26_Bench64", aWidth = 8, maxAcc = 4096, xDim = 64, maxN = 128, maxM = 768)

/** K26_MMFree370M geometry at FULL size (maxN=4096, maxM=32000) so the real
  * register widths are elaborated (15-bit cmpCols, 125-col-tile counters,
  * 4096-entry act BRAM) and the N=2816 non-pow2 inner dim (down_proj) runs
  * through the 4-way port join. Datapath is otherwise BenchMirror64Spec's. */
class BenchMirror370MSpec extends BenchMirrorSpecBase(
  geomName = "K26_MMFree370M", aWidth = 8, maxAcc = 4096, xDim = 64, maxN = 4096, maxM = 32000)

/** K26_MMFree370M_A16 geometry (aWidth=16, xDim=32 → 512-bit s_axis split across
  * N=4 ports, SIGNED activations). Reduced maxN/maxM for sim speed; the point is
  * to exercise the signed sign-extension path end-to-end through the 4-way join
  * with negative activations (genAct returns [-2048,2047]). outLanesPerTile=256
  * (=32*8), so maxM=512 spans 2 col tiles. */
class BenchMirror370M_A16Spec extends BenchMirrorSpecBase(
  geomName = "K26_MMFree370M_A16", aWidth = 16, maxAcc = 4096, xDim = 32,
  maxN = 128, maxM = 768, signedAct = true)

// ─── Batched (batchSize > 1) specs — Phase 0 hardening of the Core FSM batch
//     path (the SystolicArray grid is already verified at yDim=2 in
//     SystolicArraySpec; these close the LOAD/drain/STORE wrapper gap). ───

/** Small/fast batch geometry (xDim=4, single 32-bit port): primary correctness
  * for B=2 and B=4 — exercises the B-wide LOAD, B-row compute, batch-major
  * drain and B*m STORE cheaply. */
class BenchMirrorBatch2Spec extends BenchMirrorSpecBase(
  geomName = "Batch-small", aWidth = 8, maxAcc = 256, xDim = 4,
  maxN = 128, maxM = 128, batchSize = 2)

class BenchMirrorBatch4Spec extends BenchMirrorSpecBase(
  geomName = "Batch-small", aWidth = 8, maxAcc = 256, xDim = 4,
  maxN = 128, maxM = 128, batchSize = 4)

/** Realistic deployed geometry batched: a16 (aWidth=16, xDim=32 → 256-lane col
  * tiles, 4-way port join, signed activations), batchSize=2. Reduced maxN/maxM
  * for sim speed; the point is the full batched datapath at the real array
  * width. (Drain = B*256 cycles/tile here — slow in sim but functionally what
  * the bitstream does.) */
class BenchMirror370M_A16_B2Spec extends BenchMirrorSpecBase(
  geomName = "K26_MMFree370M_A16_B2", aWidth = 16, maxAcc = 4096, xDim = 32,
  maxN = 64, maxM = 512, batchSize = 2, signedAct = true)

// ─── outBeatLanes=4 (128-bit m_axis) batched specs — the store-bandwidth config
//     (board B=4 showed the 32-bit store was the batch bottleneck). Exercise the
//     4-lane drain + multi-lane m_axis beats together with batch. The batched a16
//     presets (k26_mmfree370m_a16_b{2,4,8}) now carry outBeatLanes=4. ───
class BenchMirrorBatch4_Obl4Spec extends BenchMirrorSpecBase(
  geomName = "Batch-small-obl4", aWidth = 8, maxAcc = 256, xDim = 4,
  maxN = 128, maxM = 128, batchSize = 4, outBeatLanes = 4)

class BenchMirror370M_A16_B4_Obl4Spec extends BenchMirrorSpecBase(
  geomName = "K26_MMFree370M_A16_B4", aWidth = 16, maxAcc = 4096, xDim = 32,
  maxN = 64, maxM = 512, batchSize = 4, outBeatLanes = 4, signedAct = true)

// B=6: the K26 device sweet spot (B8 overflowed the fabric — see CoreConfig
// K26_MMFree370M_A16_B6). Non-power-of-2 batch exercises the arithmetic batch
// addressing (drainBatchCtr*numColTiles+…, outBram depth 6*…) at the real a16
// array width with the 4-lane store.
class BenchMirror370M_A16_B6_Obl4Spec extends BenchMirrorSpecBase(
  geomName = "K26_MMFree370M_A16_B6", aWidth = 16, maxAcc = 4096, xDim = 32,
  maxN = 64, maxM = 512, batchSize = 6, outBeatLanes = 4, signedAct = true)
