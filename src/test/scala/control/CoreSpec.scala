package control

import scala.util.Random
import chisel3._
import chisel3.util.log2Ceil
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
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
      m:            Int
  ): Seq[BigInt] = {
    val numColTiles = m / outLanesPerTile
    require(weightStream.length == numColTiles * n)

    // LOAD: n beats, each beat = batchSize activations.
    axiWrite(dut, OFF_INSTR, encode(LOAD_ACT, BigInt(0x100), BigInt(n), 0)) mustBe OKAY
    pushAxisBeats(dut, acts.map(packActivations))
    waitForIrq(dut); ackIrq(dut)

    // COMPUTE: rows=n, cols=m
    axiWrite(dut, OFF_INSTR, encode(COMPUTE_MM, BigInt(0x300), BigInt(n), BigInt(m))) mustBe OKAY
    pushAxisBeats(dut, weightStream.map(packWeights), maxCyclesPerBeat = 200)
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
