package testutil

import chisel3.simulator.scalatest.ChiselSim
import svsim.{Backend, BackendSettingsModifications}
import svsim.verilator.{Backend => VerilatorBackend}

/** Mix-in (in place of `ChiselSim`) that pins each Verilator invocation to a
  * single thread (`-j 1`).
  *
  * ChiselSim drives `verilator --build -j 0`, which sizes a per-process thread
  * pool to the host core count. Mill fans the test suites out across roughly one
  * worker per core, so a dozen-plus Verilator builds run at once, each wanting
  * all cores. Under that oversubscription Verilator intermittently aborts a
  * random build with:
  *
  *   %Error: Internal Error: attempted to destroy locked Thread Pool
  *
  * which surfaces as a flaky failure in whichever suite lost the race. Forcing
  * `-j 1` removes the per-process thread pool entirely; inter-suite parallelism
  * (Mill workers) still keeps the aggregate run reasonable. It does lengthen the
  * full run somewhat (the larger designs verilate single-threaded), which is a
  * worthwhile trade for a deterministic, non-flaky suite.
  */
trait SingleThreadVerilator extends ChiselSim { this: org.scalatest.TestSuite =>
  implicit override def backendSettingsModifications: BackendSettingsModifications = {
    val base = super.backendSettingsModifications
    new BackendSettingsModifications {
      def apply(settings: Backend.Settings): Backend.Settings =
        base(settings) match {
          case v: VerilatorBackend.CompilationSettings =>
            v.withParallelism(
              Some(VerilatorBackend.CompilationSettings.Parallelism.Uniform.default.withNum(1))
            )
          case other => other
        }
    }
  }
}
