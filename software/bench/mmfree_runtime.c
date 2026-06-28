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
#include <time.h>
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

/* Open a u-dma-buf sysfs attribute for `name` (e.g. "udmabuf-act0"), trying the
 * current "u-dma-buf" class then the legacy "udmabuf" name. Returns an fd or -1. */
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

/* Write a decimal value to an already-open sysfs attribute fd (rewinds to 0). */
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

int mmfree_buf_open(mmfree_buf_t *b, const char *udmabuf_dev, size_t size, int cached) {
    memset(b, 0, sizeof(*b));
    b->fd = -1;
    b->sync_size_fd = b->sync_cpu_fd = b->sync_dev_fd = -1;

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

    /* O_SYNC forces a non-cacheable (Device) mapping on ikwzm u-dma-buf; drop it
     * for a write-back cacheable mapping (then the caller must sync manually). */
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
        /* Hold the sync attrs open for the buffer's life. sync_offset stays 0;
         * sync_size is set per call. sync_for_device/_for_cpu carry an explicit
         * dma_data_direction (TO_DEVICE=1 clean-only, FROM_DEVICE=2 invalidate-
         * only) so each sync does the minimum cache work. */
        /* Fix offset=0 and direction=BIDIRECTIONAL(0) once; both are the minimum
         * safe configuration (for_device cleans, for_cpu invalidates regardless).
         * Per call we set sync_size and write exactly 1 to the trigger — the
         * plain form that uses these attrs, not the combined-value form. */
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

/* Set sync_size to the touched region (cache-line rounded, clamped) and write 1
 * to `trigger_fd` — the plain trigger form, which syncs [sync_offset, sync_size)
 * in the configured sync_direction (offset 0, BIDIRECTIONAL set at open). */
static void buf_sync(mmfree_buf_t *b, int trigger_fd, size_t nbytes) {
    if (!b->cached || trigger_fd < 0) return;
    size_t n = (nbytes + 63u) & ~(size_t)63u;       /* round up to a cache line */
    if (n == 0 || n > b->size) n = b->size;
    write_sysfs_fd(b->sync_size_fd, n);
    write_sysfs_fd(trigger_fd, 1u);
}

void mmfree_buf_sync_for_device(mmfree_buf_t *b, size_t nbytes) {
    buf_sync(b, b->sync_dev_fd, nbytes);   /* clean: CPU writes -> DDR */
}

void mmfree_buf_sync_for_cpu(mmfree_buf_t *b, size_t nbytes) {
    buf_sync(b, b->sync_cpu_fd, nbytes);   /* invalidate: DDR -> CPU reads */
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

/* MMFREE_POLL=1 trades the UIO IRQ round-trip (poll()+read()+ack+re-arm, tens of
 * us of kernel/interrupt latency) for a userspace spin on the Core status word.
 * Worth it for the short LOAD/STORE ops that dominate the decode loop; costs one
 * busy core for the op's duration. Default 0 = IRQ path. */
static int poll_mode(void) {
    static int p = -1;
    if (p < 0) {
        const char *e = getenv("MMFREE_POLL");
        p = (e && atoi(e) != 0) ? 1 : 0;
    }
    return p;
}

/* Spin on the Core status register until completion (irqPending set), bounded by
 * the same MMFREE_TIMEOUT_MS as the IRQ path. We watch irqPending, NOT busy:
 * busy hasn't asserted yet in the launch-latency window right after push_instr,
 * so polling !busy would false-complete immediately. irqPending is cleared by
 * our ack each op, so it is reliably 0 until this op finishes. The status word is
 * one 32-bit read, so the snapshot is consistent. Returns 0 on completion. */
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
        /* Check the (vDSO, syscall-free) clock only every 1024 spins so the
         * hot path stays a single MMIO read. */
        if (ms > 0 && (i & 0x3FFu) == 0x3FFu) {
            clock_gettime(CLOCK_MONOTONIC, &ts);
            uint64_t now = (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
            if (now >= deadline) {
                fprintf(stderr, "timeout: no core completion (poll) within %d ms\n", ms);
                return -ETIMEDOUT;
            }
        }
#if defined(__aarch64__)
        __asm__ __volatile__("yield");   /* SEV-friendly spin hint */
#endif
    }
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
    if (poll_mode()) {
        /* Userspace spin — no UIO involvement, so nothing to re-arm. The Core
         * IRQ line still asserts and is cleared by the ack below; UIO masks its
         * one delivered IRQ at open and we simply never service it. */
        if (mmfree_spin_done(ctx) < 0) {
            fprintf(stderr, "poll wait failed\n");
            mmfree_dump_state(ctx, "poll wait");
            return 0xFFFFFFFFu;
        }
        uint32_t s = mmfree_status(ctx);
        mmfree_ack_irq(ctx);
        return s;
    }

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
    g->batch = 1u;     /* unbatched default; batched callers set g->batch (<= xDim) */

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
