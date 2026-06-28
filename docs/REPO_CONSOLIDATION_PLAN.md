# Repo Consolidation Plan

**Status:** EXECUTED 2026-06-27 (P0 + P1 + sbt drop). One P2 item slipped — see the
Status section at the bottom. **Created:** 2026-06-24.

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

## Decisions (locked 2026-06-26, execute next session)
- **Manifest format:** flat `preset.env` (KEY=VAL — `source` in bash, simple `#include`-style
  read in C) **+ a parallel `preset.json`** for the Python consumer (`ctypes_lib.py`). One
  emitter in `EmitCore`, two output files.
- **Task runner:** root **`Makefile`** with ~8 verbs. Caveat: arg-heavy verbs (`bench`,
  `run`) take `PRESET=`/`BATCH=`/`ARGS="…"` variables + an `ARGS` passthrough, NOT positional
  goals (Make goal parsing mangles `--shapes …`).
- **Scope:** full P0+P1+P2 in one pass, including the C-binary unification and dropping sbt.

## Execution order (runbook)
0. **Pre-flight:** commit/stash the dirty tree first (submodule exp + i/f/g cluster work;
   parent `PIPELINING_PLAN.md`, `fpga_backend.*`, `mmfree_lib.*`). Cleanup on a clean tree.
1. **P0 manifest emit:** extend `EmitCore` to write `preset.env` + `preset.json` (all
   `CoreConfig` derived fields) into the target dir alongside the SV.
2. **P0 consumers** (one at a time, keep old path as fallback until each is verified):
   `build_all.sh` sources `preset.env` → delete case tables; `software/Makefile` reads it →
   delete `ifeq -DBENCH_*`; `gen_overlay.tcl` reads udmabuf sizes; `ctypes_lib.py` reads
   `preset.json`; runtime loads it at startup and **warns** on bitstream mismatch (flip to
   hard-assert only after a known-good run proves it).
3. **P0 task runner:** root `Makefile`, ~8 verbs (sim/build/pack/deploy/bench/gate/run/help),
   each reading the manifest + board config.
4. **P1:** `board.conf` for the 6 positional args; `deploy`/`bench` build C natively on the
   board and verify udmabuf sizes post-overlay-load.
5. **P2 (last — slip first if the day runs out):** drop sbt (keep mill); unify
   `bench`+`smoke`+`fpga-cli` (all on `mmfree_runtime`/`libmmfree`) into one subcommand binary.

## Status — executed 2026-06-27

**The linchpin (P0): one source of truth → one manifest → all consumers.**
- `control.EmitCore` now emits `generated/<preset>/preset.env` + `preset.json` with every
  derived `CoreConfig` field (geometry, ports, widths, PL clock, udmabuf sizes). Added a
  `plClkMhz` field to `CoreConfig` (the only deployment-policy value that wasn't derivable);
  udmabuf sizes are derived from geometry. Locked by tests in `CoreConfigSpec`.
- **Consumers wired (case tables / `-D` flags deleted):**
  - `scripts/build_all.sh` sources `preset.env` (non-clobbering, so env overrides still win);
    all per-preset case tables removed.
  - `software/Makefile` — `ifeq -DBENCH_*` blocks gone; geometry is purely runtime via
    `MMFREE_*` env, so ONE binary serves every preset (per-preset build dirs also removed).
  - `scripts/gen_overlay.tcl` reads NUM_DMA + udmabuf sizes from the manifest (single- AND
    multi-port nodes generated in TCL); static `udmabuf.dtsi.in` retired.
  - `mmfree_bridge/ctypes_lib.py` + `mmfree_pack/geometry.py` got `from_manifest()`; the
    `mmfree_pack.cli` + all three `phase_e_*` scripts accept `--manifest` / `$MMFREE_MANIFEST`.
  - Runtime (`mmfree_runtime.c`) cross-checks geometry vs the manifest at startup —
    `mmfree_geom_check_env()`, WARN-first; `MMFREE_STRICT=1` makes it fatal. Wired into
    `bench.c` and `mmfree_lib.c`.

**P0 task runner:** root `Makefile` with `help/sim/build/pack/deploy/bench/smoke/gate/run`.
Arg-heavy verbs take `PRESET=`/`BATCH=`/`ARGS=`; board verbs hand geometry through
`sudo env` (sudo strips the environment). Geometry from the manifest, addresses from `board.conf`.

**P1:** `board.conf.example` template (`board.conf` git-ignored; Makefile `-include`s it with
real-KRIA defaults). Board verbs build C natively (they `make -C` on the board). `deploy_kria.sh`
verifies each u-dma-buf node's sysfs size against the deployed manifest (kills the stale-overlay
footgun); `build_all.sh` ships `preset.env`/`.json` in `transfer/<preset>/`.

**P2:** sbt dropped (mill is the sole build tool; `build.sbt` + `project/` removed, CI + README +
CLAUDE.md updated). 

**SLIPPED (follow-up):** physically merging `bench` + `smoke` + `fpga-cli` into one subcommand
binary. The root `Makefile` already unifies the *UX* (one runner reaches all four harnesses), so
the binary merge is low-benefit / high-risk churn to proven board tooling — deferred by decision
2026-06-27. If picked up: they all already link `mmfree_runtime`/`libmmfree`; the blocker is just
C (bench/smoke) vs C++ (runner) in one build + a dispatcher.

**Not yet exercised on hardware:** the board-only paths (`make bench/smoke/gate/run`, the
`deploy_kria.sh` udmabuf check, the runtime manifest cross-check on a live mismatch) are logic-
verified on the host but await a board run. Flip the runtime check to hard-assert (drop the
WARN-first) only after a known-good board run, per the original plan.
