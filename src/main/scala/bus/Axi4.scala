package bus

import chisel3._

/**
  * AXI4-Full slave port, slave-side view. Flat bundle so Chisel emits Vivado-recognized
  * port names of the form `<io>_awaddr`, `<io>_wdata`, etc.: naming the parent IO `s_axi`
  * yields `s_axi_awaddr`, `s_axi_wdata`, ... matching the standard slave interface contract.
  *
  * Both a parent's port and a child's port of this type are slave-side, so a pass-through
  * is `child.s_axi <> s_axi`.
  */
class AxiSlaveIO(val addrWidth: Int, val dataWidth: Int, val idWidth: Int) extends Bundle {
    require(dataWidth % 8 == 0, "dataWidth must be a multiple of 8")
    val strbWidth = dataWidth / 8

    // Write address channel
    val awid     = Input(UInt(idWidth.W))
    val awaddr   = Input(UInt(addrWidth.W))
    val awlen    = Input(UInt(8.W))
    val awsize   = Input(UInt(3.W))
    val awburst  = Input(UInt(2.W))
    val awvalid  = Input(Bool())
    val awready  = Output(Bool())

    // Write data channel
    val wdata    = Input(UInt(dataWidth.W))
    val wstrb    = Input(UInt(strbWidth.W))
    val wlast    = Input(Bool())
    val wvalid   = Input(Bool())
    val wready   = Output(Bool())

    // Write response channel
    val bid      = Output(UInt(idWidth.W))
    val bresp    = Output(UInt(2.W))
    val bvalid   = Output(Bool())
    val bready   = Input(Bool())

    // Read address channel
    val arid     = Input(UInt(idWidth.W))
    val araddr   = Input(UInt(addrWidth.W))
    val arlen    = Input(UInt(8.W))
    val arsize   = Input(UInt(3.W))
    val arburst  = Input(UInt(2.W))
    val arvalid  = Input(Bool())
    val arready  = Output(Bool())

    // Read data channel
    val rid      = Output(UInt(idWidth.W))
    val rdata    = Output(UInt(dataWidth.W))
    val rresp    = Output(UInt(2.W))
    val rlast    = Output(Bool())
    val rvalid   = Output(Bool())
    val rready   = Input(Bool())
}

object AxiResp {
    val OKAY   = "b00".U(2.W)
    val EXOKAY = "b01".U(2.W)
    val SLVERR = "b10".U(2.W)
    val DECERR = "b11".U(2.W)
}
