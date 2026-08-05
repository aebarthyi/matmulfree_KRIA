/*
 * mmfree_lib.h — high-level BitLinear backend over the mmfree Core (Phase C).
 *
 * Wraps mmfree_runtime so a PyTorch bridge (Phase D, via ctypes) can run the
 * MMfreeLM-370M projections on the engine:
 *
 *   1. mmfree_lib_open(cfg)                 open HW + allocate RESIDENT udmabufs
 *   2. mmfree_lib_load_weights_file(p,path) copy each per-port packed blob in ONCE
 *   3. id = mmfree_register(off, N, M)      one per projection (from the manifest)
 *   4. mmfree_bitlinear(id, x_int32, acc)   LOAD_ACT -> COMPUTE_MM@off -> STORE_OUT
 *
 * Weights are loaded once and never re-copied; a COMPUTE just points the weight
 * DMA at the projection's resident byte offset (mmfree_compute_off). The library
 * does NOT parse the manifest or know about scales — the Python side reads the
 * JSON manifest, registers offsets, and applies the y = acc/(a_scale*s) dequant.
 *
 * Activations are passed in as int32 and saturated to the engine's signed int16 LOAD
 * slot during the pack (the producer's quantizer emits int32; narrowing in a separate
 * pass cost a full extra traversal of b*N). `acc` is the raw
 * int32 dot-product Σ_n x_int[n]*w_t[n,m], sign-expanded from the engine's
 * accWidth-bit accumulator. NOT thread-safe: one in-flight op per handle.
 */

#ifndef MMFREE_LIB_H_
#define MMFREE_LIB_H_

#include <stdint.h>
#include <stddef.h>

#include "mmfree_runtime.h"   /* MMFREE_MAX_DMA, mmfree_ctx_t */

#ifdef __cplusplus
extern "C" {
#endif

typedef struct mmfree_lib mmfree_lib_t;   /* opaque handle */

/* Open-time configuration. Physical addresses + device paths are board config,
 * passed in so one build targets any address map (mirrors bench.c's CLI args).
 * Sizes are validated against the real udmabuf node sizes at open. */
typedef struct mmfree_lib_cfg {
    /* geometry (mirror CoreConfig K26_MMFree370M_A16: 16, 32, 4096, 4096, 32000) */
    uint32_t aWidth, xDim, maxAcc, maxN, maxM;
    uint32_t batch;                      /* CoreConfig.batchSize (yDim); 0/1 = unbatched. Must be <= xDim. */
    uint32_t packed;                     /* 1 => 1.6-bit weight stream (packedWeights bitstream + 1.6-bit blob). 0 = 2-bit. */

    /* Core + DMA register windows */
    uint64_t core_phys;
    size_t   core_size;
    uint64_t dma_phys[MMFREE_MAX_DMA];   /* [0] full MM2S+S2MM; 1..N-1 MM2S-only */
    uint32_t num_dma;                    /* must equal derived numPorts */
    size_t   dma_size;
    const char *uio_dev;                 /* /dev/uioN bound to Core IRQ */

    /* udmabuf device paths (one per port for wt/act; single out on port 0) */
    const char *wt_dev[MMFREE_MAX_DMA];  /* resident packed weights, per port */
    const char *act_dev[MMFREE_MAX_DMA]; /* activations, per port */
    const char *out_dev;                 /* output accumulators (S2MM, port 0) */

    /* sizing (caller derives from the packer manifest + geometry) */
    size_t   weight_bytes_per_port;      /* total resident weights / port */
    size_t   act_bytes_per_port;         /* >= maxN * portBytes */
    size_t   out_bytes;                  /* >= max n_outputs * outLaneBytes */
    uint32_t max_proj;                   /* projection-table capacity (e.g. 128) */
} mmfree_lib_cfg_t;

/* Open hardware and map all resident buffers. Activation buffers for ports
 * 1..N-1 are zeroed here (their slice is always zero); port 0 is rewritten per
 * call. Returns a handle or NULL on failure (diagnostic on stderr). */
mmfree_lib_t *mmfree_lib_open(const mmfree_lib_cfg_t *cfg);

void mmfree_lib_close(mmfree_lib_t *h);

/* Copy a packed per-port weight blob into the resident weight udmabuf for
 * `port`, starting at byte 0. Reads the file and stores it via a volatile word
 * loop (glibc memset/memcpy can SIGBUS on Device-memory udmabufs). Returns 0 on
 * success, negative on error. Call once per port after open, before any op. */
int mmfree_lib_load_weights_file(mmfree_lib_t *h, uint32_t port, const char *path);

/* Same, from an in-memory buffer (e.g. bytes handed over from Python). */
int mmfree_lib_load_weights(mmfree_lib_t *h, uint32_t port,
                            const void *blob, size_t nbytes);

/* Register a projection resident at per-port `byte_offset`, inner dim N
 * (activations / rows) and output dim M (cols). Returns a proj_id >= 0 to pass
 * to mmfree_bitlinear, or negative on error (table full / out of range). */
int mmfree_register(mmfree_lib_t *h, uint64_t byte_offset, uint32_t N, uint32_t M);

/* Transpose b*N row-major int32 activations into the engine's int16 LOAD beat layout:
 * beat n occupies `stride` int16 slots, carrying the b values for inner index n (one slot
 * per PE row, saturated to int16) with slots [b, stride) zeroed. This is the hot inner
 * loop of every op — it runs once per projection per token. Exposed because it is pure
 * (dst is a plain buffer, no hardware) and therefore host-testable: see
 * tests/test_pack_act.c. Callers should use mmfree_bitlinear* / mmfree_seq_pack. */
void mmfree_pack_act_rows_i16(int16_t *dst, const int32_t *x,
                              uint32_t N, uint32_t b, uint32_t stride);

/* Run one BitLinear projection. `x` holds N int32 activations, saturated to int16 as
 * they are packed into the LOAD beats; `acc`
 * receives M signed int32 dot products (Σ_n x[n]*w_t[n,m]). Returns 0 on
 * success, negative on error/timeout (state dumped to stderr by the runtime). */
int mmfree_bitlinear(mmfree_lib_t *h, int proj_id,
                     const int32_t *x, int32_t *acc);

/* Batched BitLinear: run `b` activation vectors through ONE weight stream (the
 * engine is weight-broadcast + activation-stationary, so the B PE rows share the
 * streamed weights — amortizing the DDR weight bandwidth that bounds decode).
 *   x:   b*N int32, row-major (vector i at x[i*N + n]); saturated to int16 on pack
 *   acc: b*M int32, row-major (vector i at acc[i*M + m])
 * `b` must be in 1..geom.batch (the bitstream's batchSize); if b < geom.batch the
 * unused PE rows are zero-padded and their outputs ignored. mmfree_bitlinear is
 * the b==1 case. Returns 0 on success, negative on error/timeout. */
int mmfree_bitlinear_batch(mmfree_lib_t *h, int proj_id,
                           const int32_t *x, int32_t *acc, uint32_t b);

/* As mmfree_bitlinear_batch, but runs the projection as up to `want_chunks` COMPUTEs over
 * contiguous slices of its output column tiles, sharing ONE activation pack + LOAD. Because
 * COMPUTE never touches OUT, chunk j-1's sign-expand runs on the CPU while the engine
 * computes chunk j — hiding the readback that is otherwise dead time (~28% of engine time
 * on the big projections). Same results as mmfree_bitlinear_batch, bit for bit.
 *
 * `want_chunks` is a REQUEST, not a promise: the weight blob's encoding constrains where a
 * chunk may start (a 1.6-bit blob only permits boundaries whose trit offset divides by 5
 * and lands on a wire beat), so the implementation halves it until every boundary is legal
 * and falls back to the plain blocking path at 1. Pass 0 or 1 for no splitting.
 *
 * Chunking is a TRADE: each chunk pays a COMPUTE issue + STORE round-trip, so the win peaks
 * where chunk COMPUTE time ~= chunk readback time and reverses if you over-split. Tune with
 * MMFREE_SPLIT_CHUNKS (see fpga_backend.cpp), which also selects per projection size. */
int mmfree_bitlinear_batch_split(mmfree_lib_t *h, int proj_id, const int32_t *x,
                                 int32_t *acc, uint32_t b, uint32_t want_chunks);

/* Called once per chunk, with the output columns [col0, col0+ncols) of `acc` that have just
 * been read back. Every invocation except the last runs INSIDE the next chunk's COMPUTE
 * window, so the work it does is free up to the length of that window — this is how
 * downstream per-column CPU work (dequant, and later the elementwise ops) gets hidden
 * behind the engine instead of running after it. Rows are b, stride is the projection's M.
 * It must touch only those columns, and must not call back into the library. */
typedef void (*mmfree_chunk_cb_t)(void *ctx, uint32_t col0, uint32_t ncols);

/* mmfree_bitlinear_batch_split with a per-chunk completion callback (NULL = the plain
 * split). The callback is the point of splitting beyond readback: readback alone only fills
 * part of the COMPUTE window, and whatever else the caller can express per-column-range
 * rides along for free in the remainder. */
int mmfree_bitlinear_batch_split_cb(mmfree_lib_t *h, int proj_id, const int32_t *x,
                                    int32_t *acc, uint32_t b, uint32_t want_chunks,
                                    mmfree_chunk_cb_t cb, void *cb_ctx);

/* One chunk of a split projection, as mmfree_bitlinear_batch_split would run it. */
typedef struct mmfree_split_chunk {
    uint32_t first_tile;    /* first output column tile */
    uint32_t tiles;         /* column tiles in this chunk */
    uint32_t first_col;     /* first output column = first_tile * outLanesPerTile */
    uint32_t M;             /* columns this chunk asks the engine for (last may be short) */
    uint64_t byte_offset;   /* per-port weight offset (absolute, includes the projection's) */
    uint32_t blob_bytes;    /* per-port weight bytes this chunk streams */
} mmfree_split_chunk_t;

/* Report the split plan for `proj_id` at `want_chunks` WITHOUT running anything: the
 * boundaries actually chosen after the blob-alignment fallback. Writes at most `max` chunks
 * to `out` and returns the count (>=1), or negative on error. Use it to check a plan tiles
 * the projection exactly (contiguous byte ranges summing to the whole slice) and for
 * diagnostics — the chunk count is data-dependent, so it is worth being able to see it. */
int mmfree_split_plan(const mmfree_lib_t *h, int proj_id, uint32_t want_chunks,
                      mmfree_split_chunk_t *out, uint32_t max);

/* ---- pipelined cluster primitives -----------------------------------------
 * mmfree_bitlinear_batch, split into non-blocking issue + blocking wait so a
 * caller can overlap CPU work (quant+pack the next projection, sign-expand the
 * previous) with the engine's COMPUTE. Engine ordering is strictly one-op at a
 * time (the Core has a 1-deep instruction buffer + single irqPending). ACT and
 * OUT are single-region: pack the next activation only AFTER mmfree_seq_load of
 * the current has drained ACT into on-chip BRAM, and read OUT back BEFORE the
 * next mmfree_seq_store_issue overwrites it. Intended call order per projection:
 *   pack -> load -> compute_issue -> [CPU overlap] -> compute_wait ->
 *   store_issue -> store_wait -> readback.
 * All return 0 / negative (errno-style); b is 1..geom.batch. */
int  mmfree_seq_pack(mmfree_lib_t *h, const int32_t *x, uint32_t N, uint32_t b);
int  mmfree_seq_load(mmfree_lib_t *h, uint32_t N);
int  mmfree_seq_compute_issue(mmfree_lib_t *h, int proj_id, uint32_t b);
int  mmfree_seq_compute_wait(mmfree_lib_t *h);
int  mmfree_seq_store_issue(mmfree_lib_t *h, int proj_id, uint32_t b);
int  mmfree_seq_store_wait(mmfree_lib_t *h, int proj_id, uint32_t b);
void mmfree_seq_readback(mmfree_lib_t *h, int proj_id, int32_t *acc, uint32_t b);

/* Per-phase timing accumulated across every mmfree_bitlinear call, in
 * nanoseconds (CLOCK_MONOTONIC). The five phases are the serialized cost of one
 * projection: CPU-side activation packing into Device memory, then the three
 * blocking IRQ round-trips (LOAD_ACT / COMPUTE_MM / STORE_OUT), then reading the
 * M output lanes back. Use to see where the per-call ~600us actually goes. */
typedef struct mmfree_lib_stats {
    uint64_t calls;        /* mmfree_bitlinear invocations measured */
    uint64_t pack_ns;      /* pack activations into port-0 udmabuf (incl. act sync) */
    uint64_t act_sync_ns;  /*   ...of which: sync_for_device(act) clean (subset of pack) */
    uint64_t load_ns;      /* mmfree_load   + wait_done (IRQ) */
    uint64_t compute_ns;   /* mmfree_compute_off + wait_done (IRQ) */
    uint64_t store_ns;     /* mmfree_store  + wait_done (IRQ) + s2mm_wait (incl. out sync) */
    uint64_t out_sync_ns;  /*   ...of which: sync_for_cpu(out) invalidate (subset of store) */
    uint64_t readback_ns;  /* sign-expand M lanes out of the udmabuf — the part actually PAID
                            * for in wall time. With column-tile splitting the overlapped
                            * readbacks run inside a COMPUTE window and cost nothing, so they
                            * are excluded here and counted in readback_hidden_ns instead;
                            * pack+load+compute+store+readback therefore still sums to the
                            * measured wall time on both the split and un-split paths. */
    uint64_t readback_hidden_ns; /* CPU readback time that ran under a COMPUTE and so cost no
                            * wall time (0 without splitting). NOT part of the sum above —
                            * it is the "work you got for free", the tuning signal for how
                            * many chunks to split into. */
} mmfree_lib_stats_t;

/* Copy the accumulated phase timers into *out (no-op if either is NULL). */
void mmfree_lib_get_stats(const mmfree_lib_t *h, mmfree_lib_stats_t *out);

/* Zero the phase timers (e.g. to exclude warmup before a timed run). */
void mmfree_lib_reset_stats(mmfree_lib_t *h);

/* Number of s_axis ports the configured geometry uses (== required num_dma). */
uint32_t mmfree_lib_num_ports(uint32_t aWidth, uint32_t xDim);

/* sizeof(mmfree_lib_cfg_t) — ctypes bindings assert their struct matches this,
 * catching layout/ABI drift between the header and the Python mirror. */
size_t mmfree_lib_cfg_sizeof(void);

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif /* MMFREE_LIB_H_ */
