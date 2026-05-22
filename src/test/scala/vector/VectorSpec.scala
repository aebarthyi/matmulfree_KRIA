package vector

import scala.util.Random
import chisel3._
import chisel3.util.log2Ceil
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/**
  * Pure Scala reference model for the Vector dot-product accumulator.
  * Each cycle: nLanes ternary contributions are summed; the per-cycle sum is
  * added to a running scalar accumulator. Weight 1 = +act, 3 = -act, 0 = hold.
  * Result is masked to the hardware output width (2's-complement reinterpreted as UInt).
  */
object VectorModel {
  val WEIGHT_ADD  = 1
  val WEIGHT_SUB  = 3
  val WEIGHT_HOLD = 0

  def outWidth(aWidth: Int, nLanes: Int, maxAcc: Int): Int =
    aWidth + 1 + log2Ceil(nLanes) + log2Ceil(maxAcc)

  def compute(
      aWidth: Int,
      nLanes: Int,
      maxAcc: Int,
      weights: Seq[Seq[Int]],
      activations: Seq[Seq[Int]]
  ): BigInt = {
    val nAcc = activations.length
    require(weights.length == nAcc, s"weights.length(${weights.length}) != nAcc($nAcc)")
    require(weights.forall(_.length == nLanes), s"each weight vector must have $nLanes lanes")
    require(activations.forall(_.length == nLanes), s"each activation vector must have $nLanes lanes")
    require(nAcc >= 2, "Vector requires nAcc >= 2")

    val modulus = BigInt(1) << outWidth(aWidth, nLanes, maxAcc)

    var accum = BigInt(0)
    for (cycle <- 0 until nAcc) {
      var laneSum = BigInt(0)
      for (lane <- 0 until nLanes) {
        weights(cycle)(lane) match {
          case WEIGHT_ADD => laneSum += BigInt(activations(cycle)(lane))
          case WEIGHT_SUB => laneSum -= BigInt(activations(cycle)(lane))
          case _          => // hold
        }
      }
      accum += laneSum
    }
    ((accum % modulus) + modulus) % modulus
  }
}

class VectorSpec extends AnyFreeSpec with Matchers with ChiselSim {

  private def pokeWeights(dut: Vector, w: Seq[Int]): Unit = {
    w.zipWithIndex.foreach { case (v, i) => dut.input.weights_i.bits(i).poke(v.U) }
  }

  private def pokeActivations(dut: Vector, a: Seq[Int]): Unit = {
    a.zipWithIndex.foreach { case (v, i) => dut.input.activation_i.bits(i).poke(v.U) }
  }

  private def resetDut(dut: Vector): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  /**
    * Drive a complete Vector operation: hold all three input streams valid for nAcc
    * cycles with fresh data each cycle, then check the accumulator against the model.
    * Assumes DUT has been freshly reset.
    */
  private def runAndCheck(
      dut: Vector,
      aWidth: Int,
      nLanes: Int,
      maxAcc: Int,
      weights: Seq[Seq[Int]],
      activations: Seq[Seq[Int]]
  ): Unit = {
    val nAcc = activations.length
    val expected = VectorModel.compute(aWidth, nLanes, maxAcc, weights, activations)

    dut.input.weights_i.valid.poke(true.B)
    dut.input.activation_i.valid.poke(true.B)
    dut.input.nAcc.valid.poke(true.B)
    dut.input.nAcc.bits.poke(nAcc.U)
    dut.output.ready.poke(false.B)

    for (cycle <- 0 until nAcc) {
      pokeWeights(dut, weights(cycle))
      pokeActivations(dut, activations(cycle))
      dut.clock.step()
    }

    dut.input.weights_i.valid.poke(false.B)
    dut.input.activation_i.valid.poke(false.B)
    dut.input.nAcc.valid.poke(false.B)

    dut.output.valid.expect(true.B, "Vector should assert output valid after nAcc cycles")
    dut.output.bits.accum.expect(expected.U, s"accum: expected $expected")

    // Complete output handshake
    dut.output.ready.poke(true.B)
    dut.clock.step()
  }

  private def randWeight(rng: Random): Int = Seq(0, 1, 3)(rng.nextInt(3))

  // ---------------------------------------------------------------
  // Deterministic functional tests
  // ---------------------------------------------------------------

  "Vector(aWidth=8, nLanes=4, maxAcc=64) functional tests" - {
    val aWidth = 8
    val nLanes = 4
    val maxAcc = 64

    "all +1 weights, constant activation per lane" in {
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 5
        val weights = Seq.fill(nAcc)(Seq.fill(nLanes)(1))
        val activations = Seq.fill(nAcc)(Seq.fill(nLanes)(7))
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
        // total = 5 cycles * 4 lanes * 7 = 140
      }
    }

    "all -1 (weight=3) weights, constant activation" in {
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 4
        val weights = Seq.fill(nAcc)(Seq.fill(nLanes)(3))
        val activations = Seq.fill(nAcc)(Seq.fill(nLanes)(10))
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
        // total = -160, wraps unsigned
      }
    }

    "all hold weights produces zero" in {
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 3
        val weights = Seq.fill(nAcc)(Seq.fill(nLanes)(0))
        val activations = Seq.fill(nAcc)(Seq.fill(nLanes)(123))
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
      }
    }

    "mixed weights per lane, constant activation" in {
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 4
        val laneWeights = Seq(1, 3, 0, 1) // +1 -1 hold +1 → net per cycle: +act + -act + 0 + +act = +act
        val weights = Seq.fill(nAcc)(laneWeights)
        val activations = Seq.fill(nAcc)(Seq.fill(nLanes)(50))
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
        // per cycle: (50 - 50 + 0 + 50) = 50; total over 4 cycles = 200
      }
    }

    "add then subtract same value cancels to zero" in {
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val weights = Seq(Seq.fill(nLanes)(1), Seq.fill(nLanes)(3))
        val activations = Seq(Seq.fill(nLanes)(200), Seq.fill(nLanes)(200))
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
        // 4*200 - 4*200 = 0
      }
    }

    "subtract from zero wraps unsigned" in {
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val weights = Seq(Seq.fill(nLanes)(3), Seq.fill(nLanes)(3))
        val activations = Seq(Seq.fill(nLanes)(1), Seq.fill(nLanes)(1))
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
        // total = -8, masked to output width
      }
    }

    "alternating add/subtract pattern" in {
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 6
        val weights = (0 until nAcc).map { c =>
          Seq.fill(nLanes)(if (c % 2 == 0) 1 else 3)
        }
        val activations = Seq.fill(nAcc)(Seq.fill(nLanes)(20))
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
        // (+80 -80) * 3 = 0
      }
    }

    "single lane active, others hold" in {
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 4
        // Only lane 2 accumulates with +1
        val weights = Seq.fill(nAcc)(Seq(0, 0, 1, 0))
        val activations = Seq(
          Seq(10, 10, 10, 10),
          Seq(20, 20, 20, 20),
          Seq(30, 30, 30, 30),
          Seq(40, 40, 40, 40)
        )
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
        // total = 10 + 20 + 30 + 40 = 100
      }
    }

    "varying weights and activations each cycle" in {
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val weights = Seq(
          Seq(1, 3, 0, 1),
          Seq(3, 1, 1, 0),
          Seq(0, 0, 3, 1)
        )
        val activations = Seq(
          Seq(10, 20, 30, 40),
          Seq(50, 60, 70, 80),
          Seq(15, 25, 35, 45)
        )
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
      }
    }

    "maximum activation value" in {
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val maxAct = (1 << aWidth) - 1
        val nAcc = 3
        val weights = Seq.fill(nAcc)(Seq.fill(nLanes)(1))
        val activations = Seq.fill(nAcc)(Seq.fill(nLanes)(maxAct))
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
      }
    }

    "minimum nAcc = 2" in {
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val weights = Seq(Seq.fill(nLanes)(1), Seq.fill(nLanes)(3))
        val activations = Seq(Seq.fill(nLanes)(33), Seq.fill(nLanes)(11))
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
        // 4*33 - 4*11 = 88
      }
    }

    "large nAcc = 50" in {
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 50
        val rng = new Random(42)
        val weights = Seq.fill(nAcc)(Seq.fill(nLanes)(randWeight(rng)))
        val activations = Seq.fill(nAcc)(Seq.fill(nLanes)(rng.nextInt(1 << aWidth)))
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
      }
    }
  }

  // ---------------------------------------------------------------
  // Parameterized configurations
  // ---------------------------------------------------------------

  "Vector with different configurations" - {

    "aWidth=4, nLanes=2, maxAcc=16" in {
      val aWidth = 4
      val nLanes = 2
      val maxAcc = 16
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val rng = new Random(99)
        val nAcc = 6
        val weights = Seq.fill(nAcc)(Seq.fill(nLanes)(randWeight(rng)))
        val activations = Seq.fill(nAcc)(Seq.fill(nLanes)(rng.nextInt(1 << aWidth)))
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
      }
    }

    "aWidth=8, nLanes=8, maxAcc=256" in {
      val aWidth = 8
      val nLanes = 8
      val maxAcc = 256
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val rng = new Random(77)
        val nAcc = 10
        val weights = Seq.fill(nAcc)(Seq.fill(nLanes)(randWeight(rng)))
        val activations = Seq.fill(nAcc)(Seq.fill(nLanes)(rng.nextInt(1 << aWidth)))
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
      }
    }

    "aWidth=16, nLanes=16, maxAcc=512" in {
      val aWidth = 16
      val nLanes = 16
      val maxAcc = 512
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val rng = new Random(55)
        val nAcc = 8
        val weights = Seq.fill(nAcc)(Seq.fill(nLanes)(randWeight(rng)))
        val activations = Seq.fill(nAcc)(Seq.fill(nLanes)(rng.nextInt(1 << aWidth)))
        runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
      }
    }
  }

  // ---------------------------------------------------------------
  // Handshake / protocol tests
  // ---------------------------------------------------------------

  "Vector handshake protocol" - {

    "after reset, in idle: output.valid low, all input readys low when nothing valid" in {
      val aWidth = 8
      val nLanes = 4
      val maxAcc = 64
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        dut.input.weights_i.valid.poke(false.B)
        dut.input.activation_i.valid.poke(false.B)
        dut.input.nAcc.valid.poke(false.B)
        dut.output.ready.poke(false.B)
        dut.clock.step()
        dut.output.valid.expect(false.B, "valid should be low in idle")
        dut.input.nAcc.ready.expect(false.B, "nAcc.ready requires all three valids in idle")
        dut.input.weights_i.ready.expect(false.B, "weights.ready requires all three valids in idle")
        dut.input.activation_i.ready.expect(false.B, "activation.ready requires all three valids in idle")
      }
    }

    "in idle: any single missing valid keeps all input readys low" in {
      val aWidth = 8
      val nLanes = 4
      val maxAcc = 64
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        dut.output.ready.poke(false.B)

        // weights+activation valid, nAcc not → no rendezvous
        dut.input.weights_i.valid.poke(true.B)
        dut.input.activation_i.valid.poke(true.B)
        dut.input.nAcc.valid.poke(false.B)
        dut.input.weights_i.ready.expect(false.B, "weights.ready should be low without nAcc")
        dut.input.activation_i.ready.expect(false.B, "activation.ready should be low without nAcc")
        dut.input.nAcc.ready.expect(false.B)

        // weights+nAcc valid, activation not
        dut.input.activation_i.valid.poke(false.B)
        dut.input.nAcc.valid.poke(true.B)
        dut.input.weights_i.ready.expect(false.B)
        dut.input.nAcc.ready.expect(false.B)

        // activation+nAcc valid, weights not
        dut.input.weights_i.valid.poke(false.B)
        dut.input.activation_i.valid.poke(true.B)
        dut.input.activation_i.ready.expect(false.B)
        dut.input.nAcc.ready.expect(false.B)
      }
    }

    "in idle: all three valids cause all three readys to assert" in {
      val aWidth = 8
      val nLanes = 4
      val maxAcc = 64
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        dut.output.ready.poke(false.B)
        dut.input.weights_i.valid.poke(true.B)
        dut.input.activation_i.valid.poke(true.B)
        dut.input.nAcc.valid.poke(true.B)
        dut.input.nAcc.bits.poke(3.U)
        pokeWeights(dut, Seq.fill(nLanes)(1))
        pokeActivations(dut, Seq.fill(nLanes)(1))
        dut.input.nAcc.ready.expect(true.B, "nAcc.ready high when rendezvoused")
        dut.input.weights_i.ready.expect(true.B, "weights.ready high when rendezvoused")
        dut.input.activation_i.ready.expect(true.B, "activation.ready high when rendezvoused")
      }
    }

    "in running: nAcc.ready stays low (only consumed once)" in {
      val aWidth = 8
      val nLanes = 4
      val maxAcc = 64
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 4
        dut.input.weights_i.valid.poke(true.B)
        dut.input.activation_i.valid.poke(true.B)
        dut.input.nAcc.valid.poke(true.B)
        dut.input.nAcc.bits.poke(nAcc.U)
        dut.output.ready.poke(false.B)
        pokeWeights(dut, Seq.fill(nLanes)(1))
        pokeActivations(dut, Seq.fill(nLanes)(1))
        dut.clock.step()  // idle → running

        // Now in running: nAcc.ready should be low
        dut.input.nAcc.ready.expect(false.B, "nAcc.ready must drop in running state")
      }
    }

    "in running: weights+activation gate each other (no progress if one is invalid)" in {
      val aWidth = 8
      val nLanes = 4
      val maxAcc = 64
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 3
        dut.input.weights_i.valid.poke(true.B)
        dut.input.activation_i.valid.poke(true.B)
        dut.input.nAcc.valid.poke(true.B)
        dut.input.nAcc.bits.poke(nAcc.U)
        dut.output.ready.poke(false.B)
        pokeWeights(dut, Seq.fill(nLanes)(1))
        pokeActivations(dut, Seq.fill(nLanes)(5))
        dut.clock.step()  // do first accumulation, → running

        // De-assert activation valid: weights.ready must drop
        dut.input.activation_i.valid.poke(false.B)
        dut.input.weights_i.ready.expect(false.B, "weights.ready must drop when activation invalid")
        // No state change while stalled; step a few cycles
        for (_ <- 0 until 3) dut.clock.step()
        dut.output.valid.expect(false.B, "still running, output should not be valid")

        // Resume by re-asserting activation valid
        dut.input.activation_i.valid.poke(true.B)
        dut.input.weights_i.ready.expect(true.B, "weights.ready returns when activation valid")
        dut.input.activation_i.ready.expect(true.B)

        // Complete the remaining nAcc - 1 cycles
        for (_ <- 0 until nAcc - 1) dut.clock.step()
        dut.output.valid.expect(true.B)
      }
    }

    "output stays valid and stable until ready is asserted" in {
      val aWidth = 8
      val nLanes = 4
      val maxAcc = 64
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 3
        val weights = Seq.fill(nAcc)(Seq.fill(nLanes)(1))
        val activations = Seq.fill(nAcc)(Seq.fill(nLanes)(7))
        val expected = VectorModel.compute(aWidth, nLanes, maxAcc, weights, activations)

        dut.input.weights_i.valid.poke(true.B)
        dut.input.activation_i.valid.poke(true.B)
        dut.input.nAcc.valid.poke(true.B)
        dut.input.nAcc.bits.poke(nAcc.U)
        dut.output.ready.poke(false.B)
        for (cycle <- 0 until nAcc) {
          pokeWeights(dut, weights(cycle))
          pokeActivations(dut, activations(cycle))
          dut.clock.step()
        }
        dut.input.weights_i.valid.poke(false.B)
        dut.input.activation_i.valid.poke(false.B)
        dut.input.nAcc.valid.poke(false.B)

        dut.output.valid.expect(true.B)
        for (_ <- 0 until 5) {
          dut.clock.step()
          dut.output.valid.expect(true.B, "valid must hold while ready low")
          dut.output.bits.accum.expect(expected.U, "accum must remain stable")
        }

        dut.output.ready.poke(true.B)
        dut.clock.step()
        dut.output.valid.expect(false.B, "valid drops after handshake completes")
      }
    }

    "back-to-back operations: accumReg resets between runs" in {
      val aWidth = 8
      val nLanes = 4
      val maxAcc = 64
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        resetDut(dut)
        val nAcc = 2

        // Run 1: all +1, act=10
        val w1 = Seq.fill(nAcc)(Seq.fill(nLanes)(1))
        val a1 = Seq.fill(nAcc)(Seq.fill(nLanes)(10))
        runAndCheck(dut, aWidth, nLanes, maxAcc, w1, a1)
        // After handshake, back in idle

        // Run 2: all +1, act=20 (different — confirms accumReg reset)
        val w2 = Seq.fill(nAcc)(Seq.fill(nLanes)(1))
        val a2 = Seq.fill(nAcc)(Seq.fill(nLanes)(20))
        runAndCheck(dut, aWidth, nLanes, maxAcc, w2, a2)
        // 2 cycles * 4 lanes * 20 = 160 (not 240, which would happen if no reset)
      }
    }
  }

  // ---------------------------------------------------------------
  // Randomized stress tests
  // ---------------------------------------------------------------

  "Randomized model comparison" - {

    "30 random operations on Vector(nLanes=4, aWidth=8, maxAcc=64)" in {
      val aWidth = 8
      val nLanes = 4
      val maxAcc = 64
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        val rng = new Random(123)
        for (_ <- 0 until 30) {
          resetDut(dut)
          val nAcc = rng.nextInt(20) + 2
          val weights = Seq.fill(nAcc)(Seq.fill(nLanes)(randWeight(rng)))
          val activations = Seq.fill(nAcc)(Seq.fill(nLanes)(rng.nextInt(1 << aWidth)))
          runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
        }
      }
    }

    "20 random operations on Vector(nLanes=8, aWidth=8, maxAcc=128)" in {
      val aWidth = 8
      val nLanes = 8
      val maxAcc = 128
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        val rng = new Random(456)
        for (_ <- 0 until 20) {
          resetDut(dut)
          val nAcc = rng.nextInt(15) + 2
          val weights = Seq.fill(nAcc)(Seq.fill(nLanes)(randWeight(rng)))
          val activations = Seq.fill(nAcc)(Seq.fill(nLanes)(rng.nextInt(1 << aWidth)))
          runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
        }
      }
    }

    "15 random operations on Vector(nLanes=2, aWidth=4, maxAcc=16)" in {
      val aWidth = 4
      val nLanes = 2
      val maxAcc = 16
      simulate(new Vector(nLanes, aWidth, maxAcc)) { dut =>
        val rng = new Random(789)
        for (_ <- 0 until 15) {
          resetDut(dut)
          val nAcc = rng.nextInt(10) + 2
          val weights = Seq.fill(nAcc)(Seq.fill(nLanes)(randWeight(rng)))
          val activations = Seq.fill(nAcc)(Seq.fill(nLanes)(rng.nextInt(1 << aWidth)))
          runAndCheck(dut, aWidth, nLanes, maxAcc, weights, activations)
        }
      }
    }
  }
}
