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
#include <poll.h>
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

/* Default geometry seeded into ctx by mmfree_open — must match the loaded
 * bitstream's CoreConfig. Defaults are k26_bench; the Makefile's BENCH_PRESET
 * knob sets -DBENCH_AWIDTH / -DBENCH_XDIM (the same flags bench.c reads). The
 * bench harness overrides ctx->geom at runtime from MMFREE_* env vars, so this
 * is only the fallback when nothing else sets it. */
#ifdef BENCH_AWIDTH
#define MMFREE_DEF_AWIDTH   BENCH_AWIDTH
#else
#define MMFREE_DEF_AWIDTH   16
#endif
#ifdef BENCH_XDIM
#define MMFREE_DEF_XDIM     BENCH_XDIM
#else
#define MMFREE_DEF_XDIM     4
#endif
#define MMFREE_DEF_MAXACC   4096
#define MMFREE_DEF_MAXN     1024
#define MMFREE_DEF_MAXM     1024

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
                const uint64_t *dma_phys, uint32_t num_dma, size_t dma_size,
                const char *uio_dev)
{
    memset(ctx, 0, sizeof(*ctx));
    ctx->uio_fd = -1;

    if (num_dma < 1 || num_dma > MMFREE_MAX_DMA) {
        fprintf(stderr, "mmfree_open: num_dma=%u out of range 1..%d\n",
                num_dma, MMFREE_MAX_DMA);
        return -EINVAL;
    }
    ctx->num_dma = num_dma;

    /* Seed a valid default geometry; the caller (bench) overrides ctx->geom
     * from env before issuing any op. Without this the byte-count math would
     * read zeroes and every transfer would be empty. */
    mmfree_geom_init(&ctx->geom, MMFREE_DEF_AWIDTH, MMFREE_DEF_XDIM,
                     MMFREE_DEF_MAXACC, MMFREE_DEF_MAXN, MMFREE_DEF_MAXM);

    int rc = mmap_phys(core_phys, core_size, &ctx->core_regs);
    if (rc < 0) { fprintf(stderr, "mmap core_phys 0x%lx: %s\n", core_phys, strerror(-rc)); return rc; }
    ctx->core_size = core_size;

    for (uint32_t i = 0; i < num_dma; i++) {
        rc = mmap_phys(dma_phys[i], dma_size, &ctx->dma_regs[i]);
        if (rc < 0) { fprintf(stderr, "mmap dma_phys[%u] 0x%lx: %s\n", i, dma_phys[i], strerror(-rc)); return rc; }
    }
    ctx->dma_size = dma_size;

    ctx->uio_fd = open(uio_dev, O_RDWR);
    if (ctx->uio_fd < 0) {
        rc = -errno;
        fprintf(stderr, "open %s: %s\n", uio_dev, strerror(-rc));
        return rc;
    }

    /* Reset the DMAs so we start from a known state. A reset that never
     * completes means the PL clock is dead or the wrong bitstream is loaded —
     * fail loudly here instead of hanging the first transfer. DMA 0 has both
     * channels; DMAs 1..N-1 are MM2S-only (no S2MM register block). */
    if (mmfree_dma_reset(ctx->dma_regs[0]) < 0) {
        fprintf(stderr, "axi dma 0 stuck in reset — PL not programmed / clock stopped?\n");
        return -EIO;
    }
    for (uint32_t i = 1; i < num_dma; i++) {
        if (mmfree_dma_mm2s_reset(ctx->dma_regs[i]) < 0) {
            fprintf(stderr, "axi dma %u stuck in reset — bitstream built without "
                            "NUM_DMA=%u / wrong MMFREE_DMA%u address?\n", i, num_dma, i);
            return -EIO;
        }
    }

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
    for (uint32_t i = 0; i < MMFREE_MAX_DMA; i++) {
        if (ctx->dma_regs[i]) munmap((void *)ctx->dma_regs[i], ctx->dma_size);
    }
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

/* Completion-wait timeout. The Core FSM has no internal watchdog — a beat-count
 * disagreement between PS and PL (e.g. a bench binary built for the wrong
 * preset) leaves it waiting on s_axis/m_axis forever with no error status, so
 * the only place a hang can be detected is here. Worst legal bench op is the
 * 1024x1024 COMPUTE: ~16M cycles at 100 MHz ≈ 170 ms; default 5 s is >25x
 * margin. Override with MMFREE_TIMEOUT_MS (0 = wait forever). */
static int wait_timeout_ms(void) {
    static int ms = -1;
    if (ms < 0) {
        const char *e = getenv("MMFREE_TIMEOUT_MS");
        ms = e ? atoi(e) : 5000;
        if (ms < 0) ms = 0;
    }
    return ms;
}

/* Dump everything observable about the Core + DMA so a stuck run pinpoints
 * itself: which op, whether the Core is still busy, and whether each DMA
 * channel finished its programmed transfer. */
void mmfree_dump_state(mmfree_ctx_t *ctx, const char *what) {
    uint32_t st = mmfree_status(ctx);

    fprintf(stderr,
        "---- mmfree state dump (%s) ----\n"
        "  core status = 0x%08x  busy=%u last_op=0x%02x err=0x%02x irq_pend=%u\n",
        what,
        st, MMFREE_STATUS_BUSY(st), MMFREE_STATUS_LAST_OP(st),
        MMFREE_STATUS_ERR(st), MMFREE_STATUS_IRQ_PEND(st));

    for (uint32_t i = 0; i < ctx->num_dma; i++) {
        uint32_t sr  = mmfree_dma_rd(ctx->dma_regs[i], AXI_DMA_MM2S_DMASR);
        uint32_t len = mmfree_dma_rd(ctx->dma_regs[i], AXI_DMA_MM2S_LENGTH);
        fprintf(stderr,
            "  mm2s[%u] DMASR = 0x%08x  halted=%u idle=%u err=%u  LENGTH=%u\n",
            i, sr, !!(sr & DMASR_HALTED), !!(sr & DMASR_IDLE),
            !!(sr & DMASR_ANY_ERR), len);
    }
    {
        uint32_t sr  = mmfree_dma_rd(ctx->dma_regs[0], AXI_DMA_S2MM_DMASR);
        uint32_t len = mmfree_dma_rd(ctx->dma_regs[0], AXI_DMA_S2MM_LENGTH);
        fprintf(stderr,
            "  s2mm[0] DMASR = 0x%08x  halted=%u idle=%u err=%u  LENGTH=%u\n",
            sr, !!(sr & DMASR_HALTED), !!(sr & DMASR_IDLE),
            !!(sr & DMASR_ANY_ERR), len);
    }
    fprintf(stderr,
        "  hints: core busy + mm2s idle  -> core expects MORE s_axis beats than\n"
        "         the DMA sent (preset/geometry mismatch: binary vs bitstream?)\n"
        "         core busy + mm2s !idle -> core stopped accepting beats (RTL\n"
        "         stall, or one port of the N-way join starved: check ALL mm2s)\n"
        "         core !busy, no irq_pend -> IRQ delivery problem (UIO/DT overlay)\n"
        "--------------------------------\n");
}

int mmfree_wait_irq(mmfree_ctx_t *ctx) {
    int ms = wait_timeout_ms();
    if (ms > 0) {
        struct pollfd pfd = { .fd = ctx->uio_fd, .events = POLLIN };
        int pr = poll(&pfd, 1, ms);
        if (pr == 0) {
            fprintf(stderr, "timeout: no core irq within %d ms\n", ms);
            return -ETIMEDOUT;
        }
        if (pr < 0) return -errno;
    }
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
    /* Block on UIO (bounded — see wait_timeout_ms). */
    if (mmfree_wait_irq(ctx) < 0) {
        fprintf(stderr, "uio wait failed\n");
        mmfree_dump_state(ctx, "irq wait");
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

/* ---------- geometry ---------- */

static uint32_t log2_up(uint32_t x) {
    uint32_t r = 0;
    while ((1u << r) < x) r++;
    return r;
}

int mmfree_geom_init(mmfree_geom_t *g, uint32_t aWidth, uint32_t xDim,
                     uint32_t maxAcc, uint32_t maxN, uint32_t maxM)
{
    if (!g || aWidth == 0 || xDim == 0 || maxAcc == 0) return -1;
    if (((xDim * aWidth) % 8u) != 0u) return -1;   /* beat must be whole bytes */

    g->aWidth = aWidth; g->xDim = xDim; g->maxAcc = maxAcc;
    g->maxN = maxN; g->maxM = maxM;

    g->nLanes          = aWidth / 2u;
    g->outLanesPerTile = xDim * g->nLanes;
    g->sAxisBytes      = (xDim * aWidth) / 8u;
    g->accWidth        = log2_up(maxAcc) + aWidth;

    uint32_t w = 8u;                               /* nextPow2(accWidth), min 8 */
    while (w < g->accWidth) w <<= 1;
    g->outLaneWidth = w;
    g->outLaneBytes = w / 8u;

    /* s_axis port split — mirrors CoreConfig: one PS HP port carries at most
     * 128 bits, wider streams use N x 128-bit ports. */
    uint32_t bits = xDim * aWidth;
    if (bits <= 128u) {
        g->numPorts = 1u;
    } else {
        if ((bits % 128u) != 0u || bits / 128u > 4u) return -1;
        g->numPorts = bits / 128u;
    }
    g->portBytes = g->sAxisBytes / g->numPorts;
    return 0;
}

/* ---------- high-level ops ---------- */

/* Kick all N MM2S engines with `bytes_per_port` from bufs[i] (starting at
 * byte `off` into each port buffer), push the instruction, wait for the Core
 * IRQ, then wait for every MM2S to drain. `off` lets a projection resident at
 * an offset in a shared weight udmabuf be streamed without copying — the Core
 * addresses the stream via the DMA source register, not the instruction ptr.
 * Every port pushes the SAME byte count — the CoreTop join only advances a
 * beat when all ports present a slice, so unequal counts deadlock. */
static int input_op(mmfree_ctx_t *ctx, const mmfree_buf_t *bufs,
                    uint32_t bytes_per_port, uint64_t off, mmfree_instr_t inst,
                    const char *what)
{
    for (uint32_t i = 0; i < ctx->num_dma; i++) {
        if (mmfree_dma_mm2s_start(ctx->dma_regs[i], bufs[i].paddr + off, bytes_per_port) < 0) {
            fprintf(stderr, "%s mm2s[%u] start failed\n", what, i);
            return -1;
        }
    }

    mmfree_push_instr(ctx, inst);

    uint32_t st = mmfree_wait_done(ctx);
    if (MMFREE_STATUS_ERR(st) != MMFREE_ERR_NONE) {
        fprintf(stderr, "%s err=0x%x status=0x%x\n", what, MMFREE_STATUS_ERR(st), st);
        return -1;
    }
    for (uint32_t i = 0; i < ctx->num_dma; i++) {
        int rc = mmfree_dma_mm2s_wait(ctx->dma_regs[i]);
        if (rc < 0) {
            fprintf(stderr, "%s dma %u %s\n", what, i, rc == -2 ? "timeout" : "error");
            mmfree_dump_state(ctx, what);
            return -1;
        }
    }
    return 0;
}

int mmfree_load(mmfree_ctx_t *ctx, const mmfree_buf_t *bufs, uint32_t n_activations) {
    /* One s_axis beat per activation; only the low aWidth bits of the wide
     * beat are meaningful, so port 0 carries the values and ports 1..N-1
     * stream zeros. Caller packs one beat-slice-sized entry per activation
     * per port (the bench harness does this packing). */
    uint32_t bytes = n_activations * ctx->geom.portBytes;
    return input_op(ctx, bufs, bytes, 0,
                    mmfree_inst_load(bufs[0].paddr, n_activations), "LOAD");
}

int mmfree_compute(mmfree_ctx_t *ctx, const mmfree_buf_t *bufs,
                   uint32_t rows, uint32_t cols)
{
    return mmfree_compute_off(ctx, bufs, rows, cols, 0);
}

int mmfree_compute_off(mmfree_ctx_t *ctx, const mmfree_buf_t *bufs,
                       uint32_t rows, uint32_t cols, uint64_t byte_offset)
{
    /* One beat per col-tile-cycle: each wide beat carries outLanesPerTile
     * ternary weights, port p holding lanes [p*64, (p+1)*64). Total beats =
     * rows * ceil(cols / outLanesPerTile). Caller is responsible for the
     * col-tile-major / per-port-slice layout (see bench.c). `byte_offset` is
     * the per-port start of this projection's slice within a resident weight
     * buffer; 0 reproduces the legacy single-projection behavior. */
    uint32_t lanes_per_tile = ctx->geom.outLanesPerTile;
    uint32_t col_tiles = (cols + lanes_per_tile - 1) / lanes_per_tile;
    uint32_t total_beats = rows * col_tiles;
    uint32_t bytes = total_beats * ctx->geom.portBytes;
    return input_op(ctx, bufs, bytes, byte_offset,
                    mmfree_inst_compute(bufs[0].paddr + byte_offset, rows, cols), "COMPUTE");
}

int mmfree_store(mmfree_ctx_t *ctx, const mmfree_buf_t *buf, uint32_t n_outputs) {
    /* n_outputs is the number of lanes to drain (one per output column). At
     * outBeatLanes=1, each lane is one beat, padded to outLaneWidth bits.
     * Output stays single-stream on DMA 0's S2MM. */
    uint32_t bytes = n_outputs * ctx->geom.outLaneBytes;

    if (mmfree_dma_s2mm_start(ctx->dma_regs[0], buf->paddr, bytes) < 0) return -1;

    mmfree_push_instr(ctx, mmfree_inst_store(buf->paddr, n_outputs));

    uint32_t st = mmfree_wait_done(ctx);
    if (MMFREE_STATUS_ERR(st) != MMFREE_ERR_NONE) {
        fprintf(stderr, "STORE err=0x%x status=0x%x\n", MMFREE_STATUS_ERR(st), st);
        return -1;
    }
    int rc = mmfree_dma_s2mm_wait(ctx->dma_regs[0]);
    if (rc < 0) {
        fprintf(stderr, "STORE dma %s\n", rc == -2 ? "timeout" : "error");
        mmfree_dump_state(ctx, "STORE s2mm wait");
        return -1;
    }
    return 0;
}
