package testutil

/**
  * Host-side encoder for the 128-bit instruction word — an independent mirror of
  * `control.Instruction` / `software/include/mmfree_core.h`, deliberately not derived from
  * the RTL constants so a drifted field layout fails the specs.
  */
object InstructionEncoder {
  def encode(opcode: BigInt, ptr: BigInt, dim0: BigInt, dim1: BigInt, flags: BigInt = 0): BigInt = {
    require(opcode >= 0 && opcode < (BigInt(1) << 8),  "opcode must fit in 8 bits")
    require(ptr    >= 0 && ptr    < (BigInt(1) << 40), "ptr must fit in 40 bits")
    require(dim0   >= 0 && dim0   < (BigInt(1) << 32), "dim0 must fit in 32 bits")
    require(dim1   >= 0 && dim1   < (BigInt(1) << 32), "dim1 must fit in 32 bits")
    require(flags  >= 0 && flags  < (BigInt(1) << 16), "flags must fit in 16 bits")
    opcode |
      (ptr  <<   8) |
      (dim0 <<  48) |
      (dim1 <<  80) |
      (flags << 112)
  }

  val LOAD_ACT   = BigInt(0x01)
  val COMPUTE_MM = BigInt(0x02)
  val STORE_OUT  = BigInt(0x03)
}
