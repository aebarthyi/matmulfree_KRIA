#!/usr/bin/env bash
# scripts/build_all.sh — full hardware flow for the t_matmul bench, Chisel → board.
#
#   Chisel  ──mill──▶  generated/k26_bench/*.sv   (SystemVerilog)
#                       (packaged IP: user.org:user:CoreTop:1.0)
#   bd.tcl  ─vivado─▶  build/t_matmul/  +  build/t_matmul.xsa   (bitstream + platform)
#   xsa     ──xsct──▶  build/overlay/{core_bench.bit,.dtbo,shell.json}
#   .bit  ─bootgen─▶   build/overlay/core_bench.bit.bin
#   assemble       ▶   transfer/presets/<preset>/{core_bench.bit.bin, .dtbo, shell.json, preset.env}
#                       + shared transfer/{Makefile, deploy_kria.sh, board.conf.example,
#                                          udmabuf/, src/{software,matmulfreellmCPU/cpp}}
#                       (per-preset HW coexists; the board needs ONLY the transfer/ bundle)
#
# Run from anywhere; paths resolve relative to the repo root.
#
#   ./scripts/build_all.sh
#
# Requires on PATH (source your Vivado 2025.2 settings64.sh first):
#   mill (repo-local ./mill), vivado, xsct, dtc, bootgen, unzip
#
# Environment overrides:
#   PRESET=k26_bench    CoreConfig preset emitted + packaged
#   JOBS=8              parallel synth/impl jobs
#   DT_BRANCH=...       device-tree-xlnx branch for xsct createdts
#   DT_REPO=...         local device-tree-xlnx clone (offline builds)
#   REPACKAGE=0         set 1 to re-package the IP from SV. Needed when the
#                       interface changed (port widths / new ports) OR the SV
#                       file set changed (new submodules, e.g. added Queues) —
#                       the packaged IP carries a fixed file manifest, so a
#                       module in a new file synth-fails as "module not found"

#   SKIP_SV=0           set 1 to skip Chisel SV regeneration
#   BITSTREAM_ONLY=0    set 1 to stop after the bitstream/XSA (steps 1-3)
#   PACKAGE_ONLY=0      set 1 to skip steps 1-3 and package the existing
#                       build/t_matmul.xsa into transfer/<preset>/ (steps 4-6)

set -euo pipefail

# ─── Locate repo root ───────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

PRESET="${PRESET:-k26_bench}"
DESIGN="t_matmul"

# ─── Preset manifest = single source of truth ───────────────────────────────
# Every geometry/deployment field (XDIM, AWIDTH, NUM_DMA, MM2S_WIDTH, S2MM_WIDTH,
# PL_CLK_MHZ, UDMABUF_ACT/WT/OUT_SZ …) is derived once by CoreConfig and emitted
# to generated/<preset>/preset.env by control.EmitCore (step 1). This script
# SOURCES that file rather than re-deriving — the per-preset case tables that used
# to live here (and silently drifted from CoreConfig) are gone.
#
# load_manifest sets each KEY=VAL pair only if the var is NOT already in the
# environment, so documented overrides still win. e.g.:
#   UDMABUF_WT_SZ=0x01800000 ./scripts/build_all.sh   # 24 MiB resident-runner set
#   PL_CLK_MHZ=100 ./scripts/build_all.sh             # rebuild a 250 preset at 100
load_manifest() {
    local f="$1" line key val
    [ -f "$f" ] || { echo "ERROR: preset manifest $f missing — run a full build" \
        "(SKIP_SV=0, no PACKAGE_ONLY) once to generate it." >&2; return 1; }
    while IFS= read -r line; do
        case "$line" in ''|\#*) continue ;; esac
        key="${line%%=*}"; val="${line#*=}"
        [ -n "${!key+x}" ] || { printf -v "$key" '%s' "$val"; export "$key"; }
    done < "$f"
    # Above 100 MHz the default impl strategy leaves ~90 ps on the store path
    # (outBram -> lane mux -> S2MM skid buffer); use the post-route phys_opt
    # explore strategy. Override with IMPL_STRATEGY=... in the env.
    if [ "${PL_CLK_MHZ:-100}" -gt 100 ]; then
        export IMPL_STRATEGY="${IMPL_STRATEGY:-Performance_ExplorePostRoutePhysOpt}"
    fi
}

print_banner() {
    echo "############################################################"
    echo "# t_matmul full build"
    echo "#   preset = $PRESET   jobs = $JOBS   repackage = $REPACKAGE"
    echo "#   stream = ${MMFREE_AXIS_DATA_WIDTH}b -> $NUM_DMA DMA(s) x ${MM2S_WIDTH}b MM2S @ ${PL_CLK_MHZ} MHz"
    echo "############################################################"
}

APP="core_bench"            # xmutil app name; output files are <APP>.*
JOBS="${JOBS:-8}"
REPACKAGE="${REPACKAGE:-0}"
SKIP_SV="${SKIP_SV:-0}"
BITSTREAM_ONLY="${BITSTREAM_ONLY:-0}"
PACKAGE_ONLY="${PACKAGE_ONLY:-0}"

SV_DIR="$REPO_ROOT/generated/$PRESET"
PROJ_DIR="$REPO_ROOT/build/$DESIGN"
XSA="$REPO_ROOT/build/$DESIGN.xsa"
OVERLAY_DIR="$REPO_ROOT/build/overlay"
# transfer/ is the self-contained bundle the KRIA needs — nothing else from the repo.
# Per-preset HW lands under transfer/presets/<preset>/ (configs coexist; switch on the
# board with no rebuild). The shared, preset-independent parts (C++ sources under src/,
# the u-dma-buf driver, the board Makefile + scripts) are staged once into transfer/.
BUNDLE_ROOT="$REPO_ROOT/transfer"
TRANSFER_DIR="$BUNDLE_ROOT/presets/$PRESET"

# ─── Pre-flight: required tools ─────────────────────────────────────────────
need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' not on PATH. Source Vivado settings64.sh / install it." >&2; exit 1; }; }
[ "$PACKAGE_ONLY" = "1" ]   || need vivado
[ "$BITSTREAM_ONLY" = "1" ] || for t in xsct dtc bootgen unzip; do need "$t"; done

# ─── 1. Chisel → SystemVerilog + preset manifest ────────────────────────────
if [ "$PACKAGE_ONLY" = "1" ]; then
    echo; echo "==> [1-3/6] Skipping build steps (PACKAGE_ONLY=1) — reusing $XSA"
    [ -f "$XSA" ] || { echo "ERROR: $XSA missing — run the bitstream build first." >&2; exit 1; }
    load_manifest "$SV_DIR/preset.env" || exit 1
    print_banner
else
    if [ "$SKIP_SV" = "0" ]; then
        echo; echo "==> [1/6] Emitting SystemVerilog + preset manifest ($PRESET) via mill"
        ./mill matmulfree_KRIA.runMain control.EmitCore "$PRESET" "generated/$PRESET"
    else
        echo; echo "==> [1/6] Skipping SV regeneration (SKIP_SV=1)"
    fi
    # Manifest now exists (just emitted, or from a prior build when SKIP_SV=1).
    load_manifest "$SV_DIR/preset.env" || exit 1
    print_banner

    # Note: batched presets emit the output memory as a URAM BlackBox
    # (control.OutMemUltra, ram_style="ultra") directly from Chisel — no SV
    # post-processing needed. REPACKAGE=1 is still required to pull regenerated
    # SV (incl. the BlackBox module) into the packaged IP.

    # ─── 2. (optional) Re-package CoreTop IP ────────────────────────────────
    # The packaged IP (component.xml) lives under generated/<preset>/, which is a
    # build product and not versioned — so a fresh checkout (or a brand-new preset)
    # has no component.xml. Package automatically in that case; REPACKAGE=1 forces a
    # re-package even when one exists (needed when ports / the SV file set changed).
    if [ "$REPACKAGE" = "1" ]; then
        echo; echo "==> [2/6] Re-packaging CoreTop IP from $SV_DIR (REPACKAGE=1)"
        vivado -mode batch -nojournal -notrace -source "$SCRIPT_DIR/package_ip.tcl" \
            -tclargs "$SV_DIR" CoreTop
    elif [ -f "$SV_DIR/component.xml" ]; then
        echo; echo "==> [2/6] Reusing packaged IP at $SV_DIR (REPACKAGE=0)"
    else
        echo; echo "==> [2/6] No packaged IP at $SV_DIR (component.xml absent) — packaging from SV"
        vivado -mode batch -nojournal -notrace -source "$SCRIPT_DIR/package_ip.tcl" \
            -tclargs "$SV_DIR" CoreTop
    fi

    # ─── 3. Vivado project + bitstream + XSA ────────────────────────────────
    echo; echo "==> [3/6] Building Vivado project + bitstream + XSA"
    vivado -mode batch -nojournal -notrace -source "$SCRIPT_DIR/build_project.tcl" \
        -tclargs "$PROJ_DIR" "$SV_DIR" "$JOBS"
    [ -f "$XSA" ] || { echo "ERROR: XSA not produced: $XSA" >&2; exit 1; }
fi

if [ "$BITSTREAM_ONLY" = "1" ]; then
    echo; echo "# BITSTREAM_ONLY=1 — stopping after XSA: $XSA"
    echo "# Package later with: PACKAGE_ONLY=1 ./scripts/build_all.sh"
    exit 0
fi

# ─── 4. XSA → overlay (.bit, .dtbo, shell.json) ─────────────────────────────
echo; echo "==> [4/6] Generating device-tree overlay + shell.json (xsct createdts)"
rm -rf "$OVERLAY_DIR"
# NUM_DMA + UDMABUF_* are already in the environment (load_manifest); also point
# gen_overlay at the manifest file so a standalone re-run is self-sufficient.
MMFREE_MANIFEST="$SV_DIR/preset.env" xsct "$SCRIPT_DIR/gen_overlay.tcl" "$XSA" "$OVERLAY_DIR"
for f in "$APP.bit" "$APP.dtbo" shell.json; do
    [ -f "$OVERLAY_DIR/$f" ] || { echo "ERROR: overlay step did not produce $f" >&2; exit 1; }
done

# ─── 5. bitstream → .bit.bin (bootgen, for fpga_manager / xmutil) ───────────
echo; echo "==> [5/6] Converting $APP.bit → $APP.bit.bin (bootgen)"
(
    cd "$OVERLAY_DIR"
    printf 'all:\n{\n\t%s.bit\n}\n' "$APP" > "$APP.bif"
    bootgen -arch zynqmp -image "$APP.bif" -process_bitstream bin -w
)
[ -f "$OVERLAY_DIR/$APP.bit.bin" ] || { echo "ERROR: bootgen did not produce $APP.bit.bin" >&2; exit 1; }

# ─── 6. Assemble the bundle: transfer/presets/<preset>/ + shared parts ───────
echo; echo "==> [6/6] Assembling $TRANSFER_DIR/  (+ staging shared transfer/ parts)"
rm -rf "$TRANSFER_DIR"
mkdir -p "$TRANSFER_DIR"
cp "$OVERLAY_DIR/$APP.bit.bin" "$TRANSFER_DIR/"
cp "$OVERLAY_DIR/$APP.dtbo"    "$TRANSFER_DIR/"
cp "$OVERLAY_DIR/shell.json"   "$TRANSFER_DIR/"
# Ship the preset manifest so deploy_kria.sh can verify the udmabuf node sizes
# and the on-board runtime can cross-check geometry against the loaded bitstream.
cp "$SV_DIR/preset.env"  "$TRANSFER_DIR/" 2>/dev/null || true
cp "$SV_DIR/preset.json" "$TRANSFER_DIR/" 2>/dev/null || true

# Shared, preset-independent bundle parts (staged once; idempotent — safe every build).
stage_shared() {
    # Board entrypoint + on-board scripts (tracked sources in scripts/, staged here).
    cp "$SCRIPT_DIR/transfer.mk"        "$BUNDLE_ROOT/Makefile"
    cp "$SCRIPT_DIR/deploy_kria.sh"     "$BUNDLE_ROOT/deploy_kria.sh"
    cp "$SCRIPT_DIR/power_bench.sh"     "$BUNDLE_ROOT/power_bench.sh"
    cp "$SCRIPT_DIR/bench_batch.sh"     "$BUNDLE_ROOT/bench_batch.sh"
    cp "$SCRIPT_DIR/sweep_all.sh"       "$BUNDLE_ROOT/sweep_all.sh"
    cp "$SCRIPT_DIR/split_sweep_csv.py" "$BUNDLE_ROOT/split_sweep_csv.py"
    cp "$SCRIPT_DIR/sweep_split_ab.sh"   "$BUNDLE_ROOT/sweep_split_ab.sh"
    cp "$SCRIPT_DIR/sweep_split_k.sh"    "$BUNDLE_ROOT/sweep_split_k.sh"
    chmod +x "$BUNDLE_ROOT/deploy_kria.sh" "$BUNDLE_ROOT/power_bench.sh" \
             "$BUNDLE_ROOT/bench_batch.sh" "$BUNDLE_ROOT/sweep_all.sh" \
             "$BUNDLE_ROOT/split_sweep_csv.py" "$BUNDLE_ROOT/sweep_split_ab.sh" \
             "$BUNDLE_ROOT/sweep_split_k.sh"
    cp "$SCRIPT_DIR/board.conf.example" "$BUNDLE_ROOT/board.conf.example" 2>/dev/null || true

    # u-dma-buf driver source (vendored submodule), so the board needs no external module.
    local UDMABUF_SRC="$REPO_ROOT/external/udmabuf"
    if [ -f "$UDMABUF_SRC/u-dma-buf.c" ]; then
        mkdir -p "$BUNDLE_ROOT/udmabuf"
        cp "$UDMABUF_SRC"/u-dma-buf.c "$UDMABUF_SRC"/u-dma-buf-ioctl.h \
           "$UDMABUF_SRC"/Makefile "$UDMABUF_SRC"/Kconfig "$UDMABUF_SRC"/LICENSE \
           "$BUNDLE_ROOT/udmabuf/" 2>/dev/null || true
    else
        echo "WARN: external/udmabuf submodule not initialized — transfer/ won't carry the" >&2
        echo "      u-dma-buf driver; run 'git submodule update --init --recursive' and rebuild." >&2
    fi

    # C++ sources the board native-compiles. The nesting is PRESERVED (software/ and
    # matmulfreellmCPU/cpp side by side under src/) so the component sub-Makefiles —
    # fpga_runner's ../../../matmulfreellmCPU/cpp etc. — resolve and compile unchanged.
    # Pre-create the dest dirs: rsync won't make multiple missing parent levels
    # (e.g. the intermediate src/matmulfreellmCPU/), and --mkpath isn't universal.
    mkdir -p "$BUNDLE_ROOT/src/software" "$BUNDLE_ROOT/src/matmulfreellmCPU/cpp"
    # Exclude build artifacts and large NON-build data: stray model blobs that may sit in
    # the submodule tree (the real model lives in transfer/models/), and the golden/ test
    # references (validation-branch only — the board builds just the mmfreecpu lib + runner).
    # --delete-excluded (not just --delete): prune excluded files from the dest too, so a
    # stray model blob / host build/ already staged there is removed, not merely skipped.
    local RSX="--delete --delete-excluded --exclude=build/ --exclude=*.o --exclude=*.a \
               --exclude=*.so --exclude=__pycache__/ --exclude=.git --exclude=*.mmfree \
               --exclude=*.mmtok --exclude=golden/ --exclude=golden_fixedp/"
    if command -v rsync >/dev/null 2>&1; then
        # shellcheck disable=SC2086
        rsync -a $RSX "$REPO_ROOT/software/"               "$BUNDLE_ROOT/src/software/"
        # shellcheck disable=SC2086
        rsync -a $RSX "$REPO_ROOT/matmulfreellmCPU/cpp/"   "$BUNDLE_ROOT/src/matmulfreellmCPU/cpp/"
    else
        echo "WARN: rsync not found — falling back to cp (won't prune stale files)." >&2
        mkdir -p "$BUNDLE_ROOT/src/software" "$BUNDLE_ROOT/src/matmulfreellmCPU/cpp"
        cp -a "$REPO_ROOT/software/."             "$BUNDLE_ROOT/src/software/"
        cp -a "$REPO_ROOT/matmulfreellmCPU/cpp/." "$BUNDLE_ROOT/src/matmulfreellmCPU/cpp/"
    fi
    [ -f "$REPO_ROOT/matmulfreellmCPU/cpp/CMakeLists.txt" ] || \
        echo "WARN: matmulfreellmCPU submodule not initialized — fpga_runner won't build on the board." >&2
}
stage_shared

echo
echo "############################################################"
echo "# DONE. transfer/ bundle ready for the KRIA (preset $PRESET):"
echo "#   transfer/presets/$PRESET/  $(ls "$TRANSFER_DIR" | tr '\n' ' ')"
echo "#   transfer/{Makefile, deploy_kria.sh, udmabuf/, src/}"
echo "#"
echo "# Ship + run (build server -> board):"
echo "#   make model  PRESET=$PRESET               # provision + pack this preset's model"
echo "#   make deploy BOARD=ubuntu@<kria>          # rsync transfer/ -> board:~/transfer/"
echo "# then, on the KRIA (in ~/transfer):"
echo "#   make build && sudo make deploy-hw PRESET=$PRESET"
echo "#   make gate PRESET=$PRESET"
echo "############################################################"
