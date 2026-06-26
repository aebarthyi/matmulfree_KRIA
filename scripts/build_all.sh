#!/usr/bin/env bash
# scripts/build_all.sh — full hardware flow for the t_matmul bench, Chisel → board.
#
#   Chisel  ──mill──▶  generated/k26_bench/*.sv   (SystemVerilog)
#                       (packaged IP: user.org:user:CoreTop:1.0)
#   bd.tcl  ─vivado─▶  build/t_matmul/  +  build/t_matmul.xsa   (bitstream + platform)
#   xsa     ──xsct──▶  build/overlay/{core_bench.bit,.dtbo,shell.json}
#   .bit  ─bootgen─▶   build/overlay/core_bench.bit.bin
#   assemble       ▶   transfer/<preset>/{core_bench.bit.bin, .dtbo, shell.json, deploy_kria.sh}
#                       (one folder per preset — configs coexist, no rebuild to switch)
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

# Stream geometry per preset (must mirror CoreConfig presets). The s_axis
# stream is xDim*aWidth bits; one PS HP port carries at most 128 bits, so wider
# streams are split across NUM_DMA parallel DMAs/HP ports (HP0..HP3), each
# 128 bits wide. vivado/bd.tcl consumes NUM_DMA + MM2S_WIDTH via the
# environment; gen_overlay.tcl consumes NUM_DMA for the udmabuf node count.
case "$PRESET" in
    k26_bench)       XDIM=4;  AWIDTH=16 ;;
    k26_bench16)     XDIM=16; AWIDTH=8  ;;
    k26_bench32)     XDIM=32; AWIDTH=8  ;;
    k26_bench64)     XDIM=64; AWIDTH=8  ;;
    k26_mmfree370m)     XDIM=64; AWIDTH=8  ;;
    k26_mmfree370m_a16) XDIM=32; AWIDTH=16 ;;   # signed int16 activations
    # Batched a16 (CoreConfig.batchSize=B): B activation vectors per weight
    # stream. B<=xDim keeps the same 512-b/4-port topology as the B=1 a16 preset;
    # only the engine's PE rows / output drain scale (see BATCH below).
    k26_mmfree370m_a16_b2|k26_mmfree370m_a16_b4|k26_mmfree370m_a16_b6|k26_mmfree370m_a16_b8) XDIM=32; AWIDTH=16 ;;
    *) echo "ERROR: preset '$PRESET' has no geometry entry here — add xDim/aWidth (mirror CoreConfig)." >&2
       exit 1 ;;
esac

# Batch size baked into the bitstream (parsed from the _bN preset suffix). Only
# the OUTPUT udmabuf scales with it — the engine drains B output vectors per
# STORE; activations (B fit one port slice for B<=8) and weights are unchanged.
case "$PRESET" in
    *_b2) BATCH=2 ;;
    *_b4) BATCH=4 ;;
    *_b6) BATCH=6 ;;
    *_b8) BATCH=8 ;;
    *)    BATCH=1 ;;
esac

# Output stream (CoreTop m_axis / DMA0 S2MM) width = outBeatLanes * outLaneWidth.
# The batched a16 presets use outBeatLanes=4 (outLaneWidth=32) → 128-bit S2MM so
# the store isn't capped at the 32-bit ~1 GB/s ceiling (128-bit = the HP port =
# ~4 GB/s). Default 32. Mirrors CoreConfig outBeatLanes — keep in sync (the
# preset-manifest consolidation in docs/REPO_CONSOLIDATION_PLAN.md removes this).
if [ "$BATCH" -gt 1 ]; then S2MM_WIDTH=128; else S2MM_WIDTH=32; fi
export S2MM_WIDTH

# PL fabric clock (vivado/bd.tcl PL0_REF). 250 MHz closes since the 2026-06-11
# Core pipeline stages (inQ/outQ queues terminate both BRAM-sourced critical
# paths in registers — OOC WNS +1.12 ns; pre-pipeline 250 was marginal at
# +0.002/-0.212 ns across seeds). Note the PS clock wizard grants the nearest
# achievable PLL rate, not the request (e.g. a 214 ask lands on ACT=199.998) —
# verify assigned-clock-rates in the generated dts and keep the bench's
# BENCH_CLK_MHZ matching it. Legacy presets keep their proven 100 MHz builds.
# Override per build with PL_CLK_MHZ=... in the environment.
case "$PRESET" in
    k26_mmfree370m|k26_mmfree370m_a16|k26_mmfree370m_a16_b2|k26_mmfree370m_a16_b4|k26_mmfree370m_a16_b6|k26_mmfree370m_a16_b8) PL_CLK_MHZ="${PL_CLK_MHZ:-250}" ;;
    *)                                 PL_CLK_MHZ="${PL_CLK_MHZ:-100}" ;;
esac
export PL_CLK_MHZ

# Above 100 MHz the default impl strategy leaves ~90 ps on the table on the
# store path (outBram -> lane mux -> S2MM skid buffer); use the post-route
# phys_opt explore strategy there. Override with IMPL_STRATEGY=... in the env.
if [ "$PL_CLK_MHZ" -gt 100 ]; then
    export IMPL_STRATEGY="${IMPL_STRATEGY:-Performance_ExplorePostRoutePhysOpt}"
fi

# u-dma-buf node sizes for gen_overlay.tcl. Defaults (unset) keep the historical
# 16K/256K/4K nodes; the 370M preset needs room for maxN=4096 activations, the
# pow2 sweep's worst weight stream (4096 rows x 125 col tiles x 16 B/port =
# 8 MiB — the 370m projection sweep itself peaks at 2 MiB/port on lm_head),
# and the 32000-lane output (125 KiB).
case "$PRESET" in
  k26_mmfree370m|k26_mmfree370m_a16|k26_mmfree370m_a16_b2|k26_mmfree370m_a16_b4|k26_mmfree370m_a16_b6|k26_mmfree370m_a16_b8)  # 16 B/port → same act/wt sizes
    # Defaults cover the per-shape bench (wt peaks ~8 MiB on the pow2 sweep). The
    # resident full-model runner (integration/fpga_runner) holds ALL 145 projections
    # at once — ~21.3 MiB/port — so override with UDMABUF_WT_SZ=0x01800000 (24 MiB)
    # before this script for that flow. All honor a pre-set env value.
    # Batched presets drain BATCH output vectors per STORE, so the OUT node scales
    # xBATCH (act/wt unchanged — B<=8 fits one port slice; weights are broadcast).
    export UDMABUF_ACT_SZ=${UDMABUF_ACT_SZ:-0x00010000}   #  64 KiB / port
    export UDMABUF_WT_SZ=${UDMABUF_WT_SZ:-0x00800000}     #   8 MiB / port (bench)
    export UDMABUF_OUT_SZ=${UDMABUF_OUT_SZ:-$(printf '0x%08x' $((BATCH * 0x00020000)))}   # 128 KiB x BATCH
    ;;
esac
STREAM_BITS=$((XDIM * AWIDTH))
if [ "$STREAM_BITS" -le 128 ]; then
    NUM_DMA=1
    MM2S_WIDTH=$STREAM_BITS
else
    [ $((STREAM_BITS % 128)) -eq 0 ] || { echo "ERROR: stream width $STREAM_BITS not a multiple of 128." >&2; exit 1; }
    NUM_DMA=$((STREAM_BITS / 128))
    [ "$NUM_DMA" -le 4 ] || { echo "ERROR: stream width $STREAM_BITS needs $NUM_DMA HP ports (max 4)." >&2; exit 1; }
    MM2S_WIDTH=128
fi
export NUM_DMA MM2S_WIDTH
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
# Per-config: each preset lands in its own transfer/<preset>/ so multiple built
# bitstreams coexist and you can switch configs on the board without rebuilding.
TRANSFER_DIR="$REPO_ROOT/transfer/$PRESET"

# ─── Pre-flight: required tools ─────────────────────────────────────────────
need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' not on PATH. Source Vivado settings64.sh / install it." >&2; exit 1; }; }
[ "$PACKAGE_ONLY" = "1" ]   || need vivado
[ "$BITSTREAM_ONLY" = "1" ] || for t in xsct dtc bootgen unzip; do need "$t"; done

echo "############################################################"
echo "# t_matmul full build"
echo "#   preset = $PRESET   jobs = $JOBS   repackage = $REPACKAGE"
echo "#   stream = ${STREAM_BITS}b -> $NUM_DMA DMA(s) x ${MM2S_WIDTH}b MM2S @ ${PL_CLK_MHZ} MHz"
echo "############################################################"

# ─── 1. Chisel → SystemVerilog ──────────────────────────────────────────────
if [ "$PACKAGE_ONLY" = "1" ]; then
    echo; echo "==> [1-3/6] Skipping build steps (PACKAGE_ONLY=1) — reusing $XSA"
    [ -f "$XSA" ] || { echo "ERROR: $XSA missing — run the bitstream build first." >&2; exit 1; }
else
    if [ "$SKIP_SV" = "0" ]; then
        echo; echo "==> [1/6] Emitting SystemVerilog ($PRESET) via mill"
        ./mill matmulfree_KRIA.runMain control.EmitCore "$PRESET" "generated/$PRESET"
    else
        echo; echo "==> [1/6] Skipping SV regeneration (SKIP_SV=1)"
    fi

    # Note: batched presets emit the output memory as a URAM BlackBox
    # (control.OutMemUltra, ram_style="ultra") directly from Chisel — no SV
    # post-processing needed. REPACKAGE=1 is still required to pull regenerated
    # SV (incl. the BlackBox module) into the packaged IP.

    # ─── 2. (optional) Re-package CoreTop IP ────────────────────────────────
    if [ "$REPACKAGE" = "1" ]; then
        echo; echo "==> [2/6] Re-packaging CoreTop IP from $SV_DIR"
        vivado -mode batch -nojournal -notrace -source "$SCRIPT_DIR/package_ip.tcl" \
            -tclargs "$SV_DIR" CoreTop
    else
        echo; echo "==> [2/6] Reusing packaged IP at $SV_DIR (REPACKAGE=0)"
        [ -f "$SV_DIR/component.xml" ] || { echo "ERROR: $SV_DIR/component.xml missing — run with REPACKAGE=1." >&2; exit 1; }
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
xsct "$SCRIPT_DIR/gen_overlay.tcl" "$XSA" "$OVERLAY_DIR"
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

# ─── 6. Assemble transfer/<preset>/ ─────────────────────────────────────────
echo; echo "==> [6/6] Assembling $TRANSFER_DIR/"
rm -rf "$TRANSFER_DIR"
mkdir -p "$TRANSFER_DIR"
cp "$OVERLAY_DIR/$APP.bit.bin" "$TRANSFER_DIR/"
cp "$OVERLAY_DIR/$APP.dtbo"    "$TRANSFER_DIR/"
cp "$OVERLAY_DIR/shell.json"   "$TRANSFER_DIR/"
cp "$SCRIPT_DIR/deploy_kria.sh" "$TRANSFER_DIR/"
chmod +x "$TRANSFER_DIR/deploy_kria.sh"

echo
echo "############################################################"
echo "# DONE. transfer/$PRESET/ ready for the KRIA:"
ls -la "$TRANSFER_DIR"
echo "#"
echo "# Deploy on the board (per-config — other configs are left intact):"
echo "#   scp -r transfer/$PRESET ubuntu@<kria>:~/transfer/"
echo "#   sudo ~/transfer/$PRESET/deploy_kria.sh"
echo "############################################################"
