/* mmfree_runtime.c — implementation of mmfree_runtime.h. Board addresses and
 * device paths are supplied by the caller. */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#include "mmfree_runtime.h"
#include "mmfree_dma.h"

#ifdef __aarch64__
#include <arm_neon.h>
#endif

/* Fallback geometry seeded by mmfree_open; callers overwrite ctx->geom with the
 * real one (from the MMFREE_* env / preset manifest). */
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

static int read_sysfs_u64(const char *path, uint64_t *out) {
    FILE *f = fopen(path, "r");
    if (!f) return -errno;
    char buf[64] = {0};
    if (!fgets(buf, sizeof(buf), f)) { fclose(f); return -EIO; }
    fclose(f);
    *out = strtoull(buf, NULL, 0);
    return 0;
}

/* Open a u-dma-buf sysfs attribute, trying the "u-dma-buf" class then the legacy
 * "udmabuf" one. Returns an fd or -1. */
static int open_udmabuf_attr(const char *name, const char *attr, int flags) {
    char path[256];
    snprintf(path, sizeof(path), "/sys/class/u-dma-buf/%s/%s", name, attr);
    int fd = open(path, flags);
    if (fd < 0) {
        snprintf(path, sizeof(path), "/sys/class/udmabuf/%s/%s", name, attr);
        fd = open(path, flags);
    }
    return fd;
}

/* Write a decimal value to an open sysfs attribute fd at offset 0. */
static void write_sysfs_fd(int fd, uint64_t val) {
    if (fd < 0) return;
    char buf[32];
    int n = snprintf(buf, sizeof(buf), "%llu", (unsigned long long)val);
    if (n > 0) { ssize_t w = pwrite(fd, buf, (size_t)n, 0); (void)w; }
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

    /* Seed a valid geometry so byte counts are never zero; callers overwrite it. */
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

    /* A reset that never completes means the PL clock is dead or the wrong
     * bitstream is loaded. DMAs 1..N-1 are MM2S-only. */
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

    /* Clear any stale Core IRQ, then arm UIO (uio_pdrv_genirq: write a uint32 1). */
    mmfree_ack_irq(ctx);

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

int mmfree_buf_open(mmfree_buf_t *b, const char *udmabuf_dev, size_t size, int cached) {
    memset(b, 0, sizeof(*b));
    b->fd = -1;
    b->sync_size_fd = b->sync_cpu_fd = b->sync_dev_fd = -1;

    const char *slash = strrchr(udmabuf_dev, '/');
    const char *name  = slash ? slash + 1 : udmabuf_dev;

    char path[256];
    uint64_t pa = 0, sz = 0;

    snprintf(path, sizeof(path), "/sys/class/u-dma-buf/%s/phys_addr", name);
    if (read_sysfs_u64(path, &pa) < 0) {
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

    /* O_SYNC gives a non-cacheable (Device) mapping; without it the mapping is
     * write-back cacheable and the caller must sync manually. */
    int fd = open(udmabuf_dev, cached ? O_RDWR : (O_RDWR | O_SYNC));
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
    b->cached = cached;

    if (cached) {
        /* Fix sync_offset=0 and sync_direction=BIDIRECTIONAL(0) once; per call,
         * set sync_size and write 1 to the trigger. Attr fds stay open for the
         * buffer's life. */
        int off_fd = open_udmabuf_attr(name, "sync_offset", O_WRONLY);
        write_sysfs_fd(off_fd, 0);
        if (off_fd >= 0) close(off_fd);
        int dir_fd = open_udmabuf_attr(name, "sync_direction", O_WRONLY);
        write_sysfs_fd(dir_fd, 0);
        if (dir_fd >= 0) close(dir_fd);
        b->sync_size_fd = open_udmabuf_attr(name, "sync_size", O_WRONLY);
        b->sync_dev_fd  = open_udmabuf_attr(name, "sync_for_device", O_WRONLY);
        b->sync_cpu_fd  = open_udmabuf_attr(name, "sync_for_cpu", O_WRONLY);
        if (b->sync_size_fd < 0 || b->sync_dev_fd < 0 || b->sync_cpu_fd < 0)
            fprintf(stderr, "warning: %s cached but sync attrs missing — "
                            "results may be incoherent\n", name);
    }
    return 0;
}

void mmfree_buf_close(mmfree_buf_t *b) {
    if (b->vaddr) munmap(b->vaddr, b->size);
    if (b->fd >= 0) close(b->fd);
    if (b->sync_size_fd >= 0) close(b->sync_size_fd);
    if (b->sync_cpu_fd  >= 0) close(b->sync_cpu_fd);
    if (b->sync_dev_fd  >= 0) close(b->sync_dev_fd);
    memset(b, 0, sizeof(*b));
    b->fd = -1;
}

/* Set sync_size (cache-line rounded, clamped) and write 1 to `trigger_fd`. */
static void buf_sync(mmfree_buf_t *b, int trigger_fd, size_t nbytes) {
    if (!b->cached || trigger_fd < 0) return;
    size_t n = (nbytes + 63u) & ~(size_t)63u;       /* round up to a cache line */
    if (n == 0 || n > b->size) n = b->size;
    write_sysfs_fd(b->sync_size_fd, n);
    write_sysfs_fd(trigger_fd, 1u);
}

void mmfree_buf_sync_for_device(mmfree_buf_t *b, size_t nbytes) {
    buf_sync(b, b->sync_dev_fd, nbytes);
}

void mmfree_buf_sync_for_cpu(mmfree_buf_t *b, size_t nbytes) {
    buf_sync(b, b->sync_cpu_fd, nbytes);
}

/* ---------- IRQ + instruction issue ---------- */

/* Completion timeout, MMFREE_TIMEOUT_MS (default 5000, 0 = forever). The Core has
 * no watchdog: a PS/PL beat-count disagreement (e.g. geometry vs bitstream
 * mismatch) waits forever with no error status, so this is the only detection. */
static int wait_timeout_ms(void) {
    static int ms = -1;
    if (ms < 0) {
        const char *e = getenv("MMFREE_TIMEOUT_MS");
        ms = e ? atoi(e) : 5000;
        if (ms < 0) ms = 0;
    }
    return ms;
}

/* MMFREE_POLL (default 1): spin on the Core status word instead of taking the UIO
 * IRQ, whose latency dominates the short LOAD/STORE ops. Costs one busy core.
 * MMFREE_POLL=0 selects the IRQ path. */
static int poll_mode(void) {
    static int p = -1;
    if (p < 0) {
        const char *e = getenv("MMFREE_POLL");
        p = (e && atoi(e) == 0) ? 0 : 1;
    }
    return p;
}

/* Spin until irqPending, bounded by MMFREE_TIMEOUT_MS. Watch irqPending, NOT
 * !busy: busy has not asserted yet right after push_instr, so !busy would
 * false-complete. irqPending is cleared by each op's ack. Returns 0 on completion. */
static int mmfree_spin_done(mmfree_ctx_t *ctx) {
    int ms = wait_timeout_ms();
    uint64_t deadline = 0;
    struct timespec ts;
    if (ms > 0) {
        clock_gettime(CLOCK_MONOTONIC, &ts);
        deadline = (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec
                 + (uint64_t)ms * 1000000ull;
    }
    for (uint32_t i = 0;; i++) {
        if (MMFREE_STATUS_IRQ_PEND(mmfree_status(ctx))) return 0;
        /* Check the clock only every 1024 spins; the hot path is one MMIO read. */
        if (ms > 0 && (i & 0x3FFu) == 0x3FFu) {
            clock_gettime(CLOCK_MONOTONIC, &ts);
            uint64_t now = (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
            if (now >= deadline) {
                fprintf(stderr, "timeout: no core completion (poll) within %d ms\n", ms);
                return -ETIMEDOUT;
            }
        }
#if defined(__aarch64__)
        __asm__ __volatile__("yield");
#endif
    }
}

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
    /* One 128-bit store = one AXI beat. */
    uint64x2_t v = vld1q_u64(&inst.u64.lo);
    vst1q_u64((uint64_t *)p, v);
#else
    /* Non-aarch64 fallback: two 64-bit stores, which may become two beats. */
    *(volatile uint64_t *)((char *)p)     = inst.u64.lo;
    *(volatile uint64_t *)((char *)p + 8) = inst.u64.hi;
#endif
}

uint32_t mmfree_wait_done(mmfree_ctx_t *ctx) {
    if (poll_mode()) {
        /* No UIO involvement, so nothing to re-arm; the ack clears the Core IRQ. */
        if (mmfree_spin_done(ctx) < 0) {
            fprintf(stderr, "poll wait failed\n");
            mmfree_dump_state(ctx, "poll wait");
            return 0xFFFFFFFFu;
        }
        uint32_t s = mmfree_status(ctx);
        mmfree_ack_irq(ctx);
        return s;
    }

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
    g->batch = 1u;     /* unbatched default; batched callers set g->batch (<= xDim) */
    g->packed = 0u;    /* 2-bit default; a packedWeights-bitstream caller sets g->packed */

    g->nLanes          = aWidth / 2u;
    g->outLanesPerTile = xDim * g->nLanes;
    g->sAxisBytes      = (xDim * aWidth) / 8u;
    g->accWidth        = log2_up(maxAcc) + aWidth;

    uint32_t w = 8u;                               /* nextPow2(accWidth), min 8 */
    while (w < g->accWidth) w <<= 1;
    g->outLaneWidth = w;
    g->outLaneBytes = w / 8u;

    /* s_axis port split, mirrors CoreConfig: <=128 bits per HP port, up to 4 ports. */
    uint32_t bits = xDim * aWidth;
    if (bits <= 128u) {
        g->numPorts = 1u;
    } else {
        if ((bits % 128u) != 0u || bits / 128u > 4u) return -1;
        g->numPorts = bits / 128u;
    }
    g->portBytes = g->sAxisBytes / g->numPorts;
    g->packed    = 0u;
    return 0;
}

uint32_t mmfree_wt_port_bytes(const mmfree_geom_t *g, uint32_t rows, uint32_t cols)
{
    uint32_t olt = g->outLanesPerTile;
    uint32_t col_tiles = (cols + olt - 1u) / olt;
    if (!g->packed) return rows * col_tiles * g->portBytes;
    /* 1.6-bit: trits 5/byte, padded to a whole wire beat — exactly the beats the
     * gearbox consumes (its flush drops the padding trits). */
    uint64_t trits  = (uint64_t)rows * col_tiles * olt;
    uint64_t raw    = (trits + 4u) / 5u;                       /* ceil(trits/5) */
    uint64_t padded = ((raw + g->sAxisBytes - 1u) / g->sAxisBytes) * g->sAxisBytes;
    return (uint32_t)(padded / g->numPorts);
}

/* ---------- preset manifest cross-check ---------- */

int mmfree_geom_check_manifest(const mmfree_geom_t *g, const char *path) {
    if (!g || !path || !*path) return 0;   /* nothing configured → skip */
    FILE *f = fopen(path, "r");
    if (!f) {
        fprintf(stderr, "warn: MMFREE_MANIFEST=%s not readable (%s) — skipping "
                "geometry cross-check\n", path, strerror(errno));
        return 0;
    }

    struct { const char *key; uint32_t want, got; int seen; } chk[] = {
        { "MMFREE_AWIDTH", g->aWidth,   0, 0 },
        { "MMFREE_XDIM",   g->xDim,     0, 0 },
        { "MMFREE_MAXACC", g->maxAcc,   0, 0 },
        { "MMFREE_MAXN",   g->maxN,     0, 0 },
        { "MMFREE_MAXM",   g->maxM,     0, 0 },
        { "MMFREE_BATCH",  g->batch,    0, 0 },
        { "NUM_DMA",       g->numPorts, 0, 0 },
        { "MMFREE_PACKED_WEIGHTS", g->packed, 0, 0 },
    };
    const size_t nchk = sizeof(chk) / sizeof(chk[0]);
    char preset[64] = "?";

    char line[256];
    while (fgets(line, sizeof(line), f)) {
        if (line[0] == '#') continue;
        char *eq = strchr(line, '=');
        if (!eq) continue;
        *eq = '\0';
        const char *key = line;
        char *val = eq + 1;
        val[strcspn(val, "\r\n")] = '\0';   /* trim EOL */
        if (strcmp(key, "MMFREE_PRESET") == 0) {
            snprintf(preset, sizeof(preset), "%s", val);
            continue;
        }
        for (size_t i = 0; i < nchk; i++)
            if (strcmp(key, chk[i].key) == 0) {
                chk[i].got  = (uint32_t)strtoul(val, NULL, 0);
                chk[i].seen = 1;
            }
    }
    fclose(f);

    int mism = 0;
    for (size_t i = 0; i < nchk; i++)
        if (chk[i].seen && chk[i].got != chk[i].want) {
            fprintf(stderr, "WARN: geometry vs bitstream manifest '%s' (%s): %s "
                    "binary=%u bitstream=%u\n",
                    preset, path, chk[i].key, chk[i].want, chk[i].got);
            mism++;
        }
    if (mism) {
        fprintf(stderr, "WARN: %d geometry field(s) disagree with the loaded "
                "bitstream — the Core may wedge (completion timeout). Set "
                "MMFREE_STRICT=1 to make this fatal.\n", mism);
        const char *s = getenv("MMFREE_STRICT");
        if (s && atoi(s) != 0) return -1;
    }
    return mism;
}

int mmfree_geom_check_env(const mmfree_geom_t *g) {
    return mmfree_geom_check_manifest(g, getenv("MMFREE_MANIFEST"));
}

/* ---------- high-level ops ---------- */

/* Start every MM2S at bufs[i].paddr + off for `bytes_per_port`, push `inst`, wait
 * for completion, then for every MM2S to drain. All ports must move the same byte
 * count: the CoreTop join advances only when every port presents a slice. */
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
    /* One beat per activation; the caller has packed the per-port slices. */
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
    return mmfree_compute_off_b(ctx, bufs, rows, cols, byte_offset, 0);
}

int mmfree_compute_off_b(mmfree_ctx_t *ctx, const mmfree_buf_t *bufs,
                         uint32_t rows, uint32_t cols, uint64_t byte_offset,
                         uint32_t batch)
{
    /* Caller provides the col-tile-major, per-port-sliced layout; `batch` only
     * bounds the drain, never the byte count. */
    uint32_t bytes = mmfree_wt_port_bytes(&ctx->geom, rows, cols);
    return input_op(ctx, bufs, bytes, byte_offset,
                    mmfree_inst_compute_b(bufs[0].paddr + byte_offset, rows, cols,
                                          (uint16_t)batch), "COMPUTE");
}

int mmfree_store(mmfree_ctx_t *ctx, const mmfree_buf_t *buf, uint32_t n_outputs) {
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
