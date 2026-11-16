package core

import chisel3._
import chisel3.util._
import bus.{AxiSlaveIO, AxisMasterIO, AxisSlaveIO}

/**
  * Xilinx-convention top wrapper around [[Core]] for Vivado IP packaging.
  *
  * Exposes `aclk`, `aresetn` (active-low), the AXI4 instruction slave, AXI-Stream
  * weight/activation slave(s), AXI-Stream output master, and the `irq` line. The
  * wrapper is a thin pass-through: it inverts `aresetn` to drive Core's sync
  * active-high reset, renames the clock to `aclk`, and joins the per-HP-port input
  * streams. All compute logic lives in [[Core]] under the implicit Chisel clock/reset.
  */
class CoreTop(val cfg: CoreConfig) extends RawModule {
    import cfg.{axiAddrWidth, axiDataWidth, axiIdWidth, axisDataWidth, inPortWidth, numInPorts, outBeatWidth}

    val aclk    = IO(Input(Clock()))
    val aresetn = IO(Input(Bool()))
    val s_axi   = IO(new AxiSlaveIO(axiAddrWidth, axiDataWidth, axiIdWidth))

    /** N==1: single port named literally `s_axis` (byte-identical to the legacy top).
      * N>1: `s_axis_0..s_axis_{N-1}`, port 0 carrying the least-significant 128 bits.
      * Suffixed individual IOs (not a Vec) — EmitCore lowers with disallowPackedArrays
      * and Vivado's packager needs one flat `s_axis_i_*` interface per port. */
    val sAxisPorts: Seq[AxisSlaveIO] =
        if (numInPorts == 1) Seq(IO(new AxisSlaveIO(axisDataWidth)).suggestName("s_axis"))
        else Seq.tabulate(numInPorts)(i => IO(new AxisSlaveIO(inPortWidth)).suggestName(s"s_axis_$i"))

    val m_axis  = IO(new AxisMasterIO(outBeatWidth))
    val irq     = IO(Output(Bool()))

    withClockAndReset(aclk, !aresetn) {
        val core = Module(new Core(cfg))

        core.s_axi <> s_axi

        if (numInPorts == 1) {
            core.s_axis <> sAxisPorts.head
        } else {
            // Standard AXIS join: a wide beat advances only when every port presents
            // its slice, and all treadys drop together when Core drops tready (the
            // capture-cycle backpressure — no port may run ahead). tlast from port 0.
            core.s_axis.tvalid := sAxisPorts.map(_.tvalid).reduce(_ && _)
            core.s_axis.tdata  := Cat(sAxisPorts.reverse.map(_.tdata)) // port 0 = LSBs
            core.s_axis.tlast  := sAxisPorts.head.tlast
            for (i <- sAxisPorts.indices) {
                val othersValid = sAxisPorts.zipWithIndex
                    .collect { case (p, j) if j != i => p.tvalid }
                    .reduce(_ && _)
                sAxisPorts(i).tready := core.s_axis.tready && othersValid
            }
        }

        m_axis <> core.m_axis

        irq := core.irq
    }
}
