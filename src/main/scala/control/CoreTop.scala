package control

import chisel3._
import chisel3.util._

/**
  * Xilinx-convention top wrapper around [[Core]] for Vivado IP packaging.
  *
  * Exposes `aclk`, `aresetn` (active-low), the AXI4 instruction slave, AXI-Stream
  * weight/activation slave, AXI-Stream output master, and the `irq` line. The
  * wrapper is a thin pass-through: it inverts `aresetn` to drive Core's sync
  * active-high reset and renames the clock to `aclk`. All compute logic lives in
  * [[Core]] under the implicit Chisel clock/reset domain.
  */
class CoreTop(
    val aWidth:       Int = 8,
    val maxAcc:       Int = 4096,
    val xDim:         Int = 8,
    val batchSize:    Int = 1,
    val maxN:         Int = 4096,
    val maxM:         Int = 1024,
    val outBeatLanes: Int = 0,
    val axiAddrWidth: Int = 32,
    val axiDataWidth: Int = 128,
    val axiIdWidth:   Int = 6
) extends RawModule {

    // Width math (mirrors Core.scala) — required so the IO bundles can size themselves.
    private val nLanes          = aWidth / 2
    private val outLanesPerTile = xDim * nLanes
    private val effOutBeatLanes = if (outBeatLanes == 0) outLanesPerTile else outBeatLanes
    private val tileAccWidth    = log2Ceil(maxAcc) + aWidth
    private val outAccWidth     = tileAccWidth
    private val outLaneWidth    = 1 << log2Ceil(outAccWidth)

    private val axisDataWidth = math.max(xDim, batchSize) * aWidth
    private val outBeatWidth  = effOutBeatLanes * outLaneWidth

    val aclk    = IO(Input(Clock()))
    val aresetn = IO(Input(Bool()))
    val s_axi   = IO(new AxiSlaveIO(axiAddrWidth, axiDataWidth, axiIdWidth))
    val s_axis  = IO(new AxisSlaveIO(axisDataWidth))
    val m_axis  = IO(new AxisMasterIO(outBeatWidth))
    val irq     = IO(Output(Bool()))

    withClockAndReset(aclk, !aresetn) {
        val core = Module(new Core(
            aWidth       = aWidth,
            maxAcc       = maxAcc,
            xDim         = xDim,
            batchSize    = batchSize,
            maxN         = maxN,
            maxM         = maxM,
            outBeatLanes = outBeatLanes,
            axiAddrWidth = axiAddrWidth,
            axiDataWidth = axiDataWidth,
            axiIdWidth   = axiIdWidth
        ))

        Core.connectAxiSlave(outer = s_axi, inner = core.s_axi)

        core.s_axis.tvalid := s_axis.tvalid
        core.s_axis.tdata  := s_axis.tdata
        core.s_axis.tlast  := s_axis.tlast
        s_axis.tready      := core.s_axis.tready

        m_axis.tvalid      := core.m_axis.tvalid
        m_axis.tdata       := core.m_axis.tdata
        m_axis.tlast       := core.m_axis.tlast
        core.m_axis.tready := m_axis.tready

        irq := core.irq
    }
}
