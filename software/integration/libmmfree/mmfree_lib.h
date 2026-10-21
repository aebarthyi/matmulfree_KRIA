/*
 * mmfree_lib.h — BitLinear backend over the mmfree Core, used by the C++ fpga_runner.
 *
 *   1. mmfree_lib_open(cfg)                 open HW + map RESIDENT udmabufs
 *   2. mmfree_lib_load_weights_file(p,path) copy each per-port packed blob in ONCE
 *   3. id = mmfree_register(off, N, M)      one per projection (from the manifest)
 *   4. mmfree_bitlinear(id, x_int16, acc)   LOAD_ACT -> COMPUTE_MM@off -> STORE_OUT
 *
 * A COMPUTE points the weight DMA at the projection's resident offset; weights are
 * never re-copied. The library does not parse the manifest or apply scales.
 * Activations are signed int16; `acc` is the int32 sum_n x[n]*w_t[n,m],
 * sign-extended from accWidth bits. NOT thread-safe: one in-flight op per handle.
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

/* Open-time configuration. Sizes are validated against the real udmabuf nodes. */
typedef struct mmfree_lib_cfg {
    /* geometry (mirrors CoreConfig) */
    uint32_t aWidth, xDim, maxAcc, maxN, maxM;
    uint32_t batch;                      /* CoreConfig.batchSize; 0/1 = unbatched; <= xDim */
    uint32_t packed;                     /* 1 = 1.6-bit weights (bitstream and blob); 0 = 2-bit */

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
    size_t   out_bytes;                  /* >= batch * max n_outputs * outLaneBytes */
    uint32_t max_proj;                   /* projection-table capacity (e.g. 128) */
} mmfree_lib_cfg_t;

/* Open hardware and map all resident buffers (activation buffers zeroed). Returns
 * a handle or NULL on failure (diagnostic on stderr). */
mmfree_lib_t *mmfree_lib_open(const mmfree_lib_cfg_t *cfg);

void mmfree_lib_close(mmfree_lib_t *h);

/* Copy a per-port weight blob file into `port`'s resident weight buffer from byte
 * 0 (volatile word stores — glibc memcpy can SIGBUS on Device memory). Call once
 * per port after open. Returns 0 or negative errno. */
int mmfree_lib_load_weights_file(mmfree_lib_t *h, uint32_t port, const char *path);

/* Register a projection at per-port `byte_offset` with inner dim N and output dim
 * M. Returns a proj_id >= 0, or negative on error. */
int mmfree_register(mmfree_lib_t *h, uint64_t byte_offset, uint32_t N, uint32_t M);

/* Run one projection: N int16 activations in, M int32 accumulators out.
 * Returns 0, or negative on error/timeout. */
int mmfree_bitlinear(mmfree_lib_t *h, int proj_id,
                     const int16_t *x, int32_t *acc);

/* Run `b` activation rows (1..batchSize) through ONE weight stream.
 * x: b*N int16 row-major; acc: b*M int32 row-major. */
int mmfree_bitlinear_batch(mmfree_lib_t *h, int proj_id,
                           const int16_t *x, int32_t *acc, uint32_t b);

/* As mmfree_bitlinear_batch, but as up to `want_chunks` COMPUTEs over contiguous
 * output column-tile ranges sharing ONE pack + LOAD; chunk j-1's readback runs on
 * the CPU while the engine computes chunk j. Bit-identical results. `want_chunks` is
 * a request: boundaries must be legal DMA starts in the blob encoding, so fewer
 * chunks may run (0/1 = no split). */
int mmfree_bitlinear_batch_split(mmfree_lib_t *h, int proj_id, const int16_t *x,
                                 int32_t *acc, uint32_t b, uint32_t want_chunks);

/* Called once per chunk with the just-read-back columns [col0, col0+ncols) of `acc`
 * (b rows, stride M); all but the last call run inside the next chunk's COMPUTE.
 * It must touch only those columns and must not call back into the library. */
typedef void (*mmfree_chunk_cb_t)(void *ctx, uint32_t col0, uint32_t ncols);

/* mmfree_bitlinear_batch_split with a per-chunk callback (NULL = plain split). */
int mmfree_bitlinear_batch_split_cb(mmfree_lib_t *h, int proj_id, const int16_t *x,
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

/* The split plan for `proj_id` at `want_chunks`, without running it. Writes at most
 * `max` chunks to `out`; returns the count (>=1) or negative on error. */
int mmfree_split_plan(const mmfree_lib_t *h, int proj_id, uint32_t want_chunks,
                      mmfree_split_chunk_t *out, uint32_t max);

/* ---- pipelined primitives ----
 * mmfree_bitlinear_batch split into issue + wait so CPU work can overlap COMPUTE.
 * One engine op at a time (single irqPending). ACT and OUT are single regions: pack
 * the next activations only after mmfree_seq_load has drained ACT, and read OUT
 * back before the next store_issue overwrites it. Order per projection:
 *   pack -> load -> compute_issue -> [CPU overlap] -> compute_wait ->
 *   store_issue -> store_wait -> readback.
 * All return 0 / negative errno; b is 1..batchSize. */
int  mmfree_seq_pack(mmfree_lib_t *h, const int16_t *x, uint32_t N, uint32_t b);
int  mmfree_seq_load(mmfree_lib_t *h, uint32_t N);
int  mmfree_seq_compute_issue(mmfree_lib_t *h, int proj_id, uint32_t b);
int  mmfree_seq_compute_wait(mmfree_lib_t *h);
int  mmfree_seq_store_issue(mmfree_lib_t *h, int proj_id, uint32_t b);
int  mmfree_seq_store_wait(mmfree_lib_t *h, int proj_id, uint32_t b);
void mmfree_seq_readback(mmfree_lib_t *h, int proj_id, int32_t *acc, uint32_t b);

/* Per-phase time (ns, CLOCK_MONOTONIC) summed over mmfree_bitlinear* calls (the
 * seq primitives are not timed). pack+load+compute+store+readback = wall time. */
typedef struct mmfree_lib_stats {
    uint64_t calls;        /* mmfree_bitlinear invocations measured */
    uint64_t pack_ns;      /* pack activations into port-0 udmabuf (incl. act sync) */
    uint64_t act_sync_ns;  /*   ...of which: sync_for_device(act) clean (subset of pack) */
    uint64_t load_ns;      /* mmfree_load   + wait_done (IRQ) */
    uint64_t compute_ns;   /* mmfree_compute_off + wait_done (IRQ) */
    uint64_t store_ns;     /* mmfree_store  + wait_done (IRQ) + s2mm_wait (incl. out sync) */
    uint64_t out_sync_ns;  /*   ...of which: sync_for_cpu(out) invalidate (subset of store) */
    uint64_t readback_ns;  /* sign-expand that was NOT hidden under a COMPUTE */
    uint64_t readback_hidden_ns; /* readback (+ chunk callbacks) run under a COMPUTE;
                                  * not part of the wall-time sum */
} mmfree_lib_stats_t;

/* Copy the accumulated phase timers into *out (no-op if either is NULL). */
void mmfree_lib_get_stats(const mmfree_lib_t *h, mmfree_lib_stats_t *out);

/* Zero the phase timers. */
void mmfree_lib_reset_stats(mmfree_lib_t *h);

/* Number of s_axis ports the configured geometry uses (== required num_dma). */
uint32_t mmfree_lib_num_ports(uint32_t aWidth, uint32_t xDim);

/* sizeof(mmfree_lib_cfg_t), for FFI callers to check struct layout. */
size_t mmfree_lib_cfg_sizeof(void);

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif /* MMFREE_LIB_H_ */
