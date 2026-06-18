/*
 * test_helpers.c — board-free unit tests for libmmfree's pure-software helpers.
 *
 * Exercises the activation int16 packing and accumulator sign-expansion that
 * frame every mmfree_bitlinear() call. Architecture-independent arithmetic, so
 * it runs on the x86 dev host. The hardware path (DMA/IRQ) is validated on-board.
 *
 *   cc -Iinclude -Iintegration/libmmfree integration/libmmfree/tests/test_helpers.c \
 *      -o build/test_helpers   (helpers compiled in directly; no .so link needed)
 */

#include <stdint.h>
#include <stdio.h>
#include <string.h>

void    mmfree_pack_act_beat(volatile uint8_t *dst, int32_t value, uint32_t abytes, uint32_t nbytes);
int64_t mmfree_expand_acc(uint64_t raw, uint32_t accWidth);

static int fails = 0;
#define CHECK(cond, ...) do { if (!(cond)) { printf("FAIL: " __VA_ARGS__); printf("\n"); fails++; } } while (0)

/* a16 geometry: aWidth=16 -> abytes=2; portBytes=16; accWidth=28. */
#define ABYTES   2
#define PORTBYTES 16
#define ACCW     28

static void test_pack_act_signed_roundtrip(void) {
    const int16_t vals[] = { 0, 1, -1, 127, -128, 2047, -2048, 32767, -32768, 12345, -12345 };
    for (size_t i = 0; i < sizeof(vals)/sizeof(vals[0]); i++) {
        uint8_t beat[PORTBYTES];
        memset(beat, 0xAA, sizeof(beat));  /* poison: every byte must be written */
        mmfree_pack_act_beat(beat, (int32_t)vals[i], ABYTES, PORTBYTES);

        /* low 2 bytes are the int16 little-endian two's complement */
        int16_t got = (int16_t)((uint16_t)beat[0] | ((uint16_t)beat[1] << 8));
        CHECK(got == vals[i], "pack int16 %d -> %d", vals[i], got);
        /* the rest of the port slice is zeroed (no stale carry / poison) */
        for (int b = ABYTES; b < PORTBYTES; b++)
            CHECK(beat[b] == 0, "pack tail byte %d = 0x%02x for val %d", b, beat[b], vals[i]);
    }
}

static void test_expand_acc(void) {
    /* small positives unchanged */
    CHECK(mmfree_expand_acc(0u, ACCW) == 0, "expand 0");
    CHECK(mmfree_expand_acc(1u, ACCW) == 1, "expand 1");
    CHECK(mmfree_expand_acc(1000u, ACCW) == 1000, "expand 1000");

    /* max positive in 28 bits = 2^27 - 1 */
    CHECK(mmfree_expand_acc((1u << 27) - 1u, ACCW) == (int64_t)((1 << 27) - 1), "expand max+");
    /* sign bit set: 2^27 -> -2^27 */
    CHECK(mmfree_expand_acc(1u << 27, ACCW) == -(int64_t)(1 << 27), "expand min-");
    /* all 28 bits set -> -1 */
    CHECK(mmfree_expand_acc((1u << 28) - 1u, ACCW) == -1, "expand all-ones -> -1");
    /* -5 in 28-bit two's complement */
    uint64_t neg5 = ((1u << 28) - 1u) & (uint64_t)(-5);
    CHECK(mmfree_expand_acc(neg5, ACCW) == -5, "expand -5");

    /* bits above accWidth in the 32-bit container are ignored (masked off) */
    CHECK(mmfree_expand_acc(0xF0000000u | 1234u, ACCW) == 1234, "expand ignores high bits");
}

int main(void) {
    test_pack_act_signed_roundtrip();
    test_expand_acc();
    if (fails == 0) { printf("test_helpers: all checks passed\n"); return 0; }
    printf("test_helpers: %d check(s) FAILED\n", fails);
    return 1;
}
