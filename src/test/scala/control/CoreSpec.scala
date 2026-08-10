package control

import scala.util.Random
import chisel3._
import chisel3.util.log2Ceil
import systolic.TernaryPack
import testutil.SingleThreadVerilator
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CoreSpec extends AnyFreeSpec with Matchers with SingleThreadVerilator {
  import InstructionEncoder._

  // Tiny config: xDim != batchSize to exercise the asymmetric layout. Default LM-head
  // semantics use much larger params, but the small one keeps Verilator fast.
  private val aWidth       = 4
  private val maxAcc       = 8
  private val xDim         = 2
  private val batchSize    = 2
  private val maxN         = 8
  private val maxM         = 16

  private val nLanes          = aWidth / 2
  private val outLanesPerTile = xDim * nLanes
  private val tileAccWidth    = log2Ceil(maxAcc) + aWidth
  private val outAccWidth     = tileAccWidth
  private val outAccModulus   = BigInt(1) << outAccWidth
  private val outLaneWidth    = 1 << log2Ceil(outAccWidth)
  private val axisDataWidth   = math.max(xDim, batchSize) * aWidth

  // No output chunking in the tiny config.
  private val effOutBeatLanes = outLanesPerTile
  private val outSubBeats     = outLanesPerTile / effOutBeatLanes
  private val outBeatWidth    = effOutBeatLanes * outLaneWidth

  private val ADDR_WIDTH = 32
  private val DATA_WIDTH = 128
  private val ID_WIDTH   = 6
  private val STRB_ALL   = (BigInt(1) << (DATA_WIDTH / 8)) - 1

  private val OFF_INSTR   = BigInt(0x000)
  private val OFF_STATUS  = BigInt(0x010)
  private val OFF_IRQ_ACK = BigInt(0x020)

  private val OKAY = 0

  // ───────────────────────── Setup ─────────────────────────

  private def mkCore = new Core(
    aWidth       = aWidth,
    maxAcc       = maxAcc,
    xDim         = xDim,
    batchSize    = batchSize,
    maxN         = maxN,
    maxM         = maxM,
    outBeatLanes = 0,
    axiAddrWidth = ADDR_WIDTH,
    axiDataWidth = DATA_WIDTH,
    axiIdWidth   = ID_WIDTH
  )

  private def mkCorePacked = new Core(
    aWidth       = aWidth,
    maxAcc       = maxAcc,
    xDim         = xDim,
    batchSize    = batchSize,
    maxN         = maxN,
    maxM         = maxM,
    outBeatLanes = 0,
    axiAddrWidth = ADDR_WIDTH,
    axiDataWidth = DATA_WIDTH,
    axiIdWidth   = ID_WIDTH,
    packedWeights = true
  )

  /** Dense 1.6-bit weight stream: flatten the col-tile-major weight beats into one
    * trit sequence (lane order == the gearbox's), base-3 pack 5/byte, then chunk into
    * axisDataWidth-bit wire beats (last beat zero-padded). Mirrors what the offline
    * packer must emit for a packedWeights bitstream. */
  private def packWeightStreamDense(weightStream: Seq[Seq[Int]]): Seq[BigInt] = {
    require(axisDataWidth % 8 == 0)
    val bytes        = TernaryPack.packCodes(weightStream.flatten)
    val bytesPerBeat = axisDataWidth / 8
    bytes.grouped(bytesPerBeat).map { g =>
      g.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (b, j)) => acc | (BigInt(b) << (8 * j)) }
    }.toSeq
  }

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

  private def axiRead(dut: Core, addr: BigInt, maxCycles: Int = 50): (BigInt, Int) = {
    dut.s_axi.arid.poke(0.U)
    dut.s_axi.araddr.poke(addr.U)
    dut.s_axi.arlen.poke(0.U)
    dut.s_axi.arsize.poke(4.U)
    dut.s_axi.arburst.poke(1.U)
    dut.s_axi.arvalid.poke(true.B)
    dut.s_axi.rready.poke(true.B)
    var arA = true; var rR = true
    var data = BigInt(0); var resp = 0; var done = false; var cycles = 0
    while (!done && cycles < maxCycles) {
      val arF = arA && dut.s_axi.arready.peek().litToBoolean
      val rF  = rR  && dut.s_axi.rvalid.peek().litToBoolean
      if (rF) { data = dut.s_axi.rdata.peek().litValue; resp = dut.s_axi.rresp.peek().litValue.toInt; done = true }
      dut.clock.step()
      if (arF) { dut.s_axi.arvalid.poke(false.B); arA = false }
      if (rF)  { dut.s_axi.rready.poke(false.B);  rR  = false }
      cycles += 1
    }
    require(done, s"axiRead from 0x${addr.toString(16)} timed out")
    dut.s_axi.arvalid.poke(false.B)
    dut.s_axi.rready.poke(false.B)
    (data, resp)
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

  private def pullAxisBeats(dut: Core, count: Int, maxCycles: Int = 500): Seq[BigInt] = {
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

  private def waitForIrq(dut: Core, maxCycles: Int = 1000): Unit = {
    var c = 0
    while (!dut.irq.peek().litToBoolean && c < maxCycles) { dut.clock.step(); c += 1 }
    require(dut.irq.peek().litToBoolean, s"IRQ never asserted within $maxCycles cycles")
  }

  private def ackIrq(dut: Core): Unit = {
    axiWrite(dut, OFF_IRQ_ACK, BigInt(1)) mustBe OKAY
    dut.clock.step()
  }

  // ───────────────────────── Packers / unpackers ─────────────────────────

  /** Pack `batchSize` activations (low aWidth bits each) into one s_axis beat. */
  private def packActivations(acts: Seq[Int]): BigInt = {
    require(acts.length == batchSize)
    val mask = (BigInt(1) << aWidth) - 1
    acts.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (a, i)) =>
      acc | ((BigInt(a) & mask) << (i * aWidth))
    }
  }

  /** Pack outLanesPerTile ternary weights (each 2 bits) into one s_axis beat. */
  private def packWeights(ws: Seq[Int]): BigInt = {
    require(ws.length == outLanesPerTile)
    ws.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (w, i)) =>
      acc | ((BigInt(w) & 0x3) << (i * 2))
    }
  }

  /** Unpack a stream of m_axis beats into a flat sequence of outAccWidth-sized lane values. */
  private def unpackOutputs(beats: Seq[BigInt]): Seq[BigInt] = {
    val mask = outAccModulus - 1
    beats.flatMap { beat =>
      (0 until effOutBeatLanes).map { i =>
        (beat >> (i * outLaneWidth)) & mask
      }
    }
  }

  // ───────────────────────── Reference model ─────────────────────────

  /**
    * Software reference for the redesigned Core.
    *
    *   - `acts`: shape [n][batchSize]  — one activation beat per inner-dim cycle.
    *   - `weightStream`: numColTiles * n beats, col-tile-major. Each beat is
    *     outLanesPerTile ternary values (one cycle's worth of weights for one tile).
    *
    * Returns a flat batch-major, col-ascending output stream matching what the
    * Core emits on m_axis: out[b * cols + col] = sum_n A[n][b] * sign(W[n][col]).
    */
  private def referenceCompute(
      acts:         Seq[Seq[Int]],
      weightStream: Seq[Seq[Int]],
      n:            Int,
      m:            Int
  ): Seq[BigInt] = {
    val numColTiles = m / outLanesPerTile
    require(acts.length == n)
    require(weightStream.length == numColTiles * n)

    val out = Array.fill(batchSize, m)(BigInt(0))
    for (ct <- 0 until numColTiles; t <- 0 until n) {
      val beat = weightStream(ct * n + t)
      for (b <- 0 until batchSize) {
        val a = BigInt(acts(t)(b))
        for (lane <- 0 until outLanesPerTile) {
          val col = ct * outLanesPerTile + lane
          beat(lane) match {
            case 1 => out(b)(col) += a
            case 3 => out(b)(col) -= a
            case _ => // hold
          }
        }
      }
    }
    out.flatMap(_.map(v => ((v % outAccModulus) + outAccModulus) % outAccModulus)).toSeq
  }

  // ───────────────────────── Pipeline driver ─────────────────────────

  private def runPipeline(
      dut:          Core,
      acts:         Seq[Seq[Int]],          // [n][batchSize]
      weightStream: Seq[Seq[Int]],          // numColTiles*n beats
      n:            Int,
      m:            Int,
      packed:       Boolean = false         // stream weights 1.6-bit (needs a packedWeights dut)
  ): Seq[BigInt] = {
    val numColTiles = m / outLanesPerTile
    require(weightStream.length == numColTiles * n)

    // LOAD: n beats, each beat = batchSize activations.
    axiWrite(dut, OFF_INSTR, encode(LOAD_ACT, BigInt(0x100), BigInt(n), 0)) mustBe OKAY
    pushAxisBeats(dut, acts.map(packActivations))
    waitForIrq(dut); ackIrq(dut)

    // COMPUTE: rows=n, cols=m. Weights go out either 2-bit-per-beat (legacy) or as
    // the dense 1.6-bit stream the gearbox unpacks — same numeric result.
    val weightBeats = if (packed) packWeightStreamDense(weightStream) else weightStream.map(packWeights)
    axiWrite(dut, OFF_INSTR, encode(COMPUTE_MM, BigInt(0x300), BigInt(n), BigInt(m))) mustBe OKAY
    pushAxisBeats(dut, weightBeats, maxCyclesPerBeat = 200)
    waitForIrq(dut, maxCycles = 4000); ackIrq(dut)

    // STORE: emit batchSize * m total outputs
    val totalOutputs = batchSize * m
    axiWrite(dut, OFF_INSTR, encode(STORE_OUT, BigInt(0x400), BigInt(totalOutputs), 0)) mustBe OKAY
    val numBeats = totalOutputs / effOutBeatLanes
    val raw = pullAxisBeats(dut, count = numBeats, maxCycles = 2000)
    waitForIrq(dut); ackIrq(dut)
    unpackOutputs(raw)
  }

  // ──────────────────────────────────────────────────────────────────────
  // Tests
  // ──────────────────────────────────────────────────────────────────────

  "Core (redesigned)" - {

    "LOAD_ACT smoke: one beat per inner-dim position" in {
      simulate(mkCore) { dut =>
        resetDut(dut)
        val n = 4
        axiWrite(dut, OFF_INSTR, encode(LOAD_ACT, BigInt(0x100), BigInt(n), 0)) mustBe OKAY
        pushAxisBeats(dut, Seq.fill(n)(packActivations(Seq.fill(batchSize)(1))))
        waitForIrq(dut); ackIrq(dut)
      }
    }

    "COMPUTE_MM single col tile smoke" in {
      simulate(mkCore) { dut =>
        resetDut(dut)
        val n = 4
        axiWrite(dut, OFF_INSTR, encode(LOAD_ACT, BigInt(0x100), BigInt(n), 0)) mustBe OKAY
        pushAxisBeats(dut, Seq.fill(n)(packActivations(Seq.fill(batchSize)(1))))
        waitForIrq(dut); ackIrq(dut)

        axiWrite(dut, OFF_INSTR,
          encode(COMPUTE_MM, BigInt(0x300), BigInt(n), BigInt(outLanesPerTile))) mustBe OKAY
        pushAxisBeats(dut, Seq.fill(n)(packWeights(Seq.fill(outLanesPerTile)(1))),
                      maxCyclesPerBeat = 100)
        waitForIrq(dut, maxCycles = 500); ackIrq(dut)
      }
    }

    "STORE_OUT smoke: emits batchSize × M values" in {
      simulate(mkCore) { dut =>
        resetDut(dut)
        val n = 4
        val m = outLanesPerTile
        axiWrite(dut, OFF_INSTR, encode(LOAD_ACT, BigInt(0x100), BigInt(n), 0)) mustBe OKAY
        pushAxisBeats(dut, Seq.fill(n)(packActivations(Seq.fill(batchSize)(1))))
        waitForIrq(dut); ackIrq(dut)

        axiWrite(dut, OFF_INSTR,
          encode(COMPUTE_MM, BigInt(0x300), BigInt(n), BigInt(m))) mustBe OKAY
        pushAxisBeats(dut, Seq.fill(n)(packWeights(Seq.fill(outLanesPerTile)(1))),
                      maxCyclesPerBeat = 100)
        waitForIrq(dut); ackIrq(dut)

        val totalOutputs = batchSize * m
        axiWrite(dut, OFF_INSTR,
          encode(STORE_OUT, BigInt(0x400), BigInt(totalOutputs), 0)) mustBe OKAY
        val beats = pullAxisBeats(dut, count = totalOutputs / effOutBeatLanes)
        beats.length mustBe totalOutputs / effOutBeatLanes
        waitForIrq(dut); ackIrq(dut)
      }
    }

    // ──────────────────────────────────────────────────────────────────
    // Numeric correctness
    // ──────────────────────────────────────────────────────────────────

    "numeric: constant activations × all-+1 weights → per-batch sum on every lane" in {
      simulate(mkCore) { dut =>
        resetDut(dut)
        val n = 4
        val m = outLanesPerTile
        // batch 0 activation = 2 every cycle; batch 1 activation = 3 every cycle.
        val acts = Seq.fill(n)(Seq(2, 3))
        val weights = Seq.fill(n)(Seq.fill(outLanesPerTile)(1))
        val actual   = runPipeline(dut, acts, weights, n, m)
        val expected = referenceCompute(acts, weights, n, m)
        actual mustBe expected
        // batch 0: every output = n * 2 = 8; batch 1: every output = n * 3 = 12
        (0 until m).foreach { col => actual(col)         mustBe BigInt(8) }
        (0 until m).foreach { col => actual(m + col)     mustBe BigInt(12) }
      }
    }

    "numeric: ternary mixed weights and varying activations match reference" in {
      simulate(mkCore) { dut =>
        resetDut(dut)
        val n = 4
        val m = outLanesPerTile
        val acts = Seq(Seq(1, 2), Seq(3, 4), Seq(0, 5), Seq(2, 1))
        val pat  = Seq(1, 3, 0, 1) // length outLanesPerTile = 4
        val weights = Seq.fill(n)(pat)
        val actual   = runPipeline(dut, acts, weights, n, m)
        val expected = referenceCompute(acts, weights, n, m)
        actual mustBe expected
      }
    }

    "numeric: multi-col-tile produces independent per-tile outputs" in {
      simulate(mkCore) { dut =>
        resetDut(dut)
        val n = 4
        val m = 2 * outLanesPerTile
        val acts = Seq.fill(n)(Seq(2, 1))
        val tile0 = Seq.fill(n)(Seq(1, 1, 1, 1))   // col tile 0 → +sum
        val tile1 = Seq.fill(n)(Seq(3, 3, 3, 3))   // col tile 1 → -sum
        val weights = tile0 ++ tile1
        val actual   = runPipeline(dut, acts, weights, n, m)
        val expected = referenceCompute(acts, weights, n, m)
        actual mustBe expected
        // batch 0: tile 0 = +8, tile 1 = -8 mod ; batch 1: tile 0 = +4, tile 1 = -4 mod
        actual(0)                          mustBe BigInt(8)
        actual(outLanesPerTile)            mustBe ((BigInt(-8) % outAccModulus) + outAccModulus) % outAccModulus
        actual(m + 0)                      mustBe BigInt(4)
        actual(m + outLanesPerTile)        mustBe ((BigInt(-4) % outAccModulus) + outAccModulus) % outAccModulus
      }
    }

    "numeric: random workloads match reference (5 seeds)" in {
      val seeds = Seq(1L, 7L, 42L, 99L, 256L)
      seeds.foreach { seed =>
        simulate(mkCore) { dut =>
          resetDut(dut)
          val rng = new Random(seed)
          val n   = 4 + rng.nextInt(maxN - 3)        // 4..maxN
          val numColTiles = 1 + rng.nextInt(maxM / outLanesPerTile)
          val m = numColTiles * outLanesPerTile
          val acts = Seq.fill(n)(Seq.fill(batchSize)(rng.nextInt(1 << aWidth)))
          val weights = Seq.fill(numColTiles * n) {
            Seq.fill(outLanesPerTile)(Seq(0, 1, 3)(rng.nextInt(3)))
          }
          val actual   = runPipeline(dut, acts, weights, n, m)
          val expected = referenceCompute(acts, weights, n, m)
          withClue(s"seed=$seed n=$n m=$m") { actual mustBe expected }
        }
      }
    }

    // ──────────────────────────────────────────────────────────────────
    // 1.6-bit packed weights (packedWeights): identical results to the
    // 2-bit path — the config lever is numerically transparent.
    // ──────────────────────────────────────────────────────────────────

    "packed: random workloads match the 2-bit path and the reference (5 seeds)" in {
      val seeds = Seq(1L, 7L, 42L, 99L, 256L)
      seeds.foreach { seed =>
        val rng = new Random(seed)
        val n   = 4 + rng.nextInt(maxN - 3)
        val numColTiles = 1 + rng.nextInt(maxM / outLanesPerTile)
        val m = numColTiles * outLanesPerTile
        val acts = Seq.fill(n)(Seq.fill(batchSize)(rng.nextInt(1 << aWidth)))
        val weights = Seq.fill(numColTiles * n) {
          Seq.fill(outLanesPerTile)(Seq(0, 1, 3)(rng.nextInt(3)))
        }
        val expected = referenceCompute(acts, weights, n, m)
        // ~20% fewer weight bytes on the wire (regrouped, still-full beats).
        val packedBeats   = packWeightStreamDense(weights).length
        val unpackedBeats = weights.length
        withClue(s"seed=$seed: packed beats $packedBeats should be < unpacked $unpackedBeats") {
          (packedBeats <= unpackedBeats) mustBe true
        }
        simulate(mkCorePacked) { dut =>
          resetDut(dut)
          val actual = runPipeline(dut, acts, weights, n, m, packed = true)
          withClue(s"seed=$seed n=$n m=$m (packed)") { actual mustBe expected }
        }
      }
    }

    "packed: back-to-back COMPUTEs stay aligned (buffer flush between ops)" in {
      simulate(mkCorePacked) { dut =>
        resetDut(dut)
        val n = 5                                  // 5*4=20 trits/tile → 4 bytes, non-5-aligned tail
        val m = outLanesPerTile
        def rndWeights(r: Random) = Seq.fill(n)(Seq.fill(outLanesPerTile)(Seq(0, 1, 3)(r.nextInt(3))))
        val acts = Seq.fill(n)(Seq.fill(batchSize)(1 + Random.nextInt(3)))
        // Two full passes with different weights: the second COMPUTE must not inherit
        // stale padding trits left in the gearbox by the first (flush at sIdle).
        val w1 = rndWeights(new Random(11))
        val w2 = rndWeights(new Random(22))
        runPipeline(dut, acts, w1, n, m, packed = true) mustBe referenceCompute(acts, w1, n, m)
        runPipeline(dut, acts, w2, n, m, packed = true) mustBe referenceCompute(acts, w2, n, m)
      }
    }

    // The HW contract that column-tile splitting rests on: a projection can be run as
    // several COMPUTEs over contiguous slices of its output column tiles, all sharing ONE
    // LOAD_ACT, and the concatenated results equal the whole projection. This is what lets
    // the host pipeline a big projection against itself (hide chunk j-1's readback under
    // chunk j's COMPUTE) with no re-pack and no re-LOAD. Two facts make it work, and this
    // test pins both: the weight stream is col-tile-major (beat ct*n+t), so a tile range is
    // a CONTIGUOUS beat slice; and sCompute only READS actBram (a SyncReadMem written by
    // sLoad), resetting nothing but its own pointers, so the activations survive.
    // Chunk sizes are deliberately UNEVEN (1 tile then 2) — the host picks boundaries from
    // blob alignment, not evenness, so the RTL must not assume equal chunks.
    "split: per-column-tile-chunk COMPUTEs on one LOAD == the whole projection" in {
      simulate(mkCore) { dut =>
        val n = 4
        val tiles = 3
        val m = tiles * outLanesPerTile
        val r = new Random(0x5A5A17)
        val acts = Seq.fill(n)(Seq.fill(batchSize)(1 + r.nextInt(3)))
        val weightStream =
          Seq.fill(tiles * n)(Seq.fill(outLanesPerTile)(Seq(0, 1, 3)(r.nextInt(3))))

        resetDut(dut)
        val whole = runPipeline(dut, acts, weightStream, n, m)
        whole mustBe referenceCompute(acts, weightStream, n, m)

        // Same projection, now as chunks: ONE LOAD, then COMPUTE+STORE per tile range.
        resetDut(dut)
        axiWrite(dut, OFF_INSTR, encode(LOAD_ACT, BigInt(0x100), BigInt(n), 0)) mustBe OKAY
        pushAxisBeats(dut, acts.map(packActivations))
        waitForIrq(dut); ackIrq(dut)

        val got = Array.fill(batchSize, m)(BigInt(0))
        for ((ct0, ct1) <- Seq((0, 1), (1, 3))) {
          val cm = (ct1 - ct0) * outLanesPerTile
          axiWrite(dut, OFF_INSTR,
            encode(COMPUTE_MM, BigInt(0x300), BigInt(n), BigInt(cm))) mustBe OKAY
          pushAxisBeats(dut, weightStream.slice(ct0 * n, ct1 * n).map(packWeights),
                        maxCyclesPerBeat = 100)
          waitForIrq(dut); ackIrq(dut)
          axiWrite(dut, OFF_INSTR,
            encode(STORE_OUT, BigInt(0x400), BigInt(batchSize * cm), 0)) mustBe OKAY
          val vals = unpackOutputs(pullAxisBeats(dut, batchSize * cm / effOutBeatLanes))
          waitForIrq(dut); ackIrq(dut)
          // Chunk output is batch-major over ITS cm lanes; scatter to absolute columns.
          for (bb <- 0 until batchSize; l <- 0 until cm)
            got(bb)(ct0 * outLanesPerTile + l) = vals(bb * cm + l)
        }
        got.flatMap(_.toSeq).toSeq mustBe whole
      }
    }

    // ──────────────────────────────────────────────────────────────────
    // Edge cases
    // ──────────────────────────────────────────────────────────────────

    "edge: re-COMPUTE without intervening LOAD reuses the prior activations" in {
      simulate(mkCore) { dut =>
        resetDut(dut)
        val n = 4
        val m = outLanesPerTile

        // LOAD once.
        val acts = Seq(Seq(1, 5), Seq(2, 6), Seq(3, 7), Seq(4, 8))
        axiWrite(dut, OFF_INSTR, encode(LOAD_ACT, BigInt(0x100), BigInt(n), 0)) mustBe OKAY
        pushAxisBeats(dut, acts.map(packActivations))
        waitForIrq(dut); ackIrq(dut)

        def computeStore(weights: Seq[Seq[Int]]): Seq[BigInt] = {
          axiWrite(dut, OFF_INSTR,
            encode(COMPUTE_MM, BigInt(0x300), BigInt(n), BigInt(m))) mustBe OKAY
          pushAxisBeats(dut, weights.map(packWeights), maxCyclesPerBeat = 100)
          waitForIrq(dut); ackIrq(dut)
          axiWrite(dut, OFF_INSTR,
            encode(STORE_OUT, BigInt(0x400), BigInt(batchSize * m), 0)) mustBe OKAY
          val raw = pullAxisBeats(dut, batchSize * m / effOutBeatLanes)
          waitForIrq(dut); ackIrq(dut)
          unpackOutputs(raw)
        }

        val w1 = Seq.fill(n)(Seq.fill(outLanesPerTile)(1))
        val w2 = Seq.fill(n)(Seq.fill(outLanesPerTile)(3))
        computeStore(w1) mustBe referenceCompute(acts, w1, n, m)
        computeStore(w2) mustBe referenceCompute(acts, w2, n, m)
      }
    }

    "edge: re-LOAD overwrites activation BRAM cleanly" in {
      simulate(mkCore) { dut =>
        resetDut(dut)
        val n = 4
        val m = outLanesPerTile
        val a1 = Seq.fill(n)(Seq(1, 2))
        val a2 = Seq.fill(n)(Seq(5, 7))
        val weights = Seq.fill(n)(Seq.fill(outLanesPerTile)(1))

        runPipeline(dut, a1, weights, n, m) mustBe referenceCompute(a1, weights, n, m)
        runPipeline(dut, a2, weights, n, m) mustBe referenceCompute(a2, weights, n, m)
      }
    }

    // ──────────────────────────────────────────────────────────────────
    // Runtime batch count (instruction flags field). A high-B bitstream must
    // serve a low-batch call by draining only the requested rows. Proven
    // deterministically: a full-batch compute seeds BOTH outBram rows, then a
    // batch=1 compute with different weights must overwrite ONLY row 0 and
    // leave row 1 stale — so the drain bound is genuinely runtime, not batchSize.
    // ──────────────────────────────────────────────────────────────────

    "runtime batch: batch=1 compute drains only row 0, leaving row 1 stale" in {
      simulate(mkCore) { dut =>
        resetDut(dut)
        val n = 4
        val m = outLanesPerTile                 // single col tile (numColTiles = 1)
        val acts = Seq.fill(n)(Seq(2, 3))       // batch0 act=2, batch1 act=3 per cycle
        val w1   = Seq.fill(n)(Seq.fill(outLanesPerTile)(1))  // +sum
        val w2   = Seq.fill(n)(Seq.fill(outLanesPerTile)(3))  // -sum

        // LOAD once — activations persist across both computes.
        axiWrite(dut, OFF_INSTR, encode(LOAD_ACT, BigInt(0x100), BigInt(n), 0)) mustBe OKAY
        pushAxisBeats(dut, acts.map(packActivations))
        waitForIrq(dut); ackIrq(dut)

        // COMPUTE #1: full batch (flags=0). Seeds outBram row0=+8, row1=+12.
        axiWrite(dut, OFF_INSTR,
          encode(COMPUTE_MM, BigInt(0x300), BigInt(n), BigInt(m), flags = 0)) mustBe OKAY
        pushAxisBeats(dut, w1.map(packWeights), maxCyclesPerBeat = 100)
        waitForIrq(dut); ackIrq(dut)

        // COMPUTE #2: runtime batch=1 (flags=1) with w2. Only row 0 is drained.
        axiWrite(dut, OFF_INSTR,
          encode(COMPUTE_MM, BigInt(0x300), BigInt(n), BigInt(m), flags = 1)) mustBe OKAY
        pushAxisBeats(dut, w2.map(packWeights), maxCyclesPerBeat = 100)
        waitForIrq(dut); ackIrq(dut)

        // STORE both rows and inspect.
        val totalOutputs = batchSize * m
        axiWrite(dut, OFF_INSTR,
          encode(STORE_OUT, BigInt(0x400), BigInt(totalOutputs), 0)) mustBe OKAY
        val raw = pullAxisBeats(dut, totalOutputs / effOutBeatLanes)
        waitForIrq(dut); ackIrq(dut)
        val actual = unpackOutputs(raw)

        val refW1 = referenceCompute(acts, w1, n, m)   // batch0=+8, batch1=+12
        val refW2 = referenceCompute(acts, w2, n, m)   // batch0=-8 mod
        // Row 0 = fresh w2 result; row 1 = stale w1 result from compute #1.
        val expected = refW2.slice(0, m) ++ refW1.slice(m, 2 * m)
        actual mustBe expected
        (0 until m).foreach { c => actual(c)     mustBe ((BigInt(-8) % outAccModulus) + outAccModulus) % outAccModulus }
        (0 until m).foreach { c => actual(m + c) mustBe BigInt(12) }
      }
    }

    "edge: LOAD at full BRAM depth (n = maxN) works end-to-end" in {
      simulate(mkCore) { dut =>
        resetDut(dut)
        val n = maxN
        val m = outLanesPerTile
        val acts = Seq.tabulate(n)(i => Seq((i + 1) & ((1 << aWidth) - 1),
                                            (i + 3) & ((1 << aWidth) - 1)))
        val weights = Seq.fill(n)(Seq.fill(outLanesPerTile)(1))
        val actual   = runPipeline(dut, acts, weights, n, m)
        val expected = referenceCompute(acts, weights, n, m)
        actual mustBe expected
      }
    }
  }

  // ──────────────────────────────────────────────────────────────────
  // Column banking — systolic pipeline inside the array. End-to-end result
  // must be identical to the broadcast Core (the banking only retimes the
  // array; Core's capture/drain waits on completion, so the extra per-bank
  // latency is transparent). colBankSize=1 with xDim=2 → 2 banks.
  // ──────────────────────────────────────────────────────────────────

  "Core with column banking (colBankSize=1)" - {
    def mkBankedCore = new Core(
      aWidth = aWidth, maxAcc = maxAcc, xDim = xDim, batchSize = batchSize,
      maxN = maxN, maxM = maxM, outBeatLanes = 0,
      axiAddrWidth = ADDR_WIDTH, axiDataWidth = DATA_WIDTH, axiIdWidth = ID_WIDTH,
      colBankSize = 1
    )

    "numeric: random workloads match reference (5 seeds)" in {
      Seq(1L, 7L, 42L, 99L, 256L).foreach { seed =>
        simulate(mkBankedCore) { dut =>
          resetDut(dut)
          val rng = new Random(seed)
          val n   = 4 + rng.nextInt(maxN - 3)
          val numColTiles = 1 + rng.nextInt(maxM / outLanesPerTile)
          val m = numColTiles * outLanesPerTile
          val acts = Seq.fill(n)(Seq.fill(batchSize)(rng.nextInt(1 << aWidth)))
          val weights = Seq.fill(numColTiles * n) {
            Seq.fill(outLanesPerTile)(Seq(0, 1, 3)(rng.nextInt(3)))
          }
          val actual   = runPipeline(dut, acts, weights, n, m)
          val expected = referenceCompute(acts, weights, n, m)
          withClue(s"banked seed=$seed n=$n m=$m") { actual mustBe expected }
        }
      }
    }

    "edge: back-to-back COMPUTE tiles drain correctly with per-bank skew" in {
      simulate(mkBankedCore) { dut =>
        resetDut(dut)
        val n = maxN
        val m = 2 * outLanesPerTile   // 2 col tiles: exercises capture→reset→next-tile
        val acts = Seq.tabulate(n)(i => Seq.fill(batchSize)((i + 1) & ((1 << aWidth) - 1)))
        val weights = Seq.fill((m / outLanesPerTile) * n)(Seq.fill(outLanesPerTile)(1))
        runPipeline(dut, acts, weights, n, m) mustBe referenceCompute(acts, weights, n, m)
      }
    }
  }

  // ──────────────────────────────────────────────────────────────────
  // True systolic dataflow — systolic.TrueSystolicArray in place of the broadcast
  // MAC array. Same claim as banking above and a stronger version of it: the array
  // is only retimed, so end-to-end results must be IDENTICAL, but the drain is now
  // (xDim-1)+(yDim-1) cycles instead of (numBanks-1), and it is Core's
  // capture -> arrLocalReset -> next-tile sequence that has to absorb it. The array's
  // own spec cannot see that interaction; this is where a drain that runs long, or an
  // array that keeps accepting beats into the wavefront, corrupts a tile boundary.
  //
  // Same five seeds as the baseline and the banked block, so any divergence is
  // attributable to the dataflow swap and nothing else.
  // ──────────────────────────────────────────────────────────────────

  "Core with the true systolic array (trueSystolic = true)" - {
    def mkSystolicCore = new Core(
      aWidth = aWidth, maxAcc = maxAcc, xDim = xDim, batchSize = batchSize,
      maxN = maxN, maxM = maxM, outBeatLanes = 0,
      axiAddrWidth = ADDR_WIDTH, axiDataWidth = DATA_WIDTH, axiIdWidth = ID_WIDTH,
      trueSystolic = true
    )

    "numeric: random workloads match reference (5 seeds)" in {
      Seq(1L, 7L, 42L, 99L, 256L).foreach { seed =>
        simulate(mkSystolicCore) { dut =>
          resetDut(dut)
          val rng = new Random(seed)
          val n   = 4 + rng.nextInt(maxN - 3)
          val numColTiles = 1 + rng.nextInt(maxM / outLanesPerTile)
          val m = numColTiles * outLanesPerTile
          val acts = Seq.fill(n)(Seq.fill(batchSize)(rng.nextInt(1 << aWidth)))
          val weights = Seq.fill(numColTiles * n) {
            Seq.fill(outLanesPerTile)(Seq(0, 1, 3)(rng.nextInt(3)))
          }
          val actual   = runPipeline(dut, acts, weights, n, m)
          val expected = referenceCompute(acts, weights, n, m)
          withClue(s"systolic seed=$seed n=$n m=$m") { actual mustBe expected }
        }
      }
    }

    // The tile boundary is the whole risk: PE(0,0) stops the input drainCycles before
    // PE(yDim-1,xDim-1) finishes, and a streaming caller that is not held off feeds the
    // NEXT tile's first beats into the drain and loses them. Multi-tile with a
    // per-cycle-varying activation makes a lost or duplicated beat a wrong number
    // rather than a coincidence.
    "edge: back-to-back COMPUTE tiles survive the wavefront drain" in {
      simulate(mkSystolicCore) { dut =>
        resetDut(dut)
        val n = maxN
        val m = 4 * outLanesPerTile   // 4 col tiles: capture -> reset -> next tile, thrice
        val acts = Seq.tabulate(n)(i => Seq.fill(batchSize)((i + 1) & ((1 << aWidth) - 1)))
        val weights = Seq.tabulate((m / outLanesPerTile) * n) { i =>
          Seq.tabulate(outLanesPerTile)(j => Seq(0, 1, 3)((i + j) % 3))
        }
        runPipeline(dut, acts, weights, n, m) mustBe referenceCompute(acts, weights, n, m)
      }
    }

    // n = 2, not n = 1. MEASURED 2026-08-08: a COMPUTE with cmpRows=1 never raises IRQ
    // on EITHER array — PE.scala increments accumCounter on the idle->running edge, so
    // the `accumCounter.value === nAccReg` test is first evaluated at counter=1 against
    // nAccReg = nAcc-1 = 0 and only matches after the counter wraps at maxAcc. That is a
    // pre-existing PE counter limitation (the same one behind `require(maxAcc >= 2)`),
    // identical on the shipped broadcast array, and unreachable in practice because real
    // projections have N >= 1024. n=2 is the smallest COMPUTE the PE actually supports,
    // and the wavefront still has to cross the whole array for it.
    "edge: a two-beat COMPUTE still crosses the array" in {
      simulate(mkSystolicCore) { dut =>
        resetDut(dut)
        val n = 2
        val m = 2 * outLanesPerTile
        val acts    = Seq(Seq.fill(batchSize)(3), Seq.fill(batchSize)(5))
        val weights = Seq.fill((m / outLanesPerTile) * n)(Seq.tabulate(outLanesPerTile)(j => Seq(0, 1, 3)(j % 3)))
        runPipeline(dut, acts, weights, n, m) mustBe referenceCompute(acts, weights, n, m)
      }
    }
  }

  // ──────────────────────────────────────────────────────────────────
  // outPipeStyle — the OutMemUltra read-pipeline body. batchSize=2 here, so
  // outRamStyle="ultra" and EVERY test in this suite already runs through
  // OutMemUltra; this block re-runs the numeric cases against the "absorb" body.
  //
  // Why it needs its own tests: "absorb" restructures the read path into the UG901
  // UltraRAM template (write and read in one always block, an explicit mem_data
  // output stage, and latency-1 pipeline stages after it instead of latency stages).
  // Total read latency is unchanged BY CONSTRUCTION — mem_data is stage 0 — but that
  // is exactly the kind of claim that is one off-by-one away from being false, and a
  // one-cycle slip here does not crash: it silently desynchronises the STORE stream
  // and ships shifted output. Same seeds as the baseline, so a divergence is
  // attributable to the memory body and nothing else.
  // ──────────────────────────────────────────────────────────────────

  "Core with the UG901-template output memory (outPipeStyle = absorb)" - {
    def mkAbsorbCore(systolic: Boolean = false) = new Core(
      aWidth = aWidth, maxAcc = maxAcc, xDim = xDim, batchSize = batchSize,
      maxN = maxN, maxM = maxM, outBeatLanes = 0,
      axiAddrWidth = ADDR_WIDTH, axiDataWidth = DATA_WIDTH, axiIdWidth = ID_WIDTH,
      outPipeStyle = "absorb", trueSystolic = systolic
    )

    "numeric: random workloads match reference (5 seeds)" in {
      Seq(1L, 7L, 42L, 99L, 256L).foreach { seed =>
        simulate(mkAbsorbCore()) { dut =>
          resetDut(dut)
          val rng = new Random(seed)
          val n   = 4 + rng.nextInt(maxN - 3)
          val numColTiles = 1 + rng.nextInt(maxM / outLanesPerTile)
          val m = numColTiles * outLanesPerTile
          val acts = Seq.fill(n)(Seq.fill(batchSize)(rng.nextInt(1 << aWidth)))
          val weights = Seq.fill(numColTiles * n) {
            Seq.fill(outLanesPerTile)(Seq(0, 1, 3)(rng.nextInt(3)))
          }
          val actual   = runPipeline(dut, acts, weights, n, m)
          val expected = referenceCompute(acts, weights, n, m)
          withClue(s"absorb seed=$seed n=$n m=$m") { actual mustBe expected }
        }
      }
    }

    // Multi-col-tile with a per-row-distinct value: a read pipeline that is one stage
    // short or one stage long emits the neighbouring row, which only shows up when
    // consecutive rows differ. A constant fill would pass at any latency.
    "edge: every output row is distinct, so a latency slip cannot hide" in {
      simulate(mkAbsorbCore()) { dut =>
        resetDut(dut)
        val n = maxN
        val m = 4 * outLanesPerTile
        val acts = Seq.tabulate(n)(i => Seq.fill(batchSize)((i + 1) & ((1 << aWidth) - 1)))
        val weights = Seq.tabulate((m / outLanesPerTile) * n) { i =>
          Seq.tabulate(outLanesPerTile)(j => Seq(0, 1, 3)((i + j) % 3))
        }
        runPipeline(dut, acts, weights, n, m) mustBe referenceCompute(acts, weights, n, m)
      }
    }

    // ── banked ──────────────────────────────────────────────────────────────
    // outBankDepth is forced small here on purpose. The tiny config's output memory is
    // only batchSize*numColTilesMax rows deep, so at the production default
    // (4096*cascadeHeight) it lands in ONE bank and the registered mux — the entire
    // point of the style — is never built, let alone exercised. outBankDepth=2 splits
    // it across several banks so the bank-select decode, the write-side bank decode and
    // the mux all run.
    def mkBankedCore(bankDepth: Int, systolic: Boolean = false) = new Core(
      aWidth = aWidth, maxAcc = maxAcc, xDim = xDim, batchSize = batchSize,
      maxN = maxN, maxM = maxM, outBeatLanes = 0,
      axiAddrWidth = ADDR_WIDTH, axiDataWidth = DATA_WIDTH, axiIdWidth = ID_WIDTH,
      outPipeStyle = "banked", outBankDepth = bankDepth, trueSystolic = systolic
    )

    "banked: random workloads match reference across bank counts" in {
      // bankDepth 1 and 2 give different bank counts and different select widths; the
      // production default (one bank) is the degenerate path and must also still work.
      Seq(1, 2, 0).foreach { bd =>
        Seq(1L, 42L, 256L).foreach { seed =>
          simulate(mkBankedCore(bd)) { dut =>
            resetDut(dut)
            val rng = new Random(seed)
            val n   = 4 + rng.nextInt(maxN - 3)
            val numColTiles = 1 + rng.nextInt(maxM / outLanesPerTile)
            val m = numColTiles * outLanesPerTile
            val acts = Seq.fill(n)(Seq.fill(batchSize)(rng.nextInt(1 << aWidth)))
            val weights = Seq.fill(numColTiles * n) {
              Seq.fill(outLanesPerTile)(Seq(0, 1, 3)(rng.nextInt(3)))
            }
            val actual   = runPipeline(dut, acts, weights, n, m)
            val expected = referenceCompute(acts, weights, n, m)
            withClue(s"banked bankDepth=$bd seed=$seed n=$n m=$m") { actual mustBe expected }
          }
        }
      }
    }

    // The bank-crossing case: enough col tiles that consecutive output rows land in
    // DIFFERENT banks, with every row distinct. A wrong bank-select alignment (sel_q
    // one cycle out of step with the bank output registers it selects among) returns a
    // neighbouring bank's row, which is only visible when the banks hold different data.
    "banked: consecutive rows in different banks stay correctly selected" in {
      simulate(mkBankedCore(bankDepth = 1)) { dut =>   // one row per bank: every row crosses
        resetDut(dut)
        val n = maxN
        val m = maxM                                   // all col tiles => deepest row range
        val acts = Seq.tabulate(n)(i => Seq.fill(batchSize)((i + 1) & ((1 << aWidth) - 1)))
        val weights = Seq.tabulate((m / outLanesPerTile) * n) { i =>
          Seq.tabulate(outLanesPerTile)(j => Seq(0, 1, 3)((i * 7 + j * 3) % 3))
        }
        runPipeline(dut, acts, weights, n, m) mustBe referenceCompute(acts, weights, n, m)
      }
    }

    "banked: composes with the true systolic array" in {
      simulate(mkBankedCore(bankDepth = 2, systolic = true)) { dut =>
        resetDut(dut)
        val rng = new Random(7L)
        val n   = 4 + rng.nextInt(maxN - 3)
        val numColTiles = 1 + rng.nextInt(maxM / outLanesPerTile)
        val m = numColTiles * outLanesPerTile
        val acts = Seq.fill(n)(Seq.fill(batchSize)(rng.nextInt(1 << aWidth)))
        val weights = Seq.fill(numColTiles * n) {
          Seq.fill(outLanesPerTile)(Seq(0, 1, 3)(rng.nextInt(3)))
        }
        runPipeline(dut, acts, weights, n, m) mustBe referenceCompute(acts, weights, n, m)
      }
    }

    // The combination that would actually ship: both fixes at once.
    "numeric: composes with the true systolic array" in {
      Seq(1L, 42L, 256L).foreach { seed =>
        simulate(mkAbsorbCore(systolic = true)) { dut =>
          resetDut(dut)
          val rng = new Random(seed)
          val n   = 4 + rng.nextInt(maxN - 3)
          val numColTiles = 1 + rng.nextInt(maxM / outLanesPerTile)
          val m = numColTiles * outLanesPerTile
          val acts = Seq.fill(n)(Seq.fill(batchSize)(rng.nextInt(1 << aWidth)))
          val weights = Seq.fill(numColTiles * n) {
            Seq.fill(outLanesPerTile)(Seq(0, 1, 3)(rng.nextInt(3)))
          }
          val actual   = runPipeline(dut, acts, weights, n, m)
          val expected = referenceCompute(acts, weights, n, m)
          withClue(s"absorb+systolic seed=$seed n=$n m=$m") { actual mustBe expected }
        }
      }
    }
  }

  // ──────────────────────────────────────────────────────────────────
  // captureMaxFanout — the pipelined tile capture. MEASURED 2026-08-08, the enable of
  // the tileAccums bank is 74.2% of everything that fails 300 MHz, so the lever moves the
  // parallel load out of the FSM into its own cCapture state where a plain register drives
  // it and max_fanout can replicate that register.
  //
  // The attribute itself is invisible to Verilator; what these tests pin is the RETIMING,
  // which is not invisible at all. Capture is now decided in cStream and performed one
  // cycle later, so the array must still be holding both `valid` and its accumulators when
  // the load happens — the whole correctness argument rests on PEs holding in done_state
  // until `ready`. If that is wrong the failure is silent: tileAccums latches whatever the
  // array shows next and the tile ships plausible garbage. Every test therefore uses
  // per-row-distinct data, where a stale or early load is a wrong number rather than a
  // coincidence.
  //
  // Same seeds as the baseline and the outPipeStyle block, so a divergence is attributable
  // to the capture pipeline and nothing else.
  // ──────────────────────────────────────────────────────────────────

  "Core with the pipelined tile capture (captureMaxFanout > 0)" - {
    def mkCaptureCore(systolic: Boolean = false, pipeStyle: String = "legacy",
                      bankDepth: Int = 0) = new Core(
      aWidth = aWidth, maxAcc = maxAcc, xDim = xDim, batchSize = batchSize,
      maxN = maxN, maxM = maxM, outBeatLanes = 0,
      axiAddrWidth = ADDR_WIDTH, axiDataWidth = DATA_WIDTH, axiIdWidth = ID_WIDTH,
      outPipeStyle = pipeStyle, outBankDepth = bankDepth, trueSystolic = systolic,
      captureMaxFanout = 32
    )

    "numeric: random workloads match reference (5 seeds)" in {
      Seq(1L, 7L, 42L, 99L, 256L).foreach { seed =>
        simulate(mkCaptureCore()) { dut =>
          resetDut(dut)
          val rng = new Random(seed)
          val n   = 4 + rng.nextInt(maxN - 3)
          val numColTiles = 1 + rng.nextInt(maxM / outLanesPerTile)
          val m = numColTiles * outLanesPerTile
          val acts = Seq.fill(n)(Seq.fill(batchSize)(rng.nextInt(1 << aWidth)))
          val weights = Seq.fill(numColTiles * n) {
            Seq.fill(outLanesPerTile)(Seq(0, 1, 3)(rng.nextInt(3)))
          }
          val actual   = runPipeline(dut, acts, weights, n, m)
          val expected = referenceCompute(acts, weights, n, m)
          withClue(s"capture seed=$seed n=$n m=$m") { actual mustBe expected }
        }
      }
    }

    // The tile boundary, which is where the extra state lives: cStream -> cCapture ->
    // cReset -> cStream, once per col tile. Every col tile holds a DIFFERENT accumulator
    // pattern, so a capture that fires a cycle early (array not yet valid) or a cycle late
    // (array already reset by cReset) ships a neighbouring tile's numbers.
    "edge: every col tile captures its own accumulators" in {
      simulate(mkCaptureCore()) { dut =>
        resetDut(dut)
        val n = maxN
        val m = maxM                                   // all col tiles => the longest chain
        val acts = Seq.tabulate(n)(i => Seq.fill(batchSize)((i + 1) & ((1 << aWidth) - 1)))
        val weights = Seq.tabulate((m / outLanesPerTile) * n) { i =>
          Seq.tabulate(outLanesPerTile)(j => Seq(0, 1, 3)((i * 7 + j * 3) % 3))
        }
        runPipeline(dut, acts, weights, n, m) mustBe referenceCompute(acts, weights, n, m)
      }
    }

    // The one case where capture is NOT free to fire when the array is ready: the previous
    // tile's drain still owns tileAccums. cmpRows = 2 against a drain of
    // batchSize*outSubBeats = 2 rows makes the tiles arrive as fast as the drain can
    // retire them, so the `!drainBusy` gate is live on every boundary — and it is the gate
    // whose decision is now one cycle older than the load it guards.
    "edge: a drain still in flight holds the capture off" in {
      simulate(mkCaptureCore()) { dut =>
        resetDut(dut)
        val n = 2
        val m = maxM
        val acts = Seq.tabulate(n)(i => Seq.fill(batchSize)((i * 5 + 1) & ((1 << aWidth) - 1)))
        val weights = Seq.tabulate((m / outLanesPerTile) * n) { i =>
          Seq.tabulate(outLanesPerTile)(j => Seq(0, 1, 3)((i + j) % 3))
        }
        runPipeline(dut, acts, weights, n, m) mustBe referenceCompute(acts, weights, n, m)
      }
    }

    // Against the memory that actually ships. The banked read path has its own registered
    // mux, so this is the combination where a one-cycle capture slip and a one-cycle read
    // slip could cancel out in a single-tile test and only show up across tiles — hence
    // several banks (bankDepth=1) and multiple col tiles.
    "composes with the banked output memory (the shipped style)" in {
      Seq(1L, 42L, 256L).foreach { seed =>
        simulate(mkCaptureCore(pipeStyle = "banked", bankDepth = 1)) { dut =>
          resetDut(dut)
          val rng = new Random(seed)
          val n   = 4 + rng.nextInt(maxN - 3)
          val numColTiles = 1 + rng.nextInt(maxM / outLanesPerTile)
          val m = numColTiles * outLanesPerTile
          val acts = Seq.fill(n)(Seq.fill(batchSize)(rng.nextInt(1 << aWidth)))
          val weights = Seq.fill(numColTiles * n) {
            Seq.fill(outLanesPerTile)(Seq(0, 1, 3)(rng.nextInt(3)))
          }
          val actual   = runPipeline(dut, acts, weights, n, m)
          val expected = referenceCompute(acts, weights, n, m)
          withClue(s"capture+banked seed=$seed n=$n m=$m") { actual mustBe expected }
        }
      }
    }

    // The systolic array holds its accumulators in done_state through its OWN drain, which
    // is the property the deferred load depends on; this is the direct test of it.
    "composes with the true systolic array" in {
      simulate(mkCaptureCore(systolic = true, pipeStyle = "banked", bankDepth = 1)) { dut =>
        resetDut(dut)
        val n = maxN
        val m = 4 * outLanesPerTile
        val acts = Seq.tabulate(n)(i => Seq.fill(batchSize)((i + 1) & ((1 << aWidth) - 1)))
        val weights = Seq.tabulate((m / outLanesPerTile) * n) { i =>
          Seq.tabulate(outLanesPerTile)(j => Seq(0, 1, 3)((i + j) % 3))
        }
        runPipeline(dut, acts, weights, n, m) mustBe referenceCompute(acts, weights, n, m)
      }
    }
  }

  // ──────────────────────────────────────────────────────────────────
  // actRamStyle — the control.ActMem BlackBox replacing the inferred SyncReadMem
  // for the activation memory. It exists to carry ram_style/cascade_height so the
  // RAMB36E2 depth cascade can be bounded at maxN=8192 (the worst 250 MHz path).
  // The attributes are invisible to Verilator, so what these tests pin down is the
  // thing that WOULD break: that the hand-written SV reproduces SyncReadMem's
  // read/write timing exactly. A one-cycle slip here desynchronises the
  // weight/activation pairing in inQ and corrupts results silently.
  // ──────────────────────────────────────────────────────────────────

  "Core with an ActMem activation memory" - {
    def mkActMemCore(style: String, cascade: Int) = new Core(
      aWidth = aWidth, maxAcc = maxAcc, xDim = xDim, batchSize = batchSize,
      maxN = maxN, maxM = maxM, outBeatLanes = 0,
      axiAddrWidth = ADDR_WIDTH, axiDataWidth = DATA_WIDTH, axiIdWidth = ID_WIDTH,
      actRamStyle = style, actCascadeHeight = cascade
    )

    // The headline claim: identical results on the same stimulus as the SyncReadMem
    // path. Same seeds as the baseline "random workloads match reference" case above,
    // so a divergence is attributable to the memory swap and nothing else.
    "numeric: random workloads match reference (5 seeds)" in {
      Seq(1L, 7L, 42L, 99L, 256L).foreach { seed =>
        simulate(mkActMemCore("block", 2)) { dut =>
          resetDut(dut)
          val rng = new Random(seed)
          val n   = 4 + rng.nextInt(maxN - 3)
          val numColTiles = 1 + rng.nextInt(maxM / outLanesPerTile)
          val m = numColTiles * outLanesPerTile
          val acts = Seq.fill(n)(Seq.fill(batchSize)(rng.nextInt(1 << aWidth)))
          val weights = Seq.fill(numColTiles * n) {
            Seq.fill(outLanesPerTile)(Seq(0, 1, 3)(rng.nextInt(3)))
          }
          val actual   = runPipeline(dut, acts, weights, n, m)
          val expected = referenceCompute(acts, weights, n, m)
          withClue(s"actmem seed=$seed n=$n m=$m") { actual mustBe expected }
        }
      }
    }

    // Multi-col-tile: the read address wraps back to row 0 once per tile while the
    // write port is idle. This is where a read/write-collision difference between the
    // BlackBox and SyncReadMem would show up.
    "numeric: multi-col-tile reads wrap per tile" in {
      simulate(mkActMemCore("block", 2)) { dut =>
        resetDut(dut)
        val n = maxN
        val m = 2 * outLanesPerTile
        val acts = Seq.tabulate(n)(i => Seq.fill(batchSize)((i + 1) & ((1 << aWidth) - 1)))
        val weights = Seq.fill((m / outLanesPerTile) * n)(Seq.fill(outLanesPerTile)(1))
        runPipeline(dut, acts, weights, n, m) mustBe referenceCompute(acts, weights, n, m)
      }
    }

    // Write path: a second LOAD must fully overwrite the first. Mirrors the
    // "edge: re-LOAD overwrites activation BRAM cleanly" case on the legacy path.
    "edge: re-LOAD overwrites the activation memory cleanly" in {
      simulate(mkActMemCore("block", 2)) { dut =>
        resetDut(dut)
        val n = maxN
        val m = outLanesPerTile
        val weights = Seq.fill(n)(Seq.fill(outLanesPerTile)(1))
        val first   = Seq.fill(n)(Seq.fill(batchSize)(1))
        val second  = Seq.tabulate(n)(i => Seq.fill(batchSize)((i + 2) & ((1 << aWidth) - 1)))
        runPipeline(dut, first, weights, n, m) mustBe referenceCompute(first, weights, n, m)
        runPipeline(dut, second, weights, n, m) mustBe referenceCompute(second, weights, n, m)
      }
    }

    // Both ram_style values and the cascade_height=0 (attribute omitted) case have to
    // produce SV that elaborates and compiles — an unparseable attribute list or a
    // module-name collision between two instantiations would only surface here.
    "every (ram_style, cascade_height) variant elaborates and computes" in {
      Seq(("block", 0), ("block", 1), ("ultra", 2)).foreach { case (style, cascade) =>
        simulate(mkActMemCore(style, cascade)) { dut =>
          resetDut(dut)
          val n = 4
          val m = outLanesPerTile
          val acts = Seq.tabulate(n)(i => Seq.fill(batchSize)(i + 1))
          val weights = Seq.fill(n)(Seq.fill(outLanesPerTile)(1))
          withClue(s"style=$style cascade=$cascade") {
            runPipeline(dut, acts, weights, n, m) mustBe referenceCompute(acts, weights, n, m)
          }
        }
      }
    }
  }

  // ──────────────────────────────────────────────────────────────────
  // Output chunking — separate config with outBeatLanes < outLanesPerTile.
  // ──────────────────────────────────────────────────────────────────

  "Core with output chunking (outBeatLanes = outLanesPerTile/2)" - {
    val chunkLanes = outLanesPerTile / 2
    val chunkSubBeats = outLanesPerTile / chunkLanes
    val chunkBeatWidth = chunkLanes * outLaneWidth

    def mkChunked = new Core(
      aWidth       = aWidth,
      maxAcc       = maxAcc,
      xDim         = xDim,
      batchSize    = batchSize,
      maxN         = maxN,
      maxM         = maxM,
      outBeatLanes = chunkLanes,
      axiAddrWidth = ADDR_WIDTH,
      axiDataWidth = DATA_WIDTH,
      axiIdWidth   = ID_WIDTH
    )

    def unpackChunked(beats: Seq[BigInt]): Seq[BigInt] = {
      val mask = outAccModulus - 1
      beats.flatMap { beat =>
        (0 until chunkLanes).map { i =>
          (beat >> (i * outLaneWidth)) & mask
        }
      }
    }

    "emits twice as many beats per col tile and still matches reference" in {
      simulate(mkChunked) { dut =>
        // The helpers above bind to outLanesPerTile-wide unpacking; use the chunked one here.
        // Reuse most of the pipeline by hand:
        dut.s_axi.awvalid.poke(false.B); dut.s_axi.wvalid.poke(false.B)
        dut.s_axi.bready.poke(false.B);  dut.s_axi.arvalid.poke(false.B)
        dut.s_axi.rready.poke(false.B);  dut.s_axi.awid.poke(0.U)
        dut.s_axi.arid.poke(0.U);        dut.s_axis.tvalid.poke(false.B)
        dut.s_axis.tdata.poke(0.U);      dut.s_axis.tlast.poke(false.B)
        dut.m_axis.tready.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); dut.clock.step()

        val n = 4
        val m = 2 * outLanesPerTile   // 2 col tiles → 2 × chunkSubBeats per batch
        val acts = Seq.fill(n)(Seq(2, 5))
        val w1 = Seq.fill(n)(Seq(1, 1, 0, 3))
        val w2 = Seq.fill(n)(Seq(3, 0, 1, 1))
        val weights = w1 ++ w2

        axiWrite(dut, OFF_INSTR, encode(LOAD_ACT, BigInt(0x100), BigInt(n), 0)) mustBe OKAY
        pushAxisBeats(dut, acts.map(packActivations))
        waitForIrq(dut); ackIrq(dut)

        axiWrite(dut, OFF_INSTR,
          encode(COMPUTE_MM, BigInt(0x300), BigInt(n), BigInt(m))) mustBe OKAY
        pushAxisBeats(dut, weights.map(packWeights), maxCyclesPerBeat = 100)
        waitForIrq(dut); ackIrq(dut)

        val totalOutputs = batchSize * m
        axiWrite(dut, OFF_INSTR,
          encode(STORE_OUT, BigInt(0x400), BigInt(totalOutputs), 0)) mustBe OKAY
        val numBeats = totalOutputs / chunkLanes
        numBeats mustBe (batchSize * m / chunkLanes)
        val raw = pullAxisBeats(dut, numBeats, maxCycles = 1000)
        waitForIrq(dut); ackIrq(dut)

        val actual   = unpackChunked(raw)
        val expected = referenceCompute(acts, weights, n, m)
        actual mustBe expected
      }
    }
  }
}
