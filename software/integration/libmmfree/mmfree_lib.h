/*
 * mmfree_lib.h — high-level BitLinear backend over the mmfree Core (Phase C).
 *
 * Wraps mmfree_runtime so a PyTorch bridge (Phase D, via ctypes) can run the
 * MMfreeLM-370M projections on the engine:
 *
 *   1. mmfree_lib_open(cfg)                 open HW + allocate RESIDENT udmabufs
 *   2. mmfree_lib_load_weights_file(p,path) copy each per-port packed blob in ONCE
 *   3. id = mmfree_register(off, N, M)      one per projection (from the manifest)
 *   4. mmfree_bitlinear(id, x_int16, acc)   LOAD_ACT -> COMPUTE_MM@off -> STORE_OUT
 *
 * Weights are loaded once and never re-copied; a COMPUTE just points the weight
 * DMA at the projection's resident byte offset (mmfree_compute_off). The library
 * does NOT parse the manifest or know about scales — the Python side reads the
 * JSON manifest, registers offsets, and applies the y = acc/(a_scale*s) dequant.
 *
 * Activations are signed int16 (the k26_mmfree370m_a16 engine); `acc` is the raw
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

/* Run one BitLinear projection. `x` holds N signed int16 activations; `acc`
 * receives M signed int32 dot products (Σ_n x[n]*w_t[n,m]). Returns 0 on
 * success, negative on error/timeout (state dumped to stderr by the runtime). */
int mmfree_bitlinear(mmfree_lib_t *h, int proj_id,
                     const int16_t *x, int32_t *acc);

/* Number of s_axis ports the configured geometry uses (== required num_dma). */
uint32_t mmfree_lib_num_ports(uint32_t aWidth, uint32_t xDim);

/* sizeof(mmfree_lib_cfg_t) — ctypes bindings assert their struct matches this,
 * catching layout/ABI drift between the header and the Python mirror. */
size_t mmfree_lib_cfg_sizeof(void);

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif /* MMFREE_LIB_H_ */
