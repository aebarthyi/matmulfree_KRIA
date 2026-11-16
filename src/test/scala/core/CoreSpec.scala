package core

import scala.util.Random
import chisel3._
import chisel3.util.log2Ceil
import packing.TernaryPack
import testutil.{AxiMaster, AxisSink, InstructionEncoder, SimWait, SingleThreadVerilator}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CoreSpec extends AnyFreeSpec with Matchers with SingleThreadVerilator {
  import InstructionEncoder._

  // Tiny config (fast in Verilator).
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

  private val ADDR_WIDTH = 32
  private val DATA_WIDTH = 128
  private val ID_WIDTH   = 6

  private val OFF_INSTR   = BigInt(0x000)
  private val OFF_IRQ_ACK = BigInt(0x020)

  private val OKAY = 0

  // ───────────────────────── Setup ─────────────────────────

  // The tiny geometry above as a CoreConfig; the variants below copy it.
  private val tinyCfg = CoreConfig(
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

  private def mkCore       = new Core(tinyCfg)
  private def mkCorePacked = new Core(tinyCfg.copy(packedWeights = true))

  /** Dense 1.6-bit weight stream as the offline packer emits it: the col-tile-major
    * trits packed 5/byte, chunked into axisDataWidth-bit wire beats (last zero-padded). */
  private def packWeightStreamDense(weightStream: Seq[Seq[Int]]): Seq[BigInt] = {
    require(axisDataWidth % 8 == 0)
    val bytes        = TernaryPack.packCodes(weightStream.flatten)
    val bytesPerBeat = axisDataWidth / 8
    bytes.grouped(bytesPerBeat).map { g =>
      g.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (b, j)) => acc | (BigInt(b) << (8 * j)) }
    }.toSeq
  }

  private def resetDut(dut: Core): Unit = {
    new AxiMaster(dut.s_axi, dut.clock).idle()
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

  private def axiWrite(dut: Core, addr: BigInt, data: BigInt, maxCycles: Int = 50): Int =
    new AxiMaster(dut.s_axi, dut.clock).write(addr, data, maxCycles = maxCycles)

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

  private def pullAxisBeats(dut: Core, count: Int, maxCycles: Int = 500): Seq[BigInt] =
    AxisSink.pull(dut.m_axis, dut.clock, count, maxCycles)

  private def waitForIrq(dut: Core, maxCycles: Int = 1000): Unit =
    SimWait.untilHigh(dut.irq, dut.clock, maxCycles, "IRQ")

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
    * Software reference. `acts` is [n][batchSize]; `weightStream` is numColTiles*n
    * col-tile-major beats of outLanesPerTile codes. Returns the m_axis order (batch-major,
    * col-ascending): out[b * cols + col] = sum_n A[n][b] * sign(W[n][col]).
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

    // COMPUTE: rows=n, cols=m; weights 2-bit-per-beat or the dense 1.6-bit stream.
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

  // ───────────────────────── Tests ─────────────────────────

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

    // ── Numeric correctness ──

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

    // ── 1.6-bit packed weights: must match the 2-bit path exactly ──

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

    // Host column-tile splitting relies on this: COMPUTEs over contiguous col-tile slices
    // sharing ONE LOAD_ACT concatenate to the whole projection (the weight stream is
    // col-tile-major and sCompute never disturbs actBram). Chunks are deliberately uneven.
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

    // ── Edge cases ──

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

    // ── Runtime batch count (flags): a full-batch compute seeds both outBram rows, then
    // a batch=1 compute must overwrite ONLY row 0, leaving row 1 stale. ──

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

  // Column banking only retimes the array, so results must match the broadcast Core.
  // colBankSize=1 with xDim=2 → 2 banks.

  "Core with column banking (colBankSize=1)" - {
    def mkBankedCore = new Core(tinyCfg.copy(colBankSize = 1))

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

  // Banked output memory. At the production bank depth the tiny config is ONE bank, so
  // these force small bank depths to build the bank-select and registered mux. A read-
  // latency off-by-one would silently shift the STORE stream rather than crash.

  "Core with the banked output memory" - {
    def mkBankedCore(bankDepth: Int) = new Core(tinyCfg.copy(outBankDepth = bankDepth))

    "banked: random workloads match reference across bank counts" in {
      // 1 and 2: different bank counts/select widths; 0: the production single bank.
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

    // Consecutive rows in different banks with distinct data, so a sel_q misalignment
    // (returning a neighbouring bank's row) is visible.
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

  }

  // ActMem activation memory: the hand-written SV must reproduce SyncReadMem's timing
  // exactly; a one-cycle slip silently breaks the weight/activation pairing in inQ.

  "Core with an ActMem activation memory" - {
    def mkActMemCore(style: String, cascade: Int) =
      new Core(tinyCfg.copy(actRamStyle = style, actCascadeHeight = cascade))

    // Same seeds as the SyncReadMem baseline above.
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

    // The read address wraps to row 0 once per tile.
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

    // Catches unparseable attribute lists and module-name collisions.
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

  // Output chunking: outBeatLanes < outLanesPerTile.

  "Core with output chunking (outBeatLanes = outLanesPerTile/2)" - {
    val chunkLanes = outLanesPerTile / 2

    def mkChunked = new Core(tinyCfg.copy(outBeatLanes = chunkLanes))

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
        // runPipeline unpacks outLanesPerTile-wide beats, so drive the flow by hand.
        resetDut(dut)

        val n = 4
        val m = 2 * outLanesPerTile   // 2 col tiles
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
