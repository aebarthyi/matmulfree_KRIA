/*
 * test_pack_act.c — mmfree_pack_act_rows_i16, the activation LOAD-beat scatter.
 *
 * This runs once per projection per token (5469 calls/run at 1.3B b=6, ~9% of decode),
 * and it is the one place where the producer's int32 activations become the engine's
 * int16 LOAD beats. A bug here does not crash: it streams a plausible-looking beat and
 * the engine computes a wrong dot product, which is exactly the failure mode the board
 * gate is slow to find and this test is fast to find.
 *
 * Two things changed here on 2026-08-05 and both are checked below:
 *   1. It takes int32 and saturates during the scatter. The int32 -> int16 narrow used to
 *      be a separate pass over b*N in fpga_backend, so the array was traversed twice.
 *   2. It writes the unused PE-row slots [b, stride) as zero instead of leaving them
 *      stale, so each beat is a whole-cache-line store rather than a partial one.
 *
 * The reference below is deliberately the naive two-pass form the code used to have —
 * narrow everything, then transpose — so this asserts the fused version is bit-identical
 * to what shipped, not merely self-consistent.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "mmfree_lib.h"

static int failures = 0;

static void check(int cond, const char *what) {
    if (cond) {
        printf("  ok  %s\n", what);
    } else {
        printf("  FAIL %s\n", what);
        failures++;
    }
}

/* The OLD behaviour, spelled out: pass 1 narrows with a defensive clamp, pass 2
 * transposes int16 -> beats and touches only slots [0, b). */
static void reference_two_pass(int16_t *dst, const int32_t *x,
                               uint32_t N, uint32_t b, uint32_t stride) {
    int16_t *x16 = malloc((size_t)b * N * sizeof(int16_t));
    for (size_t i = 0; i < (size_t)b * N; i++) {
        int32_t v = x[i];
        if (v > 32767) v = 32767;
        else if (v < -32768) v = -32768;
        x16[i] = (int16_t)v;
    }
    for (uint32_t n = 0; n < N; n++)
        for (uint32_t r = 0; r < b; r++)
            dst[(size_t)n * stride + r] = x16[(size_t)r * N + n];
    free(x16);
}

/* xorshift32 — deterministic, so a failure is reproducible from the seed alone. */
static uint32_t rng_state = 0x1234567u;
static uint32_t rnd(void) {
    rng_state ^= rng_state << 13; rng_state ^= rng_state >> 17; rng_state ^= rng_state << 5;
    return rng_state;
}

int main(void) {
    printf("mmfree_pack_act_rows_i16\n\n");

    /* Real geometries: stride = portBytes/2 = 8 int16 slots (128-bit HP port, aWidth=16);
     * b is the deployed batch ladder; N covers every projection inner dim in the set. */
    const uint32_t stride = 8;
    const uint32_t Ns[] = {1, 7, 256, 1024, 2048, 2816, 5632, 6912};
    const uint32_t bs[] = {1, 2, 4, 5, 6};

    printf("[1] fused int32 scatter == the old narrow-then-transpose, slot for slot\n");
    int all_match = 1, all_padded = 1;
    for (size_t ni = 0; ni < sizeof(Ns) / sizeof(*Ns); ni++) {
        for (size_t bi = 0; bi < sizeof(bs) / sizeof(*bs); bi++) {
            const uint32_t N = Ns[ni], b = bs[bi];
            int32_t *x  = malloc((size_t)b * N * sizeof(int32_t));
            for (size_t i = 0; i < (size_t)b * N; i++)
                x[i] = (int32_t)(rnd() % 65536u) - 32768;      /* in-range int16 */

            /* Prefill both with a sentinel: the reference leaves the pad slots at the
             * sentinel, the fused version must overwrite them with zero. */
            const size_t slots = (size_t)N * stride;
            int16_t *got = malloc(slots * sizeof(int16_t));
            int16_t *ref = malloc(slots * sizeof(int16_t));
            for (size_t i = 0; i < slots; i++) got[i] = ref[i] = (int16_t)0x5A5A;

            mmfree_pack_act_rows_i16(got, x, N, b, stride);
            reference_two_pass(ref, x, N, b, stride);

            for (uint32_t n = 0; n < N; n++) {
                for (uint32_t r = 0; r < b; r++)
                    if (got[(size_t)n * stride + r] != ref[(size_t)n * stride + r]) all_match = 0;
                for (uint32_t r = b; r < stride; r++)
                    if (got[(size_t)n * stride + r] != 0) all_padded = 0;
            }
            free(x); free(got); free(ref);
        }
    }
    check(all_match,  "data slots [0,b) identical to the two-pass reference (40 shapes)");
    check(all_padded, "pad slots [b,stride) zeroed, not left stale");

    printf("\n[2] saturation matches the clamp that used to live in fpga_backend\n");
    {
        /* Out-of-range int32 must saturate, not wrap. A wrap turns a large positive
         * activation into a large negative one — silently, and only on rare inputs. */
        const uint32_t N = 6, b = 2;
        int32_t x[12] = { 40000, -40000, 32767, -32768, 32768, -32769,
                          INT32_MAX, INT32_MIN, 0, 1, -1, 100000 };
        int16_t want[12] = { 32767, -32768, 32767, -32768, 32767, -32768,
                             32767, -32768, 0, 1, -1, 32767 };
        int16_t got[6 * 8];
        mmfree_pack_act_rows_i16(got, x, N, b, stride);
        int ok = 1;
        for (uint32_t r = 0; r < b; r++)
            for (uint32_t n = 0; n < N; n++)
                if (got[(size_t)n * stride + r] != want[(size_t)r * N + n]) ok = 0;
        check(ok, "int32 out of range saturates to +-32767/-32768 (no wrap)");

        int16_t ref[6 * 8];
        for (size_t i = 0; i < 6 * 8; i++) ref[i] = (int16_t)0x5A5A;
        reference_two_pass(ref, x, N, b, stride);
        int same = 1;
        for (uint32_t n = 0; n < N; n++)
            for (uint32_t r = 0; r < b; r++)
                if (got[(size_t)n * stride + r] != ref[(size_t)n * stride + r]) same = 0;
        check(same, "…and saturates exactly where the old narrow pass did");
    }

    printf("\n[3] transpose orientation: beat n carries row r at slot r\n");
    {
        /* Encode the source index into the value so a transposed/strided mix-up is
         * visible rather than merely "different". */
        const uint32_t N = 5, b = 3;
        int32_t x[15];
        for (uint32_t r = 0; r < b; r++)
            for (uint32_t n = 0; n < N; n++) x[r * N + n] = (int32_t)(r * 100 + n);
        int16_t got[5 * 8];
        mmfree_pack_act_rows_i16(got, x, N, b, stride);
        int ok = 1;
        for (uint32_t n = 0; n < N; n++)
            for (uint32_t r = 0; r < b; r++)
                if (got[(size_t)n * stride + r] != (int16_t)(r * 100 + n)) ok = 0;
        check(ok, "x[r*N+n] lands at beat n slot r (row-major -> beat-major)");
    }

    printf("\n[4] b == stride: no pad slots, nothing written past the beat\n");
    {
        const uint32_t N = 4, b = 8;                 /* b == stride */
        int32_t x[32];
        for (size_t i = 0; i < 32; i++) x[i] = (int32_t)i - 16;
        int16_t got[4 * 8 + 4];
        for (size_t i = 0; i < sizeof(got) / sizeof(*got); i++) got[i] = (int16_t)0x5A5A;
        mmfree_pack_act_rows_i16(got, x, N, b, stride);
        int ok = 1;
        for (uint32_t n = 0; n < N; n++)
            for (uint32_t r = 0; r < b; r++)
                if (got[(size_t)n * stride + r] != (int16_t)((int32_t)(r * N + n) - 16)) ok = 0;
        int no_overrun = 1;
        for (size_t i = (size_t)N * stride; i < sizeof(got) / sizeof(*got); i++)
            if (got[i] != (int16_t)0x5A5A) no_overrun = 0;
        check(ok,         "full beats pack correctly when b == stride");
        check(no_overrun, "writes stop at N*stride (no overrun past the last beat)");
    }

    printf("\n%s (%d failure%s)\n", failures ? "FAILED" : "PASSED",
           failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
