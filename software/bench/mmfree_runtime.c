/*
 * mmfree_runtime.c — Implementation of mmfree_runtime.h.
 *
 * Userspace ties together /dev/mem mmap of the Core + AXI DMA register windows,
 * udmabuf-backed contiguous DMA buffers, and UIO IRQ wait. All board-specific
 * physical addresses are passed in by the caller (typically from a tiny config
 * struct in the bench harness).
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "mmfree_runtime.h"
#include "mmfree_dma.h"

#ifdef __aarch64__
#include <arm_neon.h>
#endif

#define PAGE_SIZE 0x1000u

/* ---------- helpers ---------- */

static int mmap_phys(uint64_t phys, size_t size, volatile void **out) {
    int fd = open("/dev/mem", O_RDWR | O_SYNC);
    if (fd < 0) return -errno;
    void *p = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, (off_t)phys);
    close(fd);
    if (p == MAP_FAILED) return -errno;
    *out = p;
    return 0;
}

/* Read a sysfs decimal value into uint64_t. Used to look up udmabuf physaddr/size. */
static int read_sysfs_u64(const char *path, uint64_t *out) {
    FILE *f = fopen(path, "r");
    if (!f) return -errno;
    char buf[64] = {0};
    if (!fgets(buf, sizeof(buf), f)) { fclose(f); return -EIO; }
    fclose(f);
    *out = strtoull(buf, NULL, 0);
    return 0;
}

/* ---------- setup / teardown ---------- */

int mmfree_open(mmfree_ctx_t *ctx,
                uint64_t core_phys, size_t core_size,
                uint64_t dma_phys,  size_t dma_size,
                const char *uio_dev)
{
    memset(ctx, 0, sizeof(*ctx));
    ctx->uio_fd = -1;

    int rc = mmap_phys(core_phys, core_size, &ctx->core_regs);
    if (rc < 0) { fprintf(stderr, "mmap core_phys 0x%lx: %s\n", core_phys, strerror(-rc)); return rc; }
    ctx->core_size = core_size;

    rc = mmap_phys(dma_phys, dma_size, &ctx->dma_regs);
    if (rc < 0) { fprintf(stderr, "mmap dma_phys 0x%lx: %s\n", dma_phys, strerror(-rc)); return rc; }
    ctx->dma_size = dma_size;

    ctx->uio_fd = open(uio_dev, O_RDWR);
    if (ctx->uio_fd < 0) {
        rc = -errno;
        fprintf(stderr, "open %s: %s\n", uio_dev, strerror(-rc));
        return rc;
    }

    /* Reset the DMA so we start from a known state. */
    mmfree_dma_reset(ctx->dma_regs);

    /* Drain any stale Core IRQ from a previous run. */
    mmfree_ack_irq(ctx);

    /* Enable UIO IRQ. uio_pdrv_genirq requires a one-uint32_t write to (re)arm. */
    uint32_t enable = 1;
    if (write(ctx->uio_fd, &enable, sizeof(enable)) != (ssize_t)sizeof(enable)) {
        fprintf(stderr, "warn: failed to arm uio irq\n");
    }
    return 0;
}

void mmfree_close(mmfree_ctx_t *ctx) {
    if (ctx->uio_fd >= 0) close(ctx->uio_fd);
    if (ctx->core_regs) munmap((void *)ctx->core_regs, ctx->core_size);
    if (ctx->dma_regs)  munmap((void *)ctx->dma_regs,  ctx->dma_size);
    memset(ctx, 0, sizeof(*ctx));
    ctx->uio_fd = -1;
}

int mmfree_buf_open(mmfree_buf_t *b, const char *udmabuf_dev, size_t size) {
    memset(b, 0, sizeof(*b));
    b->fd = -1;

    /* Resolve the sysfs entry for udmabufN to read its phys_addr + actual size. */
    const char *slash = strrchr(udmabuf_dev, '/');
    const char *name  = slash ? slash + 1 : udmabuf_dev;

    char path[256];
    uint64_t pa = 0, sz = 0;

    snprintf(path, sizeof(path), "/sys/class/u-dma-buf/%s/phys_addr", name);
    if (read_sysfs_u64(path, &pa) < 0) {
        /* Fall back to the older udmabuf naming convention. */
        snprintf(path, sizeof(path), "/sys/class/udmabuf/%s/phys_addr", name);
        if (read_sysfs_u64(path, &pa) < 0) {
            fprintf(stderr, "could not read phys_addr for %s\n", name);
            return -ENOENT;
        }
    }
    snprintf(path, sizeof(path), "/sys/class/u-dma-buf/%s/size", name);
    if (read_sysfs_u64(path, &sz) < 0) {
        snprintf(path, sizeof(path), "/sys/class/udmabuf/%s/size", name);
        if (read_sysfs_u64(path, &sz) < 0) sz = size;
    }
    if (size == 0 || size > sz) size = sz;

    int fd = open(udmabuf_dev, O_RDWR | O_SYNC);
    if (fd < 0) {
        fprintf(stderr, "open %s: %s\n", udmabuf_dev, strerror(errno));
        return -errno;
    }
    void *p = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (p == MAP_FAILED) { close(fd); return -errno; }

    b->fd = fd;
    b->vaddr = p;
    b->paddr = pa;
    b->size  = size;
    return 0;
}

void mmfree_buf_close(mmfree_buf_t *b) {
    if (b->vaddr) munmap(b->vaddr, b->size);
    if (b->fd >= 0) close(b->fd);
    memset(b, 0, sizeof(*b));
    b->fd = -1;
}

/* ---------- IRQ + instruction issue ---------- */

int mmfree_wait_irq(mmfree_ctx_t *ctx) {
    uint32_t cnt;
    ssize_t n = read(ctx->uio_fd, &cnt, sizeof(cnt));
    if (n != (ssize_t)sizeof(cnt)) return -1;
    return 0;
}

void mmfree_push_instr(mmfree_ctx_t *ctx, mmfree_instr_t inst) {
    volatile void *p = (char *)ctx->core_regs + MMFREE_REG_INSTR;
#ifdef __aarch64__
    /* Single 128-bit AXI beat via NEON. */
    uint64x2_t v = vld1q_u64(&inst.u64.lo);
    vst1q_u64((uint64_t *)p, v);
#else
    /* Fallback: write the two halves. Fine in simulation; on a real PS this
     * may split into two beats unless the bus collapses them. */
    *(volatile uint64_t *)((char *)p)     = inst.u64.lo;
    *(volatile uint64_t *)((char *)p + 8) = inst.u64.hi;
#endif
}

uint32_t mmfree_wait_done(mmfree_ctx_t *ctx) {
    /* Block on UIO. */
    if (mmfree_wait_irq(ctx) < 0) {
        fprintf(stderr, "uio read failed\n");
        return 0xFFFFFFFFu;
    }
    uint32_t s = mmfree_status(ctx);
    mmfree_ack_irq(ctx);

    /* Re-arm UIO. */
    uint32_t enable = 1;
    if (write(ctx->uio_fd, &enable, sizeof(enable)) != (ssize_t)sizeof(enable)) {
        /* non-fatal */
    }
    return s;
}

/* ---------- high-level ops ---------- */

/* K26_Bench-specific widths. For other configs these need to be passed in or
 * derived from a CoreConfig descriptor. */
#define MMFREE_AWIDTH       16
#define MMFREE_BATCH        1
#define MMFREE_XDIM         4
#define MMFREE_NLANES       (MMFREE_AWIDTH / 2)                            /* 8 */
#define MMFREE_OUT_LANES_PER_TILE  (MMFREE_XDIM * MMFREE_NLANES)            /* 32 */
#define MMFREE_OUT_LANE_BYTES      4   /* outBeatLanes=1, outAccWidth=28 padded to 32 */
#define MMFREE_S_AXIS_BYTES        ((MMFREE_XDIM * MMFREE_AWIDTH) / 8)      /* 8  → 64-bit MM2S */

int mmfree_load(mmfree_ctx_t *ctx, const mmfree_buf_t *buf, uint32_t n_activations) {
    /* AXI DMA at MMFREE_S_AXIS_BYTES (= 8 bytes for K26_Bench at aWidth=16)
     * transfers one beat per activation; only the low batchSize*aWidth bits
     * (= 16 bits) are meaningful. Caller MUST pack activations as one
     * beat-sized entry each (the bench harness does this packing). */
    uint32_t bytes = n_activations * MMFREE_S_AXIS_BYTES;

    if (mmfree_dma_mm2s_start(ctx->dma_regs, buf->paddr, bytes) < 0) return -1;

    mmfree_push_instr(ctx, mmfree_inst_load(buf->paddr, n_activations));

    uint32_t st = mmfree_wait_done(ctx);
    if (MMFREE_STATUS_ERR(st) != MMFREE_ERR_NONE) {
        fprintf(stderr, "LOAD err=0x%x status=0x%x\n", MMFREE_STATUS_ERR(st), st);
        return -1;
    }
    if (mmfree_dma_mm2s_wait(ctx->dma_regs) < 0) {
        fprintf(stderr, "LOAD dma error\n");
        return -1;
    }
    return 0;
}

int mmfree_compute(mmfree_ctx_t *ctx, const mmfree_buf_t *buf,
                   uint32_t rows, uint32_t cols)
{
    /* One beat per col-tile-cycle: each beat carries xDim*nLanes = 32 ternary
     * weights (64 bits at aWidth=16). Total beats = rows * (cols / outLanesPerTile).
     * Caller is responsible for col-tile-major layout (see bench.c). */
    uint32_t col_tiles = (cols + MMFREE_OUT_LANES_PER_TILE - 1) / MMFREE_OUT_LANES_PER_TILE;
    uint32_t total_beats = rows * col_tiles;
    uint32_t bytes = total_beats * MMFREE_S_AXIS_BYTES;

    if (mmfree_dma_mm2s_start(ctx->dma_regs, buf->paddr, bytes) < 0) return -1;

    mmfree_push_instr(ctx, mmfree_inst_compute(buf->paddr, rows, cols));

    uint32_t st = mmfree_wait_done(ctx);
    if (MMFREE_STATUS_ERR(st) != MMFREE_ERR_NONE) {
        fprintf(stderr, "COMPUTE err=0x%x status=0x%x\n", MMFREE_STATUS_ERR(st), st);
        return -1;
    }
    if (mmfree_dma_mm2s_wait(ctx->dma_regs) < 0) {
        fprintf(stderr, "COMPUTE dma error\n");
        return -1;
    }
    return 0;
}

int mmfree_store(mmfree_ctx_t *ctx, const mmfree_buf_t *buf, uint32_t n_outputs) {
    /* n_outputs is the number of lanes to drain (one per output column). At
     * outBeatLanes=1, each lane is one beat, padded to outLaneWidth = 32 bits. */
    uint32_t bytes = n_outputs * MMFREE_OUT_LANE_BYTES;

    if (mmfree_dma_s2mm_start(ctx->dma_regs, buf->paddr, bytes) < 0) return -1;

    mmfree_push_instr(ctx, mmfree_inst_store(buf->paddr, n_outputs));

    uint32_t st = mmfree_wait_done(ctx);
    if (MMFREE_STATUS_ERR(st) != MMFREE_ERR_NONE) {
        fprintf(stderr, "STORE err=0x%x status=0x%x\n", MMFREE_STATUS_ERR(st), st);
        return -1;
    }
    if (mmfree_dma_s2mm_wait(ctx->dma_regs) < 0) {
        fprintf(stderr, "STORE dma error\n");
        return -1;
    }
    return 0;
}
