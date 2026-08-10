package systolic

import scala.util.Random
import chisel3._
import testutil.SingleThreadVerilator
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/**
  * [[TrueSystolicArray]] must be BIT-IDENTICAL to [[SystolicArray]], not merely
  * close: same math, different dataflow. The contract both are held to is
  * [[SystolicArrayModel.compute]], which is reused here UNCHANGED — if the model
  * had to be adjusted for the systolic array, the dataflow would be wrong.
  *
  * The tests that matter beyond plain functional coverage:
  *
  *   - `runBoth` drives the SAME randomized stimulus through the broadcast array
  *     and the systolic array and checks each against the model, which is a
  *     transitive proof that the two agree beat for beat.
  *   - the gap tests deassert valid mid-operation. Skew is exactly the mechanism
  *     that could mis-pair a weight beat with the wrong activation, and a stalled
  *     stream is what Core produces in practice (inQ backpressure), so a design
  *     that only works on a contiguous stream would pass every other test here and
  *     fail on hardware.
  *   - the drain test pins that the array stops accepting input the cycle PE(0,0)
  *     finishes. Without it a streaming caller feeds the next operation's beats
  *     into the drain and loses them.
  */
class TrueSystolicArraySpec extends AnyFreeSpec with Matchers with SingleThreadVerilator {

  private def randWeight(rng: Random): Int = Seq(0, 1, 3)(rng.nextInt(3))

  /** Random (weights, activations) for one operation. */
  private def randomOp(rng: Random, nAcc: Int, xDim: Int, yDim: Int, aWidth: Int)
      : (Seq[Seq[Int]], Seq[Seq[Int]]) = {
    val flatW = xDim * (aWidth / 2)
    (Seq.fill(nAcc)(Seq.fill(flatW)(randWeight(rng))),
     Seq.fill(nAcc)(Seq.fill(yDim)(rng.nextInt(1 << aWidth))))
  }

  // ---------------------------------------------------------------
  // Drivers. The two arrays share the IO bundle types but have no common
  // supertype (SystolicArray is deliberately not touched), so each gets its own
  // thin driver over the identical body.
  // ---------------------------------------------------------------

  private def resetSys(dut: TrueSystolicArray): Unit = {
    dut.input.weights_i.valid.poke(false.B)
    dut.input.activation_i.valid.poke(false.B)
    dut.input.nAcc.valid.poke(false.B)
    dut.output.ready.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  private def resetBc(dut: SystolicArray): Unit = {
    dut.input.weights_i.valid.poke(false.B)
    dut.input.activation_i.valid.poke(false.B)
    dut.input.nAcc.valid.poke(false.B)
    dut.output.ready.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  /**
    * Stream one operation and check the result against the model.
    *
    * @param gapAfter  cycle indices after which to insert one stalled (valid low)
    *                  cycle. The array must ignore the stall entirely.
    * @param maxSkew   cycles to poll output.valid for after the last beat.
    */
  private def runSys(
      dut: TrueSystolicArray,
      aWidth: Int, maxAcc: Int, xDim: Int, yDim: Int,
      weights: Seq[Seq[Int]], activations: Seq[Seq[Int]],
      maxSkew: Int, gapAfter: Set[Int] = Set.empty
  ): Unit = {
    val nAcc     = weights.length
    val expected = SystolicArrayModel.compute(aWidth, maxAcc, xDim, yDim, weights, activations)

    dut.input.nAcc.valid.poke(true.B)
    dut.input.nAcc.bits.poke(nAcc.U)
    dut.output.ready.poke(false.B)

    for (t <- 0 until nAcc) {
      dut.input.weights_i.valid.poke(true.B)
      dut.input.activation_i.valid.poke(true.B)
      weights(t).zipWithIndex.foreach { case (v, i) => dut.input.weights_i.bits(i).poke(v.U) }
      activations(t).zipWithIndex.foreach { case (v, i) => dut.input.activation_i.bits(i).poke(v.U) }
      dut.clock.step()
      if (t == 0) dut.input.nAcc.valid.poke(false.B)
      if (gapAfter.contains(t)) {
        // Stall: valid low, and the bits deliberately garbaged so anything the
        // array latches during the gap shows up as a wrong answer.
        dut.input.weights_i.valid.poke(false.B)
        dut.input.activation_i.valid.poke(false.B)
        weights(t).indices.foreach(i => dut.input.weights_i.bits(i).poke(1.U))
        activations(t).indices.foreach(i => dut.input.activation_i.bits(i).poke(((1 << aWidth) - 1).U))
        dut.clock.step()
      }
    }

    dut.input.weights_i.valid.poke(false.B)
    dut.input.activation_i.valid.poke(false.B)
    dut.input.nAcc.valid.poke(false.B)

    var waited = 0
    while (!dut.output.valid.peek().litToBoolean && waited < maxSkew) {
      dut.clock.step(); waited += 1
    }
    dut.output.valid.expect(true.B, s"output valid should be high within $maxSkew drain cycles")
    for (row <- 0 until yDim; idx <- expected(row).indices) {
      dut.output.bits.accum(row)(idx).expect(expected(row)(idx).U,
        s"accum[$row][$idx]: expected ${expected(row)(idx)}")
    }
    dut.output.ready.poke(true.B)
    dut.clock.step()
    dut.output.ready.poke(false.B)
  }

  /** The same drive, against the shipped broadcast array. */
  private def runBc(
      dut: SystolicArray,
      aWidth: Int, maxAcc: Int, xDim: Int, yDim: Int,
      weights: Seq[Seq[Int]], activations: Seq[Seq[Int]],
      maxSkew: Int, gapAfter: Set[Int] = Set.empty
  ): Unit = {
    val nAcc     = weights.length
    val expected = SystolicArrayModel.compute(aWidth, maxAcc, xDim, yDim, weights, activations)

    dut.input.nAcc.valid.poke(true.B)
    dut.input.nAcc.bits.poke(nAcc.U)
    dut.output.ready.poke(false.B)

    for (t <- 0 until nAcc) {
      dut.input.weights_i.valid.poke(true.B)
      dut.input.activation_i.valid.poke(true.B)
      weights(t).zipWithIndex.foreach { case (v, i) => dut.input.weights_i.bits(i).poke(v.U) }
      activations(t).zipWithIndex.foreach { case (v, i) => dut.input.activation_i.bits(i).poke(v.U) }
      dut.clock.step()
      if (t == 0) dut.input.nAcc.valid.poke(false.B)
      if (gapAfter.contains(t)) {
        dut.input.weights_i.valid.poke(false.B)
        dut.input.activation_i.valid.poke(false.B)
        weights(t).indices.foreach(i => dut.input.weights_i.bits(i).poke(1.U))
        activations(t).indices.foreach(i => dut.input.activation_i.bits(i).poke(((1 << aWidth) - 1).U))
        dut.clock.step()
      }
    }

    dut.input.weights_i.valid.poke(false.B)
    dut.input.activation_i.valid.poke(false.B)
    dut.input.nAcc.valid.poke(false.B)

    var waited = 0
    while (!dut.output.valid.peek().litToBoolean && waited < maxSkew) {
      dut.clock.step(); waited += 1
    }
    dut.output.valid.expect(true.B, s"output valid should be high within $maxSkew drain cycles")
    for (row <- 0 until yDim; idx <- expected(row).indices) {
      dut.output.bits.accum(row)(idx).expect(expected(row)(idx).U,
        s"accum[$row][$idx]: expected ${expected(row)(idx)}")
    }
    dut.output.ready.poke(true.B)
    dut.clock.step()
    dut.output.ready.poke(false.B)
  }

  // ---------------------------------------------------------------
  // Deterministic functional tests
  // ---------------------------------------------------------------

  "TrueSystolicArray(aWidth=8, maxAcc=64, xDim=4, yDim=3) functional tests" - {
    val (aWidth, maxAcc, xDim, yDim) = (8, 64, 4, 3)
    val flatW = xDim * (aWidth / 2)
    val skew  = xDim + yDim

    "all +1 weights, constant activation per row gives row*nAcc on every lane" in {
      simulate(new TrueSystolicArray(aWidth, maxAcc, xDim, yDim)) { dut =>
        resetSys(dut)
        val nAcc = 5
        runSys(dut, aWidth, maxAcc, xDim, yDim,
               Seq.fill(nAcc)(Seq.fill(flatW)(1)), Seq.fill(nAcc)(Seq(3, 9, 4)), skew)
      }
    }

    "all -1 weights wraps unsigned" in {
      simulate(new TrueSystolicArray(aWidth, maxAcc, xDim, yDim)) { dut =>
        resetSys(dut)
        val nAcc = 4
        runSys(dut, aWidth, maxAcc, xDim, yDim,
               Seq.fill(nAcc)(Seq.fill(flatW)(3)), Seq.fill(nAcc)(Seq(2, 5, 7)), skew)
      }
    }

    "all-hold weights produce zero on every lane and every row" in {
      simulate(new TrueSystolicArray(aWidth, maxAcc, xDim, yDim)) { dut =>
        resetSys(dut)
        val nAcc = 4
        runSys(dut, aWidth, maxAcc, xDim, yDim,
               Seq.fill(nAcc)(Seq.fill(flatW)(0)), Seq.fill(nAcc)(Seq(99, 77, 55)), skew)
      }
    }

    // The test the skew exists for: a per-cycle-varying activation paired with a
    // per-cycle-varying weight beat. A constant stream would pass with ANY skew.
    "distinct activation AND weight per cycle pins the pairing" in {
      simulate(new TrueSystolicArray(aWidth, maxAcc, xDim, yDim)) { dut =>
        resetSys(dut)
        // Beat t sets column t to +1 and everything else to hold, so output
        // column t must equal exactly the activation of cycle t — a mis-skew by
        // one cycle lands the wrong activation in the column and is visible.
        val nAcc = 4
        val weights = Seq.tabulate(nAcc) { t =>
          Seq.tabulate(flatW)(i => if (i / (aWidth / 2) == t) 1 else 0)
        }
        val activations = Seq(Seq(1, 5, 9), Seq(2, 6, 10), Seq(3, 7, 11), Seq(4, 8, 12))
        runSys(dut, aWidth, maxAcc, xDim, yDim, weights, activations, skew)
      }
    }
  }

  // ---------------------------------------------------------------
  // Equivalence with the broadcast array, same stimulus
  // ---------------------------------------------------------------

  "TrueSystolicArray is bit-identical to SystolicArray" - {
    // Geometries that make the skew non-trivial: xDim != yDim, both > 1, and one
    // case with a wide column skew (the expensive triangle at the shipped 32x8).
    Seq((8, 64, 4, 2), (4, 64, 6, 3), (8, 128, 8, 4), (8, 64, 2, 5)).foreach {
      case (aw, mA, xD, yD) =>
        val skew = xD + yD
        s"random ops agree on (aWidth=$aw, xDim=$xD, yDim=$yD)" in {
          val seed = 0xD1CE + xD * 31 + yD
          val ops  = {
            val rng = new Random(seed)
            Seq.fill(8) {
              val nAcc = 2 + rng.nextInt(math.min(8, mA - 2))
              randomOp(rng, nAcc, xD, yD, aw)
            }
          }
          simulate(new TrueSystolicArray(aw, mA, xD, yD)) { dut =>
            ops.foreach { case (w, a) =>
              resetSys(dut)
              runSys(dut, aw, mA, xD, yD, w, a, skew)
            }
          }
          // Identical stimulus through the shipped array. Both are checked against
          // the same reference, so agreement with it is agreement with each other.
          simulate(new SystolicArray(aw, mA, xD, yD)) { dut =>
            ops.foreach { case (w, a) =>
              resetBc(dut)
              runBc(dut, aw, mA, xD, yD, w, a, skew)
            }
          }
        }
    }
  }

  // ---------------------------------------------------------------
  // Stalled streams — the failure mode skew could introduce
  // ---------------------------------------------------------------

  "TrueSystolicArray survives gaps in the input stream" - {
    Seq((8, 64, 4, 3), (8, 128, 6, 2)).foreach { case (aw, mA, xD, yD) =>
      val skew = xD + yD
      s"stalled beats do not mis-pair weights and activations (xDim=$xD, yDim=$yD)" in {
        val rng  = new Random(0x6A70 + xD * 7 + yD)
        val nAcc = 6
        val (w, a) = randomOp(rng, nAcc, xD, yD, aw)
        // Stall after the first, a middle, and the last accepted beat.
        val gaps = Set(0, 3, nAcc - 1)
        simulate(new TrueSystolicArray(aw, mA, xD, yD)) { dut =>
          resetSys(dut)
          runSys(dut, aw, mA, xD, yD, w, a, skew + gaps.size, gapAfter = gaps)
        }
        // The shipped array must survive the identical stall — this pins that the
        // gap tolerance is a property of both, not something new being demanded.
        simulate(new SystolicArray(aw, mA, xD, yD)) { dut =>
          resetBc(dut)
          runBc(dut, aw, mA, xD, yD, w, a, skew + gaps.size, gapAfter = gaps)
        }
      }
    }
  }

  // ---------------------------------------------------------------
  // Drain: input must close the cycle PE(0,0) finishes
  // ---------------------------------------------------------------

  "TrueSystolicArray drain" - {
    "stops accepting input while the wavefront crosses the array" in {
      val (aw, mA, xD, yD) = (8, 64, 6, 3)
      val flatW = xD * (aw / 2)
      simulate(new TrueSystolicArray(aw, mA, xD, yD)) { dut =>
        resetSys(dut)
        val nAcc = 4
        dut.input.nAcc.valid.poke(true.B)
        dut.input.nAcc.bits.poke(nAcc.U)
        dut.input.weights_i.valid.poke(true.B)
        dut.input.activation_i.valid.poke(true.B)
        (0 until flatW).foreach(i => dut.input.weights_i.bits(i).poke(1.U))
        (0 until yD).foreach(i => dut.input.activation_i.bits(i).poke(2.U))

        // nAcc beats are accepted...
        for (_ <- 0 until nAcc) {
          dut.input.weights_i.ready.expect(true.B, "array must accept during running")
          dut.clock.step()
        }
        // ...and then the array must refuse every further beat until the deepest PE
        // lands, even though the caller is still offering them.
        var waited = 0
        while (!dut.output.valid.peek().litToBoolean && waited < xD + yD) {
          dut.input.weights_i.ready.expect(false.B,
            s"array must not accept beat during drain (cycle $waited)")
          dut.input.activation_i.ready.expect(false.B,
            s"array must not accept beat during drain (cycle $waited)")
          dut.clock.step(); waited += 1
        }
        dut.output.valid.expect(true.B, "drain must complete")
        waited must be >= 1
      }
    }
  }
}
