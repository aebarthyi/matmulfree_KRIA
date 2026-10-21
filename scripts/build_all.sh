#!/usr/bin/env bash
# scripts/build_all.sh — full hardware flow, Chisel → board bundle. Run from anywhere.
#
#   Chisel  ──mill──▶  generated/<preset>/*.sv + preset.env  (packaged IP user.org:user:CoreTop:1.0)
#   bd.tcl  ─vivado─▶  build/t_matmul/  +  build/t_matmul.xsa   (bitstream + platform)
#   xsa     ──xsct──▶  build/overlay/{core_bench.bit,.dtbo,shell.json}
#   .bit  ─bootgen─▶   build/overlay/core_bench.bit.bin
#   assemble       ▶   transfer/presets/<preset>/ + shared transfer/{Makefile, *.sh, udmabuf/, src/}
#
#   ./scripts/build_all.sh
#
# Requires on PATH (source your Vivado 2025.2 settings64.sh first):
#   mill (repo-local ./mill), vivado, xsct, dtc, bootgen, unzip
#
# Environment overrides:
#   PRESET=mmfree_2_7b  CoreConfig preset emitted + packaged
#   JOBS=8              parallel synth/impl jobs
#   DT_BRANCH=...       device-tree-xlnx branch for xsct createdts
#   DT_REPO=...         local device-tree-xlnx clone (offline builds)
#   REPACKAGE=0         1 = re-package the IP from SV. Required when ports OR the SV file set
#                       changed: the IP's fixed file manifest makes a new module "not found"
#   SKIP_SV=0           1 = skip Chisel SV regeneration
#   BITSTREAM_ONLY=0    1 = stop after the bitstream/XSA (steps 1-3)
#   PACKAGE_ONLY=0      1 = skip steps 1-3, package the existing build/t_matmul.xsa (steps 4-6)
#   STAGE_ONLY=0        1 = only re-stage the preset-independent bundle parts, then exit
#                       (`make stage`). Run it after editing software/ or scripts/: the board
#                       compiles from transfer/src/, so a stale bundle silently measures old code.

set -euo pipefail

# ─── Locate repo root ───────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

PRESET="${PRESET:-mmfree_2_7b}"
DESIGN="t_matmul"

# ─── Preset manifest = single source of truth ───────────────────────────────
# generated/<preset>/preset.env (emitted by control.EmitCore, step 1) is the single source of
# truth for geometry. load_manifest sets each KEY only if it is not already in the
# environment, so overrides win, e.g.  PL_CLK_MHZ=100 ./scripts/build_all.sh
load_manifest() {
    local f="$1" line key val
    [ -f "$f" ] || { echo "ERROR: preset manifest $f missing — run a full build" \
        "(SKIP_SV=0, no PACKAGE_ONLY) once to generate it." >&2; return 1; }
    while IFS= read -r line; do
        case "$line" in ''|\#*) continue ;; esac
        key="${line%%=*}"; val="${line#*=}"
        [ -n "${!key+x}" ] || { printf -v "$key" '%s' "$val"; export "$key"; }
    done < "$f"
    # Above 100 MHz use the post-route phys_opt explore strategy (override: IMPL_STRATEGY=...).
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
# transfer/ is the self-contained board bundle: per-preset HW under presets/<preset>/, plus
# shared parts (board Makefile + scripts, u-dma-buf driver, C++ sources) staged once.
BUNDLE_ROOT="$REPO_ROOT/transfer"
TRANSFER_DIR="$BUNDLE_ROOT/presets/$PRESET"

# Shared, preset-independent bundle parts (staged once; idempotent — safe every build).
stage_shared() {
    # Board entrypoint + on-board scripts (tracked sources in scripts/, staged here).
    cp "$SCRIPT_DIR/transfer.mk"        "$BUNDLE_ROOT/Makefile"
    cp "$SCRIPT_DIR/deploy_kria.sh"     "$BUNDLE_ROOT/deploy_kria.sh"
    cp "$SCRIPT_DIR/power_bench.sh"     "$BUNDLE_ROOT/power_bench.sh"
    cp "$SCRIPT_DIR/bench_batch.sh"     "$BUNDLE_ROOT/bench_batch.sh"
    cp "$SCRIPT_DIR/sweep_all.sh"       "$BUNDLE_ROOT/sweep_all.sh"
    cp "$SCRIPT_DIR/final_sweep_board.sh" "$BUNDLE_ROOT/final_sweep_board.sh"
    cp "$SCRIPT_DIR/split_sweep_csv.py" "$BUNDLE_ROOT/split_sweep_csv.py"
    cp "$SCRIPT_DIR/cma_reclaim.sh"      "$BUNDLE_ROOT/cma_reclaim.sh"
    cp "$SCRIPT_DIR/cma_boot_raise.sh"   "$BUNDLE_ROOT/cma_boot_raise.sh"
    cp "$SCRIPT_DIR/gate_all.sh"         "$BUNDLE_ROOT/gate_all.sh"
    chmod +x "$BUNDLE_ROOT/deploy_kria.sh" "$BUNDLE_ROOT/power_bench.sh" \
             "$BUNDLE_ROOT/bench_batch.sh" "$BUNDLE_ROOT/sweep_all.sh" \
             "$BUNDLE_ROOT/final_sweep_board.sh" \
             "$BUNDLE_ROOT/split_sweep_csv.py" "$BUNDLE_ROOT/cma_reclaim.sh" \
             "$BUNDLE_ROOT/cma_boot_raise.sh" "$BUNDLE_ROOT/gate_all.sh"
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

    # C++ sources the board native-compiles, nesting preserved so the sub-Makefiles' relative
    # paths resolve. Dest dirs pre-created: rsync won't make several missing parent levels.
    mkdir -p "$BUNDLE_ROOT/src/software" "$BUNDLE_ROOT/src/matmulfreellmCPU/cpp"
    # Exclude build output and model blobs/golden refs; --delete-excluded prunes them from
    # the dest too if already staged.
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

# STAGE_ONLY needs no preset or toolchain, so it runs before any build step.
if [ "${STAGE_ONLY:-0}" = "1" ]; then
    echo "==> STAGE_ONLY=1: re-staging the preset-independent bundle parts only"
    stage_shared
    echo
    echo "# transfer/{Makefile,*.sh,split_sweep_csv.py,src/} re-staged from the repo."
    echo "# Nothing preset-specific was touched — no bitstream, no manifest, no model."
    echo "# Next: make deploy BOARD=... ; then on the board: make build   (native recompile)"
    exit 0
fi


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

    # ─── 2. (optional) Re-package CoreTop IP ────────────────────────────────
    # generated/ is unversioned, so package automatically when component.xml is absent;
    # REPACKAGE=1 forces it.
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
# Also point gen_overlay at the manifest, so a standalone re-run is self-sufficient.
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
# Ship the manifest so deploy_kria.sh and the runtime can cross-check geometry.
cp "$SV_DIR/preset.env"  "$TRANSFER_DIR/" 2>/dev/null || true
cp "$SV_DIR/preset.json" "$TRANSFER_DIR/" 2>/dev/null || true

# PL_CLK_MHZ is a request (the PS snaps off-rung rates DOWN, e.g. 230 -> 199.998). Append the
# rate build_project.tcl actually constrained, so the board sets fclk0 from it.
if [ -f "$REPO_ROOT/build/pl_clk_actual.env" ] && [ -f "$TRANSFER_DIR/preset.env" ]; then
    cat "$REPO_ROOT/build/pl_clk_actual.env" >> "$TRANSFER_DIR/preset.env"
    _act=$(sed -n 's/^PL_CLK_ACT_MHZ=//p' "$REPO_ROOT/build/pl_clk_actual.env")
    echo "#   constrained fabric clock: ${_act} MHz (requested ${PL_CLK_MHZ})"
fi

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
