package control

import chisel3._
import chisel3.util._

object Opcode {
    val NOP        = 0x00.U(8.W)
    val LOAD_ACT   = 0x01.U(8.W)
    val COMPUTE_MM = 0x02.U(8.W)
    val STORE_OUT  = 0x03.U(8.W)
}

object ErrorCode {
    val NONE           = 0x00.U(8.W)
    val INVALID_OPCODE = 0x01.U(8.W)
}

/**
  * Decoded instruction fields from a 128-bit beat (LSB-first):
  *   [7:0]    opcode
  *   [47:8]   ptr      (40-bit physical / bus address)
  *   [79:48]  dim0
  *   [111:80] dim1
  *   [127:112] flags
  */
class Instruction extends Bundle {
    val opcode = UInt(8.W)
    val ptr    = UInt(40.W)
    val dim0   = UInt(32.W)
    val dim1   = UInt(32.W)
    val flags  = UInt(16.W)
}

object Instruction {
    def decode(raw: UInt): Instruction = {
        val inst = Wire(new Instruction)
        inst.opcode := raw(7, 0)
        inst.ptr    := raw(47, 8)
        inst.dim0   := raw(79, 48)
        inst.dim1   := raw(111, 80)
        inst.flags  := raw(127, 112)
        inst
    }
}

class LoadActivationsCmd extends Bundle {
    val ptr = UInt(40.W)
    val len = UInt(32.W)
}

class ComputeMatmulCmd extends Bundle {
    val weightsPtr = UInt(40.W)
    val rows       = UInt(32.W)
    val cols       = UInt(32.W)
}

class StoreOutputCmd extends Bundle {
    val ptr = UInt(40.W)
    val len = UInt(32.W)
}

class StatusReg extends Bundle {
    val irqPending = Bool()
    val errorCode  = UInt(8.W)
    val lastOpcode = UInt(8.W)
    val busy       = Bool()
}

/**
  * AXI4-Full slave port. Flat bundle so Chisel emits Vivado-recognized port names
  * of the form `<io>_awaddr`, `<io>_wdata`, etc. Naming the parent IO `s_axi` yields
  * `s_axi_awaddr`, `s_axi_wdata`, ... matching the standard slave interface contract.
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

/** Register-map offsets (12-bit aperture). All registers are 16-byte aligned. */
object RegMap {
    val INSTR_WRITE = 0x000  // write: push 128-bit instruction beat
    val STATUS_READ = 0x010  // read:  packed 32-bit status word
    val IRQ_ACK     = 0x020  // write: any value clears irqPending
}

/**
  * AXI4-Full slave instruction handler.
  *
  * The PS (AXI master, e.g. Zynq US+ M_AXI_HPM) writes one 128-bit instruction beat
  * to RegMap.INSTR_WRITE; the handler decodes the opcode and dispatches a Decoupled
  * command to the matching engine. On engine done it raises `irq`; the PS reads the
  * status word at RegMap.STATUS_READ and write-acks IRQ at RegMap.IRQ_ACK.
  *
  * Only single-beat AXI transactions are supported (AWLEN/ARLEN must be 0); multi-beat
  * accesses return SLVERR. Writing INSTR_WRITE while a prior instruction is still
  * pending also returns SLVERR (PS should wait for IRQ before issuing the next).
  */
class AxiInstructionHandler(
    val addrWidth: Int = 32,
    val dataWidth: Int = 128,
    val idWidth:   Int = 6
) extends Module {
    require(dataWidth >= 128, "dataWidth must be >= 128 (one beat per instruction)")

    val s_axi       = IO(new AxiSlaveIO(addrWidth, dataWidth, idWidth))

    val load        = IO(Decoupled(new LoadActivationsCmd))
    val compute     = IO(Decoupled(new ComputeMatmulCmd))
    val store       = IO(Decoupled(new StoreOutputCmd))
    val loadDone    = IO(Input(Bool()))
    val computeDone = IO(Input(Bool()))
    val storeDone   = IO(Input(Bool()))

    val status      = IO(Output(new StatusReg))
    val irq         = IO(Output(Bool()))

    // ─────────────────── Dispatcher state ───────────────────
    val idle :: decoding :: dispatching :: awaitingDone :: completed :: Nil = Enum(5)
    val dispState  = RegInit(idle)
    val instReg    = RegInit(0.U.asTypeOf(new Instruction))
    val errorReg   = RegInit(ErrorCode.NONE)
    val lastOpcReg = RegInit(0.U(8.W))
    val irqPendReg = RegInit(false.B)

    // Single-deep buffer between AXI write and dispatcher
    val pendingInst  = Reg(UInt(128.W))
    val pendingValid = RegInit(false.B)

    // ─────────────────── AXI write FSM ───────────────────
    val awHeld   = RegInit(false.B)
    val wHeld    = RegInit(false.B)
    val bSending = RegInit(false.B)

    val awAddrReg = Reg(UInt(addrWidth.W))
    val awIdReg   = Reg(UInt(idWidth.W))
    val awLenReg  = Reg(UInt(8.W))
    val wDataReg  = Reg(UInt(dataWidth.W))
    val bIdReg    = Reg(UInt(idWidth.W))
    val bRespReg  = Reg(UInt(2.W))

    s_axi.awready := !awHeld && !bSending
    s_axi.wready  := !wHeld && !bSending

    when(s_axi.awvalid && s_axi.awready) {
        awAddrReg := s_axi.awaddr
        awIdReg   := s_axi.awid
        awLenReg  := s_axi.awlen
        awHeld    := true.B
    }
    when(s_axi.wvalid && s_axi.wready) {
        wDataReg := s_axi.wdata
        wHeld    := true.B
    }

    val canProcessW = awHeld && wHeld && !bSending
    when(canProcessW) {
        val offset = awAddrReg(11, 0)
        bIdReg   := awIdReg
        bRespReg := AxiResp.DECERR  // default: unmapped offset

        when(awLenReg =/= 0.U) {
            // Multi-beat not supported
            bRespReg := AxiResp.SLVERR
        }.otherwise {
            switch(offset) {
                is(RegMap.INSTR_WRITE.U) {
                    when(!pendingValid) {
                        pendingInst  := wDataReg(127, 0)
                        pendingValid := true.B
                        bRespReg     := AxiResp.OKAY
                    }.otherwise {
                        bRespReg := AxiResp.SLVERR  // previous instruction still pending
                    }
                }
                is(RegMap.IRQ_ACK.U) {
                    irqPendReg := false.B
                    bRespReg   := AxiResp.OKAY
                }
            }
        }

        awHeld   := false.B
        wHeld    := false.B
        bSending := true.B
    }

    s_axi.bid    := bIdReg
    s_axi.bresp  := bRespReg
    s_axi.bvalid := bSending
    when(s_axi.bvalid && s_axi.bready) {
        bSending := false.B
    }

    // ─────────────────── AXI read FSM ───────────────────
    val arHeld    = RegInit(false.B)
    val rSending  = RegInit(false.B)
    val arAddrReg = Reg(UInt(addrWidth.W))
    val arIdReg   = Reg(UInt(idWidth.W))
    val arLenReg  = Reg(UInt(8.W))
    val rIdReg    = Reg(UInt(idWidth.W))
    val rDataReg  = Reg(UInt(dataWidth.W))
    val rRespReg  = Reg(UInt(2.W))

    s_axi.arready := !arHeld && !rSending

    when(s_axi.arvalid && s_axi.arready) {
        arAddrReg := s_axi.araddr
        arIdReg   := s_axi.arid
        arLenReg  := s_axi.arlen
        arHeld    := true.B
    }

    // Pack status into low 32 bits of read data:
    //   bit 0:    busy
    //   bits 7:1: reserved
    //   bits 15:8: lastOpcode
    //   bits 23:16: errorCode
    //   bit 24:   irqPending
    //   bits 31:25: reserved
    val busyBit       = dispState =/= idle
    val statusWord32  = Cat(0.U(7.W), irqPendReg, errorReg, lastOpcReg, 0.U(7.W), busyBit)
    val statusPadded  = Cat(0.U((dataWidth - 32).W), statusWord32)

    val canProcessR = arHeld && !rSending
    when(canProcessR) {
        val offset = arAddrReg(11, 0)
        rIdReg   := arIdReg
        rDataReg := 0.U
        rRespReg := AxiResp.DECERR

        when(arLenReg =/= 0.U) {
            rRespReg := AxiResp.SLVERR
        }.elsewhen(offset === RegMap.STATUS_READ.U) {
            rDataReg := statusPadded
            rRespReg := AxiResp.OKAY
        }

        arHeld   := false.B
        rSending := true.B
    }

    s_axi.rid    := rIdReg
    s_axi.rdata  := rDataReg
    s_axi.rresp  := rRespReg
    s_axi.rlast  := rSending  // single-beat: every R is the last
    s_axi.rvalid := rSending
    when(s_axi.rvalid && s_axi.rready) {
        rSending := false.B
    }

    // ─────────────────── Dispatcher logic ───────────────────
    load.valid    := false.B
    load.bits     := DontCare
    compute.valid := false.B
    compute.bits  := DontCare
    store.valid   := false.B
    store.bits    := DontCare

    status.busy       := busyBit
    status.lastOpcode := lastOpcReg
    status.errorCode  := errorReg
    status.irqPending := irqPendReg
    irq               := irqPendReg

    val completionPulse = WireDefault(false.B)

    switch(dispState) {
        is(idle) {
            when(pendingValid) {
                val decoded = Instruction.decode(pendingInst)
                instReg      := decoded
                lastOpcReg   := decoded.opcode
                errorReg     := ErrorCode.NONE
                pendingValid := false.B
                dispState    := decoding
            }
        }
        is(decoding) {
            val isValidOp = (instReg.opcode === Opcode.LOAD_ACT) ||
                            (instReg.opcode === Opcode.COMPUTE_MM) ||
                            (instReg.opcode === Opcode.STORE_OUT)
            when(isValidOp) {
                dispState := dispatching
            }.otherwise {
                errorReg  := ErrorCode.INVALID_OPCODE
                dispState := completed
            }
        }
        is(dispatching) {
            switch(instReg.opcode) {
                is(Opcode.LOAD_ACT) {
                    load.valid    := true.B
                    load.bits.ptr := instReg.ptr
                    load.bits.len := instReg.dim0
                    when(load.fire) { dispState := awaitingDone }
                }
                is(Opcode.COMPUTE_MM) {
                    compute.valid           := true.B
                    compute.bits.weightsPtr := instReg.ptr
                    compute.bits.rows       := instReg.dim0
                    compute.bits.cols       := instReg.dim1
                    when(compute.fire) { dispState := awaitingDone }
                }
                is(Opcode.STORE_OUT) {
                    store.valid    := true.B
                    store.bits.ptr := instReg.ptr
                    store.bits.len := instReg.dim0
                    when(store.fire) { dispState := awaitingDone }
                }
            }
        }
        is(awaitingDone) {
            val anyDone = loadDone || computeDone || storeDone
            when(anyDone) {
                dispState := completed
            }
        }
        is(completed) {
            completionPulse := true.B
            dispState       := idle
        }
    }

    // completionPulse wins ties with the AXI-driven IRQ ack so an IRQ is never dropped.
    when(completionPulse) {
        irqPendReg := true.B
    }
}
