/* test_split_chunks.c — host check of the column-tile split plan (no hardware). A bad
 * boundary is silent on the board: the DMA streams from a wrong offset. #includes
 * mmfree_lib.c to drive the real chunk_offset/chunk_boundaries/chunk_view.
 *
 * For every real projection shape of 370M / 1.3B / 2.7B, in BOTH weight encodings, assert:
 *   1. chunks tile [0, num_col_tiles) exactly — contiguous, no gap, no overlap
 *   2. their column ranges tile [0, M) exactly
 *   3. per-port byte ranges are contiguous and sum to the projection's whole slice — the
 *      invariant that makes splitting free (no re-packing)
 *   4. every chunk's byte offset is port-aligned (a DMA start requirement)
 *   5. a 1.6-bit blob never gets an illegal boundary (N%5-hostile shapes fall back to fewer
 *      chunks instead of streaming from a fractional-byte offset)
 *
 * Build+run:  cd software/integration/tests && make split
 */
#include "../libmmfree/mmfree_lib.c"

#include <stdio.h>

static int fails;

#define CHECK(cond, ...)                                                     \
    do {                                                                     \
        if (!(cond)) { printf("  FAIL: "); printf(__VA_ARGS__);              \
                       printf("\n"); fails++; }                              \
    } while (0)

/* Mirror of fpga_backend.cpp split_chunks() — keep in step by hand. */
static uint32_t want_for(uint32_t M) {
    if (M >= 16384) return 8;
    if (M >= 8192)  return 4;
    if (M >= 4096)  return 2;
    return 1;
}

/* mmfree_split_plan reads only ctx.geom and projs[]. */
static void check_shape(mmfree_geom_t *g, const char *name, uint32_t N, uint32_t M) {
    mmfree_lib_t h;
    memset(&h, 0, sizeof(h));
    h.ctx.geom = *g;
    h.nproj = 1;
    h.max_proj = 1;
    proj_t pr;
    const uint32_t olt = g->outLanesPerTile;
    const uint32_t tiles = (M + olt - 1) / olt;
    pr.byte_offset = 0;
    pr.N = N;
    pr.M = M;
    pr.n_outputs = tiles * olt;
    h.projs = &pr;

    mmfree_split_chunk_t plan[64];
    int k = mmfree_split_plan(&h, 0, want_for(M), plan, 64);
    CHECK(k >= 1, "%s: split_plan returned %d", name, k);
    if (k < 1) return;

    const uint32_t whole = mmfree_wt_port_bytes(g, N, M);
    uint32_t tile_cursor = 0, col_cursor = 0;
    uint64_t byte_cursor = 0, byte_sum = 0;

    for (int j = 0; j < k; j++) {
        /* 1 + 2: tiles and columns advance contiguously from 0. */
        CHECK(plan[j].first_tile == tile_cursor, "%s: chunk %d at tile %u, expected %u",
              name, j, plan[j].first_tile, tile_cursor);
        CHECK(plan[j].first_col == col_cursor, "%s: chunk %d at col %u, expected %u",
              name, j, plan[j].first_col, col_cursor);
        /* 3: byte ranges contiguous. */
        CHECK(plan[j].byte_offset == byte_cursor, "%s: chunk %d byte offset %llu, expected %llu",
              name, j, (unsigned long long)plan[j].byte_offset,
              (unsigned long long)byte_cursor);
        /* 4: a per-port DMA start must sit on a port-slice boundary. */
        CHECK(plan[j].byte_offset % g->portBytes == 0,
              "%s: chunk %d offset %llu not a multiple of portBytes=%u",
              name, j, (unsigned long long)plan[j].byte_offset, g->portBytes);

        tile_cursor += plan[j].tiles;
        col_cursor  += plan[j].M;
        byte_cursor  = plan[j].byte_offset + plan[j].blob_bytes;
        byte_sum    += plan[j].blob_bytes;
    }

    CHECK(tile_cursor == tiles, "%s: chunks covered %u tiles, projection has %u",
          name, tile_cursor, tiles);
    CHECK(col_cursor == M, "%s: chunks covered %u cols, projection has %u",
          name, col_cursor, M);
    /* 3 (sum): 2-bit is exact; 1.6-bit may add per-chunk tail padding, so bound it. */
    if (!g->packed) {
        CHECK(byte_sum == whole, "%s: chunk bytes %llu != whole %u",
              name, (unsigned long long)byte_sum, whole);
    } else {
        CHECK(byte_sum >= whole, "%s: chunk bytes %llu < whole %u (weights lost!)",
              name, (unsigned long long)byte_sum, whole);
        CHECK(byte_sum <= (uint64_t)whole + (uint64_t)k * g->sAxisBytes,
              "%s: chunk bytes %llu overshoots whole %u by > %d beats",
              name, (unsigned long long)byte_sum, whole, k);
    }
    printf("  ok  %-14s %-5s N=%-5u M=%-6u tiles=%-4u k=%d  bytes/port=%u\n",
           name, g->packed ? "1p6" : "2bit", N, M, tiles, k, whole);
}

static void run_model(const char *label, uint32_t hidden, uint32_t inter, uint32_t vocab,
                      uint32_t maxN, uint32_t maxM, int packed) {
    mmfree_geom_t g;
    if (mmfree_geom_init(&g, 16, 32, maxN, maxN, maxM) < 0) {
        printf("  FAIL: geom_init(%s)\n", label);
        fails++;
        return;
    }
    g.packed = packed ? 1u : 0u;
    printf("%s (%s):\n", label, packed ? "1.6-bit" : "2-bit");
    check_shape(&g, "i/f/g/o_proj", hidden, hidden);
    check_shape(&g, "gate_proj",    hidden, 2u * inter);
    check_shape(&g, "down_proj",    inter,  hidden);
    check_shape(&g, "lm_head",      hidden, vocab);
}

/* Chunked readback_rows_at must scatter each chunk's lanes into the right columns of the
 * caller's [b][M] accumulator: compare against one whole-projection readback. */
static void check_scatter(void) {
    mmfree_geom_t g;
    if (mmfree_geom_init(&g, 16, 32, 8192, 8192, 32000) < 0) { fails++; return; }
    const uint32_t olt = g.outLanesPerTile;   /* 256 */
    const uint32_t b = 4, tiles = 6, M = tiles * olt;

    mmfree_lib_t h;
    memset(&h, 0, sizeof(h));
    h.ctx.geom = g;
    /* Cached + 4-byte lanes + accWidth<=32 selects the fast path used on the board. */
    static uint32_t fake_out[4 * 6 * 256];
    h.out.vaddr = fake_out;
    h.out.size = sizeof(fake_out);
    h.out.cached = 1;

    int32_t got[4 * 6 * 256], want[4 * 6 * 256];
    memset(got, 0xAA, sizeof(got));

    /* Reference: one whole-projection readback. OUT holds b rows of n_outputs=M lanes. */
    for (uint32_t i = 0; i < b; i++)
        for (uint32_t m = 0; m < M; m++)
            fake_out[i * M + m] = (uint32_t)(int32_t)(-(int)(i * 100000 + m));
    proj_t whole = { .byte_offset = 0, .N = 2560, .M = M, .n_outputs = M };
    readback_rows_at(&h, &whole, want, b, M, 0u);

    /* Split: 3 chunks of 2 tiles, each with OUT laid out as the engine would drain it. */
    const uint32_t ct = 2, span = ct * olt;
    for (uint32_t j = 0; j < tiles / ct; j++) {
        uint32_t col0 = j * span;
        for (uint32_t i = 0; i < b; i++)
            for (uint32_t l = 0; l < span; l++)
                fake_out[i * span + l] =
                    (uint32_t)(int32_t)(-(int)(i * 100000 + col0 + l));
        proj_t chunk = { .byte_offset = 0, .N = 2560, .M = span, .n_outputs = span };
        readback_rows_at(&h, &chunk, got, b, M, col0);
    }

    uint32_t bad = 0;
    for (uint32_t i = 0; i < b * M; i++) if (got[i] != want[i]) bad++;
    CHECK(bad == 0, "scatter: %u/%u elements differ between split and whole readback",
          bad, b * M);
    if (!bad) printf("  ok  scatter        b=%u M=%u in %u chunks -> matches whole readback\n",
                     b, M, tiles / ct);
}

/* The per-chunk callback drives dequant, so every column of [0,M) must be reported
 * exactly once (twice = double-scaled, never = raw int32). */
static uint8_t seen_cols[32000];
static uint32_t seen_M;
static void count_cb(void *ctx, uint32_t col0, uint32_t ncols) {
    (void)ctx;
    for (uint32_t c = col0; c < col0 + ncols && c < seen_M; c++) seen_cols[c]++;
}

static void check_cb_coverage(mmfree_geom_t *g, const char *name, uint32_t N, uint32_t M) {
    mmfree_lib_t h;
    memset(&h, 0, sizeof(h));
    h.ctx.geom = *g;
    h.nproj = 1; h.max_proj = 1;
    const uint32_t olt = g->outLanesPerTile;
    proj_t pr = { .byte_offset = 0, .N = N, .M = M,
                  .n_outputs = ((M + olt - 1) / olt) * olt };
    h.projs = &pr;

    mmfree_split_chunk_t plan[64];
    int k = mmfree_split_plan(&h, 0, want_for(M), plan, 64);
    if (k < 1) { CHECK(0, "%s: split_plan failed", name); return; }

    memset(seen_cols, 0, sizeof(seen_cols));
    seen_M = M;
    /* The driver fires the callback once per chunk. */
    for (int j = 0; j < k; j++) count_cb(NULL, plan[j].first_col, plan[j].M);

    uint32_t missed = 0, dup = 0;
    for (uint32_t c = 0; c < M; c++) {
        if (seen_cols[c] == 0) missed++;
        else if (seen_cols[c] > 1) dup++;
    }
    CHECK(missed == 0, "%s: %u columns never reported (would stay un-dequantized)", name, missed);
    CHECK(dup == 0, "%s: %u columns reported twice (would be double-scaled)", name, dup);
    if (!missed && !dup)
        printf("  ok  %-14s %-5s M=%-6u covered exactly once by %d callback(s)\n",
               name, g->packed ? "1p6" : "2bit", M, k);
}

static void run_cb_model(const char *label, uint32_t hidden, uint32_t inter, uint32_t vocab,
                         uint32_t maxN, uint32_t maxM, int packed) {
    mmfree_geom_t g;
    if (mmfree_geom_init(&g, 16, 32, maxN, maxN, maxM) < 0) { fails++; return; }
    g.packed = packed ? 1u : 0u;
    printf("%s (%s):\n", label, packed ? "1.6-bit" : "2-bit");
    check_cb_coverage(&g, "i/f/g/o_proj", hidden, hidden);
    check_cb_coverage(&g, "gate_proj",    hidden, 2u * inter);
    check_cb_coverage(&g, "down_proj",    inter,  hidden);
    check_cb_coverage(&g, "lm_head",      hidden, vocab);
}

int main(void) {
    for (int packed = 0; packed <= 1; packed++) {
        run_model("370M", 1024, 2816, 32000, 4096, 32000, packed);
        run_model("1.3B", 2048, 5632, 32000, 8192, 32000, packed);
        run_model("2.7B", 2560, 6912, 32000, 8192, 32000, packed);
    }
    printf("\nper-chunk callback coverage (dequant correctness):\n");
    for (int packed = 0; packed <= 1; packed++) {
        run_cb_model("370M", 1024, 2816, 32000, 4096, 32000, packed);
        run_cb_model("1.3B", 2048, 5632, 32000, 8192, 32000, packed);
        run_cb_model("2.7B", 2560, 6912, 32000, 8192, 32000, packed);
    }
    printf("\nreadback scatter:\n");
    check_scatter();
    printf("\n%s (%d failure%s)\n", fails ? "FAILED" : "PASSED", fails,
           fails == 1 ? "" : "s");
    return fails ? 1 : 0;
}
