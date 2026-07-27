#!/usr/bin/env bash
# sweep_all.sh — deploy each bitstream in turn and run its batch sweep, unattended.
#
# Every step here needs root (the overlay load, and /dev/mem for each timed run), so run the
# WHOLE script under sudo once rather than typing a password per run:
#
#   cd ~/transfer && sudo ./sweep_all.sh
#
# Results land in results/*.csv (chowned back to you at the end) and per-run logs in
# results/logs/. One CSV per bitstream, one row per batch size.
#
# Each spec is  preset:model:tag:extra-runner-flags  (trailing fields optional):
#   preset — presets/<preset>/ in this bundle; its overlay is loaded before its sweep
#   model  — models/<model>/ to run on it; empty = the preset manifest's own MMFREE_MODEL
#   tag    — suffix for the output filename, to sweep one preset twice under different flags
#   extra  — appended verbatim to the runner flags (e.g. a non-default --packed-dir)
# Override the default list by passing your own specs as arguments.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
HERE="$(pwd)"

[ "$(id -u)" -eq 0 ] || { echo "ERROR: run under sudo (the overlay load and /dev/mem both need root): sudo ./sweep_all.sh" >&2; exit 1; }

GEN=${GEN:-64}
REPS=${REPS:-3}
WARMUP=${WARMUP:-1}

# Default sweep set: the best build per model size, plus the 1.6-bit A/B on the same B4
# DSP+banking stack (same geometry, same model — only the weight-stream encoding differs,
# so it is a clean measurement of what the packing buys).
DEFAULT_SPECS=(
    "k26_mmfree370m_a16_b6:370m"
    "k26_mmfree2_7b_a16_b4_dsp_s8"
    "k26_mmfree2_7b_a16_b4_dsp_s8_packed::1p6:--packed-dir $HERE/models/ridger/MMfreeLM-2.7B/packed-1p6"
)
if [ $# -gt 0 ]; then SPECS=("$@"); else SPECS=("${DEFAULT_SPECS[@]}"); fi

mkdir -p results/logs
failed=""
for spec in "${SPECS[@]}"; do
    IFS=: read -r preset model tag extra <<< "$spec"
    echo
    echo "############################################################"
    echo "# $preset  ${model:+model=$model }${tag:+tag=$tag}"
    echo "############################################################"

    # Load this preset's overlay + u-dma-buf. deploy_kria.sh also sets fclk0 from the
    # manifest's PL_CLK_MHZ — required, because an overlay reload does NOT reset the fabric
    # clock, so a 230 MHz build would otherwise silently run at whatever the last one left.
    if ! ./deploy_kria.sh "$preset" > "results/logs/deploy_$preset.log" 2>&1; then
        echo "!! deploy-hw FAILED — see results/logs/deploy_$preset.log"
        tail -20 "results/logs/deploy_$preset.log"
        failed="$failed $preset(deploy)"
        continue
    fi
    grep -E "fclk0|udmabuf|WARN|ERROR" "results/logs/deploy_$preset.log" | head -12 | sed 's/^/    /'

    # Build the optional KEY=VAL args as array elements. They must NOT go through word
    # splitting: `extra` holds a flag AND its value ("--packed-dir /some/path"), so an
    # unquoted ${extra:+EXTRA=$extra} would split into two arguments and the parser would
    # reject the second. One array element = one argv entry, whitespace and all.
    gate_args=("PRESET=$preset")
    # POWER=1 samples board rail power for exactly each run's workload window
    # (power_bench.sh -> power/*.samples), giving a per-batch-size power figure alongside the
    # timings. It degrades to a plain run if xlnx_platformstats is missing.
    sweep_args=("PRESET=$preset" "GEN=$GEN" "REPS=$REPS" "WARMUP=$WARMUP" "POWER=${POWER:-1}")
    [ -n "$model" ] && { gate_args+=("MODEL=$model"); sweep_args+=("MODEL=$model"); }
    [ -n "$tag" ]   && sweep_args+=("TAG=$tag")
    [ -n "$extra" ] && { gate_args+=("ARGS=$extra"); sweep_args+=("EXTRA=$extra"); }

    # Correctness before performance: the gate asserts the engine's int32 accumulators are
    # bit-identical to the CPU reference over every projection shape. A failing gate makes
    # every timing number below meaningless, so skip the sweep rather than record garbage.
    # It runs the CPU path too, so at 2.7B it is slow (minutes) — GATE=0 skips it, but then
    # the only correctness signal left is the sweep's own streams_ok column.
    glog="results/logs/gate_$preset${tag:+_$tag}.log"
    if [ "${GATE:-1}" != "0" ]; then
        echo "  -- gate (CPU == FPGA) --"
        if ! make gate "${gate_args[@]}" > "$glog" 2>&1; then
            echo "!! GATE FAILED — see $glog (skipping sweep)"
            tail -15 "$glog"
            failed="$failed $preset(gate)"
            continue
        fi
        grep -E "GATE|checked" "$glog" | sed 's/^/    /'
    fi

    echo "  -- batch sweep --"
    if ! ./bench_batch.sh "${sweep_args[@]}"; then
        failed="$failed $preset(sweep)"
    fi
done

# Hand the results back to the invoking user — everything above ran as root.
[ -n "${SUDO_USER:-}" ] && chown -R "$SUDO_USER" results 2>/dev/null

echo
echo "############################################################"
if [ -n "$failed" ]; then
    echo "# FAILURES:$failed"
else
    echo "# all sweeps complete"
fi
echo "# CSVs:"
ls -1 results/*.csv 2>/dev/null | sed 's/^/#   /'
echo "############################################################"
[ -z "$failed" ]
