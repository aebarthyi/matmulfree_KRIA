package bus

import chisel3._

/** AXI4-Stream slave (input side). Flat bundle so Vivado IP packager infers `s_axis_*`. */
class AxisSlaveIO(val dataWidth: Int) extends Bundle {
    val tdata  = Input(UInt(dataWidth.W))
    val tvalid = Input(Bool())
    val tready = Output(Bool())
    val tlast  = Input(Bool())
}

/** AXI4-Stream master (output side). Names map to `m_axis_*`. */
class AxisMasterIO(val dataWidth: Int) extends Bundle {
    val tdata  = Output(UInt(dataWidth.W))
    val tvalid = Output(Bool())
    val tready = Input(Bool())
    val tlast  = Output(Bool())
}
