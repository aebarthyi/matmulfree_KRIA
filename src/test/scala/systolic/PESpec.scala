// See README.md for license details.

package systolic

import scala.util.Random
import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/**
  * This is a trivial example of how to run this Specification
  * From within sbt use:
  * {{{
  * testOnly gcd.GCDSpec
  * }}}
  * From a terminal shell use:
  * {{{
  * sbt 'testOnly gcd.GCDSpec'
  * }}}
  * Testing from mill:
  * {{{
  * mill matmulfree_KRIA.test.testOnly gcd.GCDSpec
  * }}}
*/

class PESpec extends AnyFreeSpec with Matchers with ChiselSim {

  "PE should accumulate" in {
    simulate(new PE(16, 1024)) { dut =>
        val weightValues = Array(0,1,3)
        val testWeights = Seq.fill(8)(1)
        val testExpectedOut = Seq.fill(8)(2)
        val testActivation = 1
        val numAccumulations = 2

        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.clock.step()

        dut.input.valid.poke(true.B)

        testWeights.zipWithIndex.foreach { case (w, i) =>
            dut.input.bits.weights_i(i).poke(w.U(16.W))
        }

        dut.input.bits.activation_i.poke(testActivation.U)
        dut.input.bits.nAcc.poke(numAccumulations.U)

        while(!dut.output.valid.peek().litToBoolean) {
            dut.clock.step()
        }

        testExpectedOut.zipWithIndex.foreach { case (e, i) =>
            dut.output.bits.accum(i).expect(e.U(26.W))
        }

    }
  }
}

