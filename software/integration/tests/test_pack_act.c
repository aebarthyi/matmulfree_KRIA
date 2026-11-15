/* test_pack_act.c — host check of pack_act_port0 on every path (legacy, tiled, and neon when
 * built on aarch64) against an independent reference: slot r < b of beat n holds x[r*N + n],
 * slots [b, stride) hold no row's data (unchanged or zero), and no beat past N-1 is written.
 * Covers tails (N % 32, N % 8), unaligned rows, and 16/32 B beats (neon falls back to tiled
 * on 32). #includes mmfree_lib.c to drive the real code.
 *
 * Build+run:  cd software/integration/tests && make pack     (run it on the board too:
 *             the neon path only compiles on aarch64)
 */
#include "../libmmfree/mmfree_lib.c"

#include <stdio.h>

static int fails, checks;

/* Never produced by the fill below (1..30000), so a stray copy of any row shows. */
#define CANARY ((int16_t)-32768)
#define GUARD_BEATS 4u

static void check(int mode, uint32_t N, uint32_t b, uint32_t portBytes, uint32_t xoff) {
    const uint32_t stride = portBytes / 2u;
    const size_t   nslots = (size_t)(N + GUARD_BEATS) * stride;

    /* xoff int16s of lead-in: 1 misaligns every row by 2 B, 8 mimics malloc's 16 B header. */
    int16_t *xbuf = malloc(((size_t)b * N + xoff) * sizeof(int16_t));
    int16_t *act  = aligned_alloc(64, ((nslots * sizeof(int16_t) + 63u) / 64u) * 64u);
    if (!xbuf || !act) { printf("  FAIL: alloc\n"); fails++; return; }
    const int16_t *x = xbuf + xoff;
    for (uint32_t r = 0; r < b; r++)
        for (uint32_t n = 0; n < N; n++)
            xbuf[xoff + (size_t)r * N + n] = (int16_t)(((r * 4099u + n * 7u) % 30000u) + 1u);
    for (size_t i = 0; i < nslots; i++) act[i] = CANARY;

    mmfree_lib_t h;
    memset(&h, 0, sizeof(h));
    h.ctx.geom.aWidth = 16;
    h.ctx.geom.portBytes = portBytes;
    h.act[0].cached = 1;
    h.act[0].vaddr = (void *)act;

    pack_sel = mode;
    pack_act_port0(&h, x, N, b);

    int bad = 0;
    for (uint32_t n = 0; n < N && bad < 4; n++) {
        for (uint32_t s = 0; s < stride; s++) {
            const int16_t got = act[(size_t)n * stride + s];
            const int ok = (s < b) ? got == x[(size_t)s * N + n]
                                   : (got == CANARY || got == 0);
            if (!ok && bad++ < 4)
                printf("  FAIL %s N=%u b=%u pb=%u xoff=%u: beat %u slot %u = %d\n",
                       pack_name(mode), N, b, portBytes, xoff, n, s, got);
        }
    }
    for (size_t i = (size_t)N * stride; i < nslots; i++) {
        if (act[i] != CANARY) {
            printf("  FAIL %s N=%u b=%u pb=%u xoff=%u: wrote past beat N-1 (slot %zu)\n",
                   pack_name(mode), N, b, portBytes, xoff, i);
            bad++;
            break;
        }
    }
    fails += bad != 0;
    checks++;
    free(xbuf);
    free(act);
}

int main(void) {
    int modes[] = {PACK_LEGACY, PACK_TILED,
#ifdef MMFREE_PACK_HAVE_NEON
                   PACK_NEON,
#endif
    };
    const uint32_t Ns[] = {1, 7, 8, 31, 32, 33, 64, 100, 1024, 2048, 2056, 2560, 2816, 5632};
    const uint32_t pbs[] = {16, 32};
    const uint32_t xoffs[] = {0, 1, 8};

#ifdef MMFREE_PACK_HAVE_NEON
    printf("paths: legacy, tiled, neon\n");
#else
    printf("paths: legacy, tiled   (neon not built: not aarch64)\n");
#endif
    for (size_t m = 0; m < sizeof(modes) / sizeof(modes[0]); m++)
        for (size_t p = 0; p < sizeof(pbs) / sizeof(pbs[0]); p++)
            for (size_t i = 0; i < sizeof(Ns) / sizeof(Ns[0]); i++)
                for (uint32_t b = 1; b <= pbs[p] / 2u; b++)
                    for (size_t o = 0; o < sizeof(xoffs) / sizeof(xoffs[0]); o++)
                        check(modes[m], Ns[i], b, pbs[p], xoffs[o]);

    /* The default must be placement-immune: never legacy unless asked for. */
    pack_sel = -1;
    unsetenv("MMFREE_PACK");
    if (pack_mode() == PACK_LEGACY) { printf("  FAIL: default path is legacy\n"); fails++; }
    printf("default: %s\n", pack_name(pack_mode()));

    printf("%s: %d/%d shapes\n", fails ? "FAIL" : "PASS", checks - fails, checks);
    return fails ? 1 : 0;
}
