# Repo Consolidation Plan (future work)

**Status:** planned, NOT started · **Created:** 2026-06-24 · execute *after* the batching
optimizations land.

## Problem

Running anything takes many scattered commands across mill/sbt, `scripts/build_all.sh`,
`software/Makefile`, `fpga_runner/Makefile`, `mmfree_pack.cli`, pytest, and board deploy
scripts — each with its own env vars and positional args. The deeper issue: **a preset's
geometry is hand-duplicated in 5+ places** and must be kept in sync manually. Every drift is a
silent footgun (we hit three in one session: an x86 bench binary run on the aarch64 board, a
stale 128 KiB `out` overlay, and `MMFREE_BATCH` having to manually match the bitstream).

Geometry duplication today:
- `src/main/scala/control/CoreConfig.scala` presets — the **actual truth**.
- `scripts/build_all.sh` case tables — XDIM/AWIDTH, NUM_DMA/MM2S_WIDTH, PL_CLK, UDMABUF_*_SZ, BATCH.
- `software/Makefile` `ifeq BENCH_PRESET` — `-DBENCH_AWIDTH/XDIM/MAXN/MAXM/SIGNED/CLK`.
- runtime `MMFREE_*` env at run time — must match the bitstream or the core wedges.
- `gen_overlay.tcl` / `mmfree_bridge/ctypes_lib.py` — udmabuf sizes / cfg defaults.

## Plan (prioritized)

### P0 — Single preset manifest (the linchpin)
Extend the Chisel `EmitCore` main to emit, alongside the SV, a **`preset.env`/`preset.json`**
with *every* derived field (aWidth, xDim, batch, numPorts, MM2S_WIDTH, PL_CLK, accWidth,
outBeatLanes, udmabuf sizes). Everything then **consumes it instead of re-deriving**:
- `build_all.sh` sources it → delete the case tables.
- C build reads it → delete the `ifeq -DBENCH_*` blocks (bench geometry comes from the manifest, not compile flags).
- `gen_overlay.tcl` reads the udmabuf sizes.
- runtime loads it at startup and **asserts it matches the loaded bitstream** → retires the wedge-on-mismatch class.
One source of truth (CoreConfig) → one generated manifest → all consumers. This alone is ~80%
of the relief and kills the footgun class.

### P0 — One task runner
A top-level `./mmfree` (thin bash) or root `Makefile` with `help`, ~8 verbs, each reading the
manifest + a board config:
```
mmfree sim [spec]                              # mill test
mmfree build <preset>                          # build_all.sh, manifest-driven
mmfree pack <blob>                             # mmfree_pack.cli
mmfree deploy <preset>                         # scp transfer/<preset>/ + reload overlay + verify udmabuf sizes
mmfree bench <preset> [--batch N] [--shapes ..]# build native + run, args from board.conf
mmfree gate / mmfree run                        # fpga_runner --backend both / fpga
```
No retyped addresses, no "which cd am I in".

### P1 — Board config + arch safety
`board.conf` (CSR/stream base, `/dev/uio4`, udmabuf paths) read by bench/runner so the 6
positional args are never retyped (or auto-discover from sysfs). `mmfree deploy`/`bench`
always **build C natively on the board** and **verify udmabuf sizes after overlay load** —
directly prevents the x86-binary and stale-overlay footguns.

### P2 — Cleanups
- Drop one of mill/sbt (keep mill; sbt only if CI needs it).
- Optionally unify the C harnesses (`bench` + `smoke` + `fpga-cli`, all on
  `mmfree_runtime`/`libmmfree`) into one binary with subcommands, built once. (Biggest single
  chunk — could be a P3.)

## Open decisions
- Task-runner tech: `./mmfree` bash vs root `Makefile` vs `just` (lean bash/Makefile — no new deps).
- Manifest format: flat `preset.env` (trivial to `source`/`#include`-style consume in shell+C)
  vs JSON (nicer for the Python side). Leaning `preset.env`, JSON only if Python needs it.
- How far to unify the C binaries (real win, largest effort — likely defer).
