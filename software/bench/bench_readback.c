/* bench_readback.c — is the engine readback loop worth threading?
 *
 * readback is the one phase that still scales ~linearly with batch (13.1 us/row on 370M,
 * 21.1 on 2.7B) and the only one still running on a single A53 while the other three cores
 * sit idle. This isolates the question "does that loop scale with threads?" from the rest
 * of the runner: same loop, same shapes, no bitstream, no overlay, no sudo.
 *
 * The loop under test is readback_rows_at()'s fast path (mmfree_lib.c:327): per row, read
 * u32 lanes out of the drained output buffer, sign-extend accWidth bits with a shift pair,
 * write i32 into the caller's accumulator. Contiguous both sides.
 *
 * Two things make a naive microbenchmark lie about this, and both are handled here:
 *
 *   COLD SOURCE. The real source is a cacheable udmabuf that was just invalidated, so every
 *   read misses to DRAM. Reading one warm buffer in a loop would measure L2 and report a
 *   speedup that does not exist in the runner. So we stream through a pool much larger than
 *   the 1 MB L2, a different slice per iteration.
 *
 *   PARALLEL REGION OVERHEAD. At b=6 the whole call is ~83 us; an OpenMP region entry of
 *   10-20 us would eat the win. The timer therefore brackets the WHOLE call including the
 *   region, which is what the runner would pay — not just the inner loop.
 *
 * Build (on the board):
 *   gcc -O3 -mcpu=cortex-a53 -fopenmp -std=c11 -o bench_readback bench_readback.c
 * Run:
 *   OMP_PROC_BIND=close OMP_WAIT_POLICY=active ./bench_readback
 */
#define _GNU_SOURCE
#include <omp.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define ACC_WIDTH 28u                 /* geom.accWidth on every shipped preset */
#define POOL_BYTES (96u << 20)        /* >> 1 MB L2, so each iteration reads cold lines */
#define COL_BLOCK 512u                /* column tile per thread when b < nthreads */

static double now_s(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + 1e-9 * (double)ts.tv_nsec;
}

/* The loop as it exists today: one thread, row-major. */
static void readback_serial(const uint32_t *src, int32_t *acc, unsigned b, unsigned M,
                            unsigned n_outputs) {
    const unsigned sh = 32u - ACC_WIDTH;
    for (unsigned i = 0; i < b; i++) {
        const uint32_t *row = src + (size_t)i * n_outputs;
        int32_t *ar = acc + (size_t)i * M;
        for (unsigned m = 0; m < M; m++) ar[m] = (int32_t)(row[m] << sh) >> sh;
    }
}

/* Threaded form. Parallelising over rows alone would leave b=1 serial and would not divide
 * evenly at b=6 on 4 cores (the same ceil(b/4) wave problem the host vector ops have), so
 * the iteration space is (row x column block) collapsed — every thread gets work at any b. */
static void readback_threaded(const uint32_t *src, int32_t *acc, unsigned b, unsigned M,
                              unsigned n_outputs) {
    const unsigned sh = 32u - ACC_WIDTH;
    const unsigned nblk = (M + COL_BLOCK - 1u) / COL_BLOCK;
#pragma omp parallel for collapse(2) schedule(static)
    for (unsigned i = 0; i < b; i++) {
        for (unsigned cb = 0; cb < nblk; cb++) {
            const unsigned m0 = cb * COL_BLOCK;
            const unsigned m1 = (m0 + COL_BLOCK < M) ? m0 + COL_BLOCK : M;
            const uint32_t *row = src + (size_t)i * n_outputs;
            int32_t *ar = acc + (size_t)i * M;
            for (unsigned m = m0; m < m1; m++) ar[m] = (int32_t)(row[m] << sh) >> sh;
        }
    }
}

int main(int argc, char **argv) {
    const unsigned Ms[]  = {1024, 2816, 8192, 32000};   /* hidden / intermediate / maxN / lm_head */
    const unsigned bs[]  = {1, 4, 6, 8};
    const int threads[]  = {1, 2, 4};
    const int iters = (argc > 1) ? atoi(argv[1]) : 200;

    uint32_t *pool = aligned_alloc(64, POOL_BYTES);
    int32_t  *acc  = aligned_alloc(64, (size_t)32000 * 8 * sizeof(int32_t));
    if (!pool || !acc) { fprintf(stderr, "alloc failed\n"); return 1; }
    for (size_t i = 0; i < POOL_BYTES / 4; i++) pool[i] = (uint32_t)(i * 2654435761u);
    memset(acc, 0, (size_t)32000 * 8 * sizeof(int32_t));

    printf("# bench_readback: accWidth=%u, pool=%u MiB, iters=%d, max_threads=%d\n",
           ACC_WIDTH, POOL_BYTES >> 20, iters, omp_get_max_threads());
    printf("# us/call is the WHOLE call incl. the OpenMP region; GB/s counts 4 B read + 4 B written\n");
    printf("%7s %4s %8s %10s %9s %9s %9s\n", "M", "b", "threads", "us/call", "GB/s", "vs 1T", "us/row");

    for (size_t mi = 0; mi < sizeof(Ms) / sizeof(*Ms); mi++) {
        const unsigned M = Ms[mi];
        for (size_t bi = 0; bi < sizeof(bs) / sizeof(*bs); bi++) {
            const unsigned b = bs[bi];
            const unsigned n_outputs = M;                  /* drained lanes per row */
            const size_t   span = (size_t)b * n_outputs;   /* u32 per call */
            if (span * 4 > POOL_BYTES / 4) continue;
            const size_t slices = (POOL_BYTES / 4) / span; /* distinct cold slices available */
            double base = 0.0;

            for (size_t ti = 0; ti < sizeof(threads) / sizeof(*threads); ti++) {
                const int nt = threads[ti];
                omp_set_num_threads(nt);

                /* Warm the thread pool so region-entry cost is steady-state, not first-touch. */
                for (int w = 0; w < 3; w++)
                    (nt == 1) ? readback_serial(pool, acc, b, M, n_outputs)
                              : readback_threaded(pool, acc, b, M, n_outputs);

                const double t0 = now_s();
                for (int it = 0; it < iters; it++) {
                    const uint32_t *src = pool + (size_t)(it % slices) * span;
                    if (nt == 1) readback_serial(src, acc, b, M, n_outputs);
                    else         readback_threaded(src, acc, b, M, n_outputs);
                }
                const double us = (now_s() - t0) * 1e6 / iters;
                const double gbs = (double)span * 8.0 / (us * 1e-6) / 1e9;
                if (nt == 1) base = us;
                printf("%7u %4u %8d %10.2f %9.2f %9.2fx %9.2f\n",
                       M, b, nt, us, gbs, base / us, us / b);
            }
        }
    }
    free(pool);
    free(acc);
    return 0;
}
