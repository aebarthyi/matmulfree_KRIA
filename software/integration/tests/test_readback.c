/* test_readback.c — host check of readback_rows_at (threaded and serial) against an
 * independent reference, including chunked (acc_stride, col0) shapes, and that nothing
 * is written outside each row's window. #includes mmfree_lib.c to drive the real code.
 *
 * Build+run:  cd software/integration/tests && make readback
 */
#include "../libmmfree/mmfree_lib.c"

#include <stdio.h>

static int fails;

/* Reference: sign-extend accWidth bits; row i lands at acc[i*acc_stride + col0 + m]. */
static void reference(const uint32_t *src, int32_t *acc, uint32_t b, uint32_t M,
                      uint32_t n_outputs, uint32_t acc_stride, uint32_t col0,
                      uint32_t accWidth) {
    for (uint32_t i = 0; i < b; i++) {
        for (uint32_t m = 0; m < M; m++) {
            const uint32_t raw = src[(size_t)i * n_outputs + m];
            const uint32_t sh  = 32u - accWidth;
            acc[(size_t)i * acc_stride + col0 + m] = (int32_t)(raw << sh) >> sh;
        }
    }
}

/* One shape: compare with the reference and check nothing outside [col0, col0+M) of
 * each row was written. */
static void check(uint32_t b, uint32_t M, uint32_t acc_stride, uint32_t col0,
                  uint32_t accWidth, const char *what) {
    const uint32_t n_outputs = M;
    const size_t   nsrc = (size_t)b * n_outputs;
    const size_t   nacc = (size_t)b * acc_stride;

    uint32_t *src = malloc(nsrc * sizeof(uint32_t));
    int32_t  *got = malloc(nacc * sizeof(int32_t));
    int32_t  *want = malloc(nacc * sizeof(int32_t));
    if (!src || !got || !want) { printf("  FAIL: alloc\n"); fails++; return; }

    /* Dirty high bits, which the sign extension must discard. */
    for (size_t i = 0; i < nsrc; i++) src[i] = (uint32_t)(i * 2654435761u) ^ 0xF0000000u;

    const int32_t canary = (int32_t)0xDEADBEEF;
    for (size_t i = 0; i < nacc; i++) got[i] = want[i] = canary;

    reference(src, want, b, M, n_outputs, acc_stride, col0, accWidth);

    mmfree_lib_t h;
    memset(&h, 0, sizeof(h));
    h.ctx.geom.outLaneBytes = 4;
    h.ctx.geom.accWidth = accWidth;
    h.out.cached = 1;
    h.out.vaddr = (void *)src;

    proj_t pr;
    memset(&pr, 0, sizeof(pr));
    pr.M = M;
    pr.n_outputs = n_outputs;

    readback_rows_at(&h, &pr, got, b, acc_stride, col0);

    size_t bad = 0, spill = 0;
    for (uint32_t i = 0; i < b; i++) {
        for (uint32_t c = 0; c < acc_stride; c++) {
            const size_t k = (size_t)i * acc_stride + c;
            const int inside = (c >= col0 && c < col0 + M);
            if (inside) { if (got[k] != want[k]) bad++; }
            else if (got[k] != canary) spill++;   /* wrote outside its window */
        }
    }
    if (bad || spill)
        printf("  FAIL: %-38s  %zu wrong, %zu outside window (b=%u M=%u stride=%u col0=%u)\n",
               what, bad, spill, b, M, acc_stride, col0);
    fails += (bad || spill) ? 1 : 0;

    free(src); free(got); free(want);
}

int main(void) {
    /* Force the threaded path for every shape. overwrite=0 so `make readback`'s
     * MMFREE_RB_THREADS=1 arm still gets the serial path. */
    setenv("MMFREE_RB_THREADS", "4", 0);
    setenv("MMFREE_RB_MIN_ELEMS", "1", 0);

#ifdef _OPENMP
    printf("test_readback: OpenMP on, rb_threads=%d, min_elems=%zu\n",
           rb_threads(), rb_min_elems());
#else
    printf("test_readback: built WITHOUT OpenMP — the threaded path cannot be exercised.\n");
    printf("  (build with -fopenmp; this run only checks the serial branch)\n");
#endif

    /* Whole-projection case: acc_stride == M, col0 == 0. */
    const uint32_t Ms[] = {1, 7, 512, 1024, 2816, 8192, 32000};
    for (uint32_t b = 1; b <= 8; b++)
        for (size_t i = 0; i < sizeof(Ms) / sizeof(*Ms); i++)
            check(b, Ms[i], Ms[i], 0, 28, "whole projection");

    /* Chunked case: full M as the stride, the chunk's first column as col0. */
    const uint32_t fullM[] = {2816, 32000};
    const uint32_t nchunks[] = {4, 8};
    for (size_t f = 0; f < 2; f++) {
        for (size_t c = 0; c < 2; c++) {
            const uint32_t full = fullM[f], k = nchunks[c];
            const uint32_t per = (full + k - 1u) / k;
            for (uint32_t j = 0; j < k; j++) {
                const uint32_t col0 = j * per;
                if (col0 >= full) break;
                const uint32_t m = (col0 + per <= full) ? per : full - col0;
                for (uint32_t b = 1; b <= 8; b += 3)
                    check(b, m, full, col0, 28, "column-tile chunk");
            }
        }
    }

    /* Other accWidth values, including the no-op 32. */
    for (uint32_t w = 16; w <= 32; w += 4)
        for (uint32_t b = 1; b <= 6; b += 5)
            check(b, 2816, 2816, 0, w, "accWidth variant");

    printf("test_readback: %s (%d failing shape%s)\n", fails ? "FAIL" : "PASS", fails,
           fails == 1 ? "" : "s");
    return fails ? 1 : 0;
}
