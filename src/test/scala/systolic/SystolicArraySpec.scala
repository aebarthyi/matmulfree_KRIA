package systolic

import scala.util.Random
import chisel3._
import chisel3.util.log2Ceil
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/**
  * Reference model for SystolicArray.
  *
  * Each row k computes the batched matrix-vector product:
  *   out[k][col·nLanes + lane] = sum_{t=0..nAcc-1} sign(W[t][col·nLanes+lane]) * A[t][k]
  *
  * with ternary weights: 1 → +activation, 3 → -activation, anything else → 0.
  * Result is reduced modulo 2^(log2Ceil(maxAcc)+aWidth) to mirror PE's unsigned wrap.
  */
object SystolicArrayModel {
  val WEIGHT_ADD  = 1
  val WEIGHT_SUB  = 3
  val WEIGHT_HOLD = 0

  /**
    * @param weights     length nAcc, each is (xDim * nLanes) ternary values
    * @param activations length nAcc, each is yDim activations (one per row)
    * @return            yDim × (xDim * nLanes) result matrix
    */
  def compute(
      aWidth: Int,
      maxAcc: Int,
      xDim: Int,
      yDim: Int,
      weights: Seq[Seq[Int]],
      activations: Seq[Seq[Int]]
  ): Seq[Seq[BigInt]] = {
    val nLanes = aWidth / 2
    val nAcc   = weights.length
    require(activations.length == nAcc,
      s"activations.length(${activations.length}) != nAcc($nAcc)")
    require(activations.forall(_.length == yDim),
      s"each activation beat must be yDim($yDim) long")
    require(weights.forall(_.length == xDim * nLanes),
      s"each weight beat must be xDim*nLanes(${xDim * nLanes}) long")

    val outWidth = log2Ceil(maxAcc) + aWidth
    val modulus  = BigInt(1) << outWidth

    val result = Array.fill(yDim, xDim * nLanes)(BigInt(0))
    for (t <- 0 until nAcc; k <- 0 until yDim) {
      val a = BigInt(activations(t)(k))
      val w = weights(t)
      for (idx <- 0 until xDim * nLanes) {
        w(idx) match {
          case WEIGHT_ADD => result(k)(idx) += a
          case WEIGHT_SUB => result(k)(idx) -= a
          case _          => // hold
        }
      }
    }
    result.map(_.map(r => ((r % modulus) + modulus) % modulus).toSeq).toSeq
  }
}

class SystolicArraySpec extends AnyFreeSpec with Matchers with ChiselSim {

  private def pokeWeights(dut: SystolicArray, w: Seq[Int]): Unit = {
    w.zipWithIndex.foreach { case (v, i) => dut.input.weights_i.bits(i).poke(v.U) }
  }

  private def pokeActivations(dut: SystolicArray, a: Seq[Int]): Unit = {
    a.zipWithIndex.foreach { case (v, i) => dut.input.activation_i.bits(i).poke(v.U) }
  }

  private def resetDut(dut: SystolicArray): Unit = {
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
    * Drive a complete SystolicArray operation. Both weights and activations stream
    * one beat per cycle; both must rendezvous on the first cycle with nAcc.
    */
  private def runAndCheck(
      dut: SystolicArray,
      aWidth: Int,
      maxAcc: Int,
      xDim: Int,
      yDim: Int,
      weights: Seq[Seq[Int]],
      activations: Seq[Seq[Int]]
  ): Unit = {
    val nAcc = weights.length
    require(activations.length == nAcc, "activations length must match weights length")
    val expected = SystolicArrayModel.compute(aWidth, maxAcc, xDim, yDim, weights, activations)

    dut.input.weights_i.valid.poke(true.B)
    dut.input.activation_i.valid.poke(true.B)
    dut.input.nAcc.valid.poke(true.B)
    dut.input.nAcc.bits.poke(nAcc.U)
    dut.output.ready.poke(false.B)

    for (t <- 0 until nAcc) {
      pokeWeights(dut, weights(t))
      pokeActivations(dut, activations(t))
      dut.clock.step()
    }

    dut.input.weights_i.valid.poke(false.B)
    dut.input.activation_i.valid.poke(false.B)
    dut.input.nAcc.valid.poke(false.B)

    dut.output.valid.expect(true.B, "output valid should be high after nAcc cycles")
    for (row <- 0 until yDim; idx <- expected(row).indices) {
      dut.output.bits.accum(row)(idx).expect(expected(row)(idx).U,
        s"accum[$row][$idx]: expected ${expected(row)(idx)}")
    }

    dut.output.ready.poke(true.B)
    dut.clock.step()
  }

  private def randWeight(rng: Random): Int = Seq(0, 1, 3)(rng.nextInt(3))

  // ---------------------------------------------------------------
  // Deterministic functional tests
  // ---------------------------------------------------------------

  "SystolicArray(aWidth=8, maxAcc=64, xDim=2, yDim=2) functional tests" - {
    val aWidth = 8
    val maxAcc = 64
    val xDim   = 2
    val yDim   = 2
    val nLanes = aWidth / 2
    val flatW  = xDim * nLanes

    "all +1 weights, constant activation per row gives row*nAcc on every lane" in {
      simulate(new SystolicArray(aWidth, maxAcc, xDim, yDim)) { dut =>
        resetDut(dut)
        val nAcc        = 5
        val weights     = Seq.fill(nAcc)(Seq.fill(flatW)(1))
        val activations = Seq.fill(nAcc)(Seq(3, 9))  // row 0 = 3, row 1 = 9, each cycle
        runAndCheck(dut, aWidth, maxAcc, xDim, yDim, weights, activations)
        // row 0: 5 * 3 = 15; row 1: 5 * 9 = 45 per lane
      }
    }

    "all -1 weights wraps unsigned" in {
      simulate(new SystolicArray(aWidth, maxAcc, xDim, yDim)) { dut =>
        resetDut(dut)
        val nAcc        = 4
        val weights     = Seq.fill(nAcc)(Seq.fill(flatW)(3))
        val activations = Seq.fill(nAcc)(Seq(2, 5))
        runAndCheck(dut, aWidth, maxAcc, xDim, yDim, weights, activations)
      }
    }

    "all-hold weights produce zero on every lane and every row" in {
      simulate(new SystolicArray(aWidth, maxAcc, xDim, yDim)) { dut =>
        resetDut(dut)
        val nAcc        = 4
        val weights     = Seq.fill(nAcc)(Seq.fill(flatW)(0))
        val activations = Seq.fill(nAcc)(Seq(99, 77))
        runAndCheck(dut, aWidth, maxAcc, xDim, yDim, weights, activations)
      }
    }

    "varying activations per cycle compute true batched matvec" in {
      simulate(new SystolicArray(aWidth, maxAcc, xDim, yDim)) { dut =>
        resetDut(dut)
        val nAcc        = 4
        val weights     = Seq.fill(nAcc)(Seq.fill(flatW)(1))           // all +1 → sum activations
        val activations = Seq(Seq(1, 5), Seq(2, 6), Seq(3, 7), Seq(4, 8))
        runAndCheck(dut, aWidth, maxAcc, xDim, yDim, weights, activations)
        // row 0: 1+2+3+4 = 10 ; row 1: 5+6+7+8 = 26
      }
    }

    "mixed weights per lane × column" in {
      simulate(new SystolicArray(aWidth, maxAcc, xDim, yDim)) { dut =>
        resetDut(dut)
        val nAcc        = 4
        val pattern     = Seq(1, 3, 0, 1, 3, 1, 0, 3)  // length flatW = 8
        val weights     = Seq.fill(nAcc)(pattern)
        val activations = Seq.fill(nAcc)(Seq(11, 22))
        runAndCheck(dut, aWidth, maxAcc, xDim, yDim, weights, activations)
      }
    }
  }

  // ---------------------------------------------------------------
  // Handshake protocol
  // ---------------------------------------------------------------

  "SystolicArray handshake" - {
    val aWidth = 8
    val maxAcc = 32
    val xDim   = 2
    val yDim   = 2
    val nLanes = aWidth / 2
    val flatW  = xDim * nLanes

    "in idle: missing any one valid keeps all readys low" in {
      simulate(new SystolicArray(aWidth, maxAcc, xDim, yDim)) { dut =>
        resetDut(dut)
        // Drive activations + nAcc but not weights
        dut.input.activation_i.valid.poke(true.B)
        dut.input.nAcc.valid.poke(true.B)
        dut.input.nAcc.bits.poke(4.U)
        dut.input.weights_i.valid.poke(false.B)
        dut.input.weights_i.ready.expect(false.B)
        dut.input.activation_i.ready.expect(false.B)
        dut.input.nAcc.ready.expect(false.B)
      }
    }

    "in running: weights and activations gate each other" in {
      simulate(new SystolicArray(aWidth, maxAcc, xDim, yDim)) { dut =>
        resetDut(dut)
        // Three-way rendezvous to enter running.
        dut.input.weights_i.valid.poke(true.B)
        dut.input.activation_i.valid.poke(true.B)
        dut.input.nAcc.valid.poke(true.B)
        dut.input.nAcc.bits.poke(8.U)
        pokeWeights(dut, Seq.fill(flatW)(1))
        pokeActivations(dut, Seq(3, 5))
        dut.clock.step()

        // Now in running. Drop weights valid; activation_i.ready should go low,
        // weights_i.ready can stay high (gated by activation_i which is still valid).
        dut.input.weights_i.valid.poke(false.B)
        dut.input.activation_i.ready.expect(false.B)
      }
    }
  }

  // ---------------------------------------------------------------
  // Randomized model comparison
  // ---------------------------------------------------------------

  "SystolicArray random model comparison" - {
    Seq((8, 64, 2, 2), (4, 32, 3, 4), (8, 128, 4, 2)).foreach { case (aw, mA, xD, yD) =>
      s"random ops on (aWidth=$aw, xDim=$xD, yDim=$yD)" in {
        simulate(new SystolicArray(aw, mA, xD, yD)) { dut =>
          val rng    = new Random(0xCAFE + xD * 31 + yD)
          val nLanes = aw / 2
          val flatW  = xD * nLanes
          val nOps   = 10
          for (_ <- 0 until nOps) {
            resetDut(dut)
            val nAcc        = 2 + rng.nextInt(math.min(8, mA - 2))
            val weights     = Seq.fill(nAcc)(Seq.fill(flatW)(randWeight(rng)))
            val activations = Seq.fill(nAcc)(Seq.fill(yD)(rng.nextInt(1 << aw)))
            runAndCheck(dut, aw, mA, xD, yD, weights, activations)
          }
        }
      }
    }
  }
}
