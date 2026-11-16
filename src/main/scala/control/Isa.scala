package control

import chisel3._

/**
  * The engine's instruction set: opcodes, the 128-bit instruction word, the per-engine
  * command bundles the dispatcher hands out, and the MMIO register map. The host-side
  * mirror is `software/include/mmfree_core.h`; the two must agree bit for bit.
  */
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
    // Runtime batch count from the instruction flags field: 1..batchSize drains only
    // that many rows; 0 (or out of range) = the full built batchSize.
    val batch      = UInt(16.W)
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

/** Register-map offsets (12-bit aperture). All registers are 16-byte aligned. */
object RegMap {
    val INSTR_WRITE = 0x000  // write: push 128-bit instruction beat
    val STATUS_READ = 0x010  // read:  packed 32-bit status word
    val IRQ_ACK     = 0x020  // write: any value clears irqPending
}
