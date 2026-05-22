/*
 * Tiny self-test for the instruction encoder in mmfree_core.h.
 * Encodes a fixed (opcode, ptr, dim0, dim1, flags) tuple and prints the resulting
 * 128-bit beat as two uint64_t halves. Compare to control.InstructionEncoder in
 * the Scala tests to verify the bit layout matches.
 *
 * Build:  cc -I../include -o encode_sanity encode_sanity.c
 * Run:    ./encode_sanity
 */

#include <stdio.h>
#include <inttypes.h>
#include "mmfree_core.h"

static void show(const char *label, mmfree_instr_t i) {
    printf("%s: hi=0x%016" PRIx64 " lo=0x%016" PRIx64 "\n",
           label, i.u64.hi, i.u64.lo);
}

int main(void) {
    /* LOAD_ACT, ptr=0xCAFEBABE00, len=1024 — same triple as
     * AxiInstructionHandlerSpec's first LOAD_ACT test. */
    show("LOAD_ACT", mmfree_inst_load(UINT64_C(0xCAFEBABE00), 1024));

    /* COMPUTE_MM, ptr=0xDEADBEEF00, rows=64, cols=128 */
    show("COMPUTE ", mmfree_inst_compute(UINT64_C(0xDEADBEEF00), 64, 128));

    /* STORE_OUT, ptr=0x1234567890, len=256 */
    show("STORE   ", mmfree_inst_store(UINT64_C(0x1234567890), 256));

    /* Round-trip on a value that exercises all 5 fields. */
    mmfree_instr_t r = mmfree_encode(
        /* opcode */ 0x42,
        /* ptr    */ UINT64_C(0xABCDEF1234),
        /* dim0   */ 0x12345678,
        /* dim1   */ 0x87654321,
        /* flags  */ 0xBEEF);
    show("MIXED   ", r);

    return 0;
}
