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
    val axiIdWidth:   Int = 6,
    val signedAct:    Boolean = false
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

    // Input-stream port split (mirrors CoreConfig): one PS HP port carries at most
    // 128 bits, so wider streams arrive on N parallel 128-bit s_axis ports that are
    // joined back into Core's single wide beat here.
    private val numInPorts  = if (axisDataWidth <= 128) 1 else math.min(4, (axisDataWidth + 127) / 128)
    private val inPortWidth = axisDataWidth / numInPorts
    require(numInPorts == 1 || axisDataWidth % 128 == 0,
        s"axisDataWidth=$axisDataWidth must be a multiple of 128 when split across $numInPorts ports")

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
            axiIdWidth   = axiIdWidth,
            signedAct    = signedAct
        ))

        Core.connectAxiSlave(outer = s_axi, inner = core.s_axi)

        if (numInPorts == 1) {
            // Exact pass-through, kept separate from the join so the emitted SV for
            // existing single-port presets stays byte-identical.
            val s_axis = sAxisPorts.head
            core.s_axis.tvalid := s_axis.tvalid
            core.s_axis.tdata  := s_axis.tdata
            core.s_axis.tlast  := s_axis.tlast
            s_axis.tready      := core.s_axis.tready
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

        m_axis.tvalid      := core.m_axis.tvalid
        m_axis.tdata       := core.m_axis.tdata
        m_axis.tlast       := core.m_axis.tlast
        core.m_axis.tready := m_axis.tready

        irq := core.irq
    }
}
