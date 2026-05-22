// See README.md for license details.

package systolic

import scala.util.Random
import chisel3._
import chisel3.util.log2Ceil
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/**
  * Pure Scala reference model for the PE ternary accumulator.
  * Mirrors the hardware: each lane independently accumulates activations
  * gated by ternary weights (1 = add, 3 = subtract, 0 = hold).
  */
object PEModel {
  val WEIGHT_ADD  = 1
  val WEIGHT_SUB  = 3
  val WEIGHT_HOLD = 0

  /**
    * Compute expected PE output for a sequence of accumulation cycles.
    *
    * @param aWidth     activation bit width (must be even, >= 2)
    * @param maxAcc     max accumulations (sets output width via log2Ceil)
    * @param weights    per-cycle weight vectors, length nAcc x (aWidth/2), values in {0, 1, 3}
    * @param activations per-cycle activation values, length nAcc
    * @return expected accumulator outputs per lane as unsigned BigInts (masked to output width)
    */
  def compute(
      aWidth: Int,
      maxAcc: Int,
      weights: Seq[Seq[Int]],
      activations: Seq[Int]
  ): Seq[BigInt] = {
    val numLanes = aWidth / 2
    val nAcc = activations.length
    require(weights.length == nAcc, s"weights.length(${weights.length}) != nAcc($nAcc)")
    require(weights.forall(_.length == numLanes), s"each weight vector must have $numLanes lanes")
    require(nAcc >= 2, "PE requires nAcc >= 2 (hardware counter limitation)")

    val outWidth = log2Ceil(maxAcc) + aWidth
    val modulus = BigInt(1) << outWidth

    val accum = Array.fill(numLanes)(BigInt(0))
    for (cycle <- 0 until nAcc) {
      for (lane <- 0 until numLanes) {
        weights(cycle)(lane) match {
          case WEIGHT_ADD  => accum(lane) = (accum(lane) + BigInt(activations(cycle))) mod modulus
          case WEIGHT_SUB  => accum(lane) = (accum(lane) - BigInt(activations(cycle))) mod modulus
          case _           => // hold
        }
      }
    }
    accum.toSeq
  }
}

class PESpec extends AnyFreeSpec with Matchers with ChiselSim {

  /** Poke one cycle of PE input data */
  private def pokeInput(dut: PE, weights: Seq[Int], activation: Int, nAcc: Int): Unit = {
    weights.zipWithIndex.foreach { case (w, i) =>
      dut.input.bits.weights_i(i).poke(w.U)
    }
    dut.input.bits.activation_i.poke(activation.U)
    dut.input.bits.nAcc.poke(nAcc.U)
  }

  /** Reset DUT: assert reset for one cycle, then deassert */
  private def resetDut(dut: PE): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  /**
    * Drive a single PE operation and check output against the reference model.
    * Assumes DUT has been freshly reset.
    */
  private def runAndCheck(
      dut: PE,
      aWidth: Int,
      maxAcc: Int,
      weights: Seq[Seq[Int]],
      activations: Seq[Int]
  ): Unit = {
    val nAcc = activations.length
    val numLanes = aWidth / 2
    val expected = PEModel.compute(aWidth, maxAcc, weights, activations)

    dut.input.valid.poke(true.B)
    dut.output.ready.poke(false.B)

    for (cycle <- 0 until nAcc) {
      pokeInput(dut, weights(cycle), activations(cycle), nAcc)
      dut.clock.step()
    }

    dut.input.valid.poke(false.B)
    dut.output.valid.expect(true.B, "PE should assert output valid after nAcc cycles")

    for (lane <- 0 until numLanes) {
      dut.output.bits.accum(lane).expect(
        expected(lane).U,
        s"lane $lane: expected ${expected(lane)}"
      )
    }

    // Complete output handshake
    dut.output.ready.poke(true.B)
    dut.clock.step()
  }

  /** Generate a random ternary weight from {0, 1, 3} */
  private def randWeight(rng: Random): Int = Seq(0, 1, 3)(rng.nextInt(3))

  // ---------------------------------------------------------------
  // Deterministic functional tests
  // ---------------------------------------------------------------

  "PE(16, 1024) functional tests" - {
    val aWidth = 16
    val maxAcc = 1024
    val numLanes = aWidth / 2 // 8

    "all +1 weights, constant activation" in {
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 5
        val weights = Seq.fill(nAcc)(Seq.fill(numLanes)(1))
        val activations = Seq.fill(nAcc)(42)
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
        // each lane: 42 * 5 = 210
      }
    }

    "all -1 weights (3), constant activation" in {
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 4
        val weights = Seq.fill(nAcc)(Seq.fill(numLanes)(3))
        val activations = Seq.fill(nAcc)(10)
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
        // each lane: 0 - 40 = wraps unsigned
      }
    }

    "all hold weights (0) produces zero output" in {
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 3
        val weights = Seq.fill(nAcc)(Seq.fill(numLanes)(0))
        val activations = Seq.fill(nAcc)(9999)
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
      }
    }

    "mixed weights per lane, constant activation" in {
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 4
        val laneWeights = Seq(1, 3, 0, 1, 3, 0, 1, 3)
        val weights = Seq.fill(nAcc)(laneWeights)
        val activations = Seq.fill(nAcc)(100)
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
        // lane 0 (+1): 400, lane 1 (-1): -400 wrapped, lane 2 (hold): 0, ...
      }
    }

    "constant weights, varying activations" in {
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val weights = Seq.fill(5)(Seq.fill(numLanes)(1))
        val activations = Seq(10, 20, 30, 40, 50)
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
        // each lane: 10+20+30+40+50 = 150
      }
    }

    "varying weights and activations each cycle" in {
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val weights = Seq(
          Seq(1, 3, 0, 1, 0, 3, 1, 0),
          Seq(3, 1, 1, 0, 3, 0, 1, 1),
          Seq(0, 0, 3, 1, 1, 1, 0, 3),
        )
        val activations = Seq(100, 200, 50)
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
      }
    }

    "maximum activation value" in {
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val maxAct = (1 << aWidth) - 1 // 65535
        val nAcc = 3
        val weights = Seq.fill(nAcc)(Seq.fill(numLanes)(1))
        val activations = Seq.fill(nAcc)(maxAct)
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
        // each lane: 65535 * 3 = 196605
      }
    }

    "minimum nAcc = 2" in {
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val weights = Seq(Seq.fill(numLanes)(1), Seq.fill(numLanes)(3))
        val activations = Seq(50, 30)
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
        // each lane: 50 - 30 = 20
      }
    }

    "add then subtract same value cancels to zero" in {
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val weights = Seq(Seq.fill(numLanes)(1), Seq.fill(numLanes)(3))
        val activations = Seq(500, 500)
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
      }
    }

    "subtract from zero wraps unsigned" in {
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val weights = Seq(Seq.fill(numLanes)(3), Seq.fill(numLanes)(3))
        val activations = Seq(1, 1)
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
        // each lane: 0-1-1 = -2 mod 2^26 = 67108862
      }
    }

    "alternating add/subtract pattern" in {
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 6
        val weights = (0 until nAcc).map { c =>
          Seq.fill(numLanes)(if (c % 2 == 0) 1 else 3)
        }
        val activations = Seq.fill(nAcc)(100)
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
        // +100 -100 +100 -100 +100 -100 = 0
      }
    }

    "single lane active, others hold" in {
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 4
        // Only lane 3 accumulates; others hold
        val weights = Seq.fill(nAcc)(Seq(0, 0, 0, 1, 0, 0, 0, 0))
        val activations = Seq(10, 20, 30, 40)
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
        // lane 3: 100, all others: 0
      }
    }

    "large nAcc = 100" in {
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 100
        val rng = new Random(42)
        val weights = Seq.fill(nAcc)(Seq.fill(numLanes)(randWeight(rng)))
        val activations = Seq.fill(nAcc)(rng.nextInt(1 << aWidth))
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
      }
    }
  }

  // ---------------------------------------------------------------
  // Parameterized aWidth tests
  // ---------------------------------------------------------------

  "PE with different aWidth configurations" - {

    "aWidth=2, 1 lane" in {
      val aWidth = 2
      val maxAcc = 16
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val weights = Seq(Seq(1), Seq(3), Seq(1), Seq(0))
        val activations = Seq(3, 1, 2, 1)
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
        // lane 0: +3 -1 +2 +0 = 4
      }
    }

    "aWidth=4, 2 lanes" in {
      val aWidth = 4
      val maxAcc = 64
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val rng = new Random(99)
        val nAcc = 6
        val weights = Seq.fill(nAcc)(Seq.fill(2)(randWeight(rng)))
        val activations = Seq.fill(nAcc)(rng.nextInt(1 << aWidth))
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
      }
    }

    "aWidth=8, 4 lanes" in {
      val aWidth = 8
      val maxAcc = 256
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val rng = new Random(77)
        val nAcc = 10
        val weights = Seq.fill(nAcc)(Seq.fill(4)(randWeight(rng)))
        val activations = Seq.fill(nAcc)(rng.nextInt(1 << aWidth))
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
      }
    }

    "aWidth=32, 16 lanes" in {
      val aWidth = 32
      val maxAcc = 512
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val rng = new Random(55)
        val nAcc = 8
        val weights = Seq.fill(nAcc)(Seq.fill(16)(randWeight(rng)))
        // Stay within Int range for activation values
        val activations = Seq.fill(nAcc)(rng.nextInt(1 << 16))
        runAndCheck(dut, aWidth, maxAcc, weights, activations)
      }
    }
  }

  // ---------------------------------------------------------------
  // Handshake / protocol tests
  // ---------------------------------------------------------------

  "PE handshake protocol" - {

    "output stays valid until ready is asserted" in {
      val aWidth = 8
      val maxAcc = 64
      val numLanes = aWidth / 2
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 2
        val weights = Seq.fill(nAcc)(Seq.fill(numLanes)(1))
        val activations = Seq.fill(nAcc)(5)

        dut.input.valid.poke(true.B)
        dut.output.ready.poke(false.B)
        for (cycle <- 0 until nAcc) {
          pokeInput(dut, weights(cycle), activations(cycle), nAcc)
          dut.clock.step()
        }
        dut.input.valid.poke(false.B)

        dut.output.valid.expect(true.B)

        // Hold ready=false for several cycles; output must remain valid and stable
        val expected = PEModel.compute(aWidth, maxAcc, weights, activations)
        for (_ <- 0 until 5) {
          dut.clock.step()
          dut.output.valid.expect(true.B, "output valid must hold while ready is low")
          for (lane <- 0 until numLanes) {
            dut.output.bits.accum(lane).expect(expected(lane).U, s"lane $lane must remain stable")
          }
        }

        // Complete handshake
        dut.output.ready.poke(true.B)
        dut.clock.step()
        dut.output.valid.expect(false.B, "output valid must deassert after handshake")
      }
    }

    "input ready deasserts on final accumulation cycle" in {
      val aWidth = 8
      val maxAcc = 64
      val numLanes = aWidth / 2
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 5

        dut.input.valid.poke(true.B)
        dut.output.ready.poke(false.B)

        // First cycle (idle→running)
        pokeInput(dut, Seq.fill(numLanes)(1), 1, nAcc)
        dut.clock.step()

        // Middle cycles: input.ready should be true
        for (cycle <- 1 until nAcc - 1) {
          dut.input.ready.expect(true.B, s"ready should be true on cycle $cycle")
          pokeInput(dut, Seq.fill(numLanes)(1), 1, nAcc)
          dut.clock.step()
        }

        // Final accumulation cycle: input.ready should be false (counter === nAccReg)
        dut.input.ready.expect(false.B, "ready should deassert on final accumulation cycle")
      }
    }

    "PE idle state: input ready is high, output valid is low" in {
      val aWidth = 8
      val maxAcc = 64
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        dut.input.valid.poke(false.B)
        dut.output.ready.poke(false.B)
        dut.clock.step()
        dut.input.ready.expect(true.B, "ready should be high in idle")
        dut.output.valid.expect(false.B, "valid should be low in idle")
      }
    }
  }

  // ---------------------------------------------------------------
  // Back-to-back operations (no reset between)
  // ---------------------------------------------------------------

  "PE back-to-back operations" - {

    "two ops in a row without intervening reset" in {
      val aWidth = 8
      val maxAcc = 64
      val numLanes = aWidth / 2
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)

        // Op 1: nAcc=5, all +1, activations 1..5 → each lane sums to 15
        runAndCheck(
          dut, aWidth, maxAcc,
          weights = Seq.fill(5)(Seq.fill(numLanes)(1)),
          activations = Seq(1, 2, 3, 4, 5)
        )

        // Op 2: nAcc=3, all +1, activation 10 → each lane sums to 30.
        // Without the counter reset, accumCounter would still be at 4 from op 1
        // and this op would hang or wrap unpredictably.
        runAndCheck(
          dut, aWidth, maxAcc,
          weights = Seq.fill(3)(Seq.fill(numLanes)(1)),
          activations = Seq.fill(3)(10)
        )
      }
    }

    "ten random ops in a row without intervening reset" in {
      val aWidth = 16
      val maxAcc = 1024
      val numLanes = aWidth / 2
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val rng = new Random(2024)
        for (_ <- 0 until 10) {
          val nAcc = rng.nextInt(20) + 2
          val weights = Seq.fill(nAcc)(Seq.fill(numLanes)(randWeight(rng)))
          val activations = Seq.fill(nAcc)(rng.nextInt(1 << aWidth))
          runAndCheck(dut, aWidth, maxAcc, weights, activations)
        }
      }
    }

    "back-to-back ops with varying weight patterns" in {
      val aWidth = 8
      val maxAcc = 64
      val numLanes = aWidth / 2
      simulate(new PE(aWidth, maxAcc)) { dut =>
        resetDut(dut)

        // Op 1: subtract pattern (3s) — leaves accumReg with wrapped negatives.
        runAndCheck(
          dut, aWidth, maxAcc,
          weights = Seq.fill(3)(Seq.fill(numLanes)(3)),
          activations = Seq.fill(3)(10)
        )

        // Op 2: hold pattern (0s) — must see clean zeros, not residue from op 1.
        runAndCheck(
          dut, aWidth, maxAcc,
          weights = Seq.fill(2)(Seq.fill(numLanes)(0)),
          activations = Seq.fill(2)(99)
        )

        // Op 3: add pattern — must accumulate from zero, not from op 1 leftovers.
        runAndCheck(
          dut, aWidth, maxAcc,
          weights = Seq.fill(4)(Seq.fill(numLanes)(1)),
          activations = Seq.fill(4)(7)
        )
        // Each lane in op 3 should be exactly 28.
      }
    }
  }

  // ---------------------------------------------------------------
  // Randomized stress tests
  // ---------------------------------------------------------------

  "Randomized model comparison" - {

    "50 random operations on PE(16, 1024)" in {
      val aWidth = 16
      val maxAcc = 1024
      val numLanes = aWidth / 2
      simulate(new PE(aWidth, maxAcc)) { dut =>
        val rng = new Random(123)
        for (iter <- 0 until 50) {
          resetDut(dut)
          val nAcc = rng.nextInt(30) + 2 // [2, 31]
          val weights = Seq.fill(nAcc)(Seq.fill(numLanes)(randWeight(rng)))
          val activations = Seq.fill(nAcc)(rng.nextInt(1 << aWidth))
          runAndCheck(dut, aWidth, maxAcc, weights, activations)
        }
      }
    }

    "20 random operations on PE(8, 256)" in {
      val aWidth = 8
      val maxAcc = 256
      val numLanes = aWidth / 2
      simulate(new PE(aWidth, maxAcc)) { dut =>
        val rng = new Random(456)
        for (iter <- 0 until 20) {
          resetDut(dut)
          val nAcc = rng.nextInt(15) + 2
          val weights = Seq.fill(nAcc)(Seq.fill(numLanes)(randWeight(rng)))
          val activations = Seq.fill(nAcc)(rng.nextInt(1 << aWidth))
          runAndCheck(dut, aWidth, maxAcc, weights, activations)
        }
      }
    }

    "20 random operations on PE(4, 64)" in {
      val aWidth = 4
      val maxAcc = 64
      val numLanes = aWidth / 2
      simulate(new PE(aWidth, maxAcc)) { dut =>
        val rng = new Random(789)
        for (iter <- 0 until 20) {
          resetDut(dut)
          val nAcc = rng.nextInt(10) + 2
          val weights = Seq.fill(nAcc)(Seq.fill(numLanes)(randWeight(rng)))
          val activations = Seq.fill(nAcc)(rng.nextInt(1 << aWidth))
          runAndCheck(dut, aWidth, maxAcc, weights, activations)
        }
      }
    }
  }
}
