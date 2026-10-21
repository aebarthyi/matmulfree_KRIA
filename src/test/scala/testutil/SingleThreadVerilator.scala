package testutil

import chisel3.simulator.scalatest.ChiselSim
import svsim.{Backend, BackendSettingsModifications}
import svsim.verilator.{Backend => VerilatorBackend}

/** Mix-in (in place of `ChiselSim`) that pins each Verilator build to one thread
  * (`-j 1`). With Mill running many suites in parallel, ChiselSim's default `-j 0`
  * oversubscribes the host and Verilator intermittently aborts with "attempted to
  * destroy locked Thread Pool"; single-threaded builds make the suite deterministic.
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
