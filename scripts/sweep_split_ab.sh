#!/usr/bin/env bash
# sweep_split_ab.sh — measure what column-tile splitting buys, as a controlled A/B.
#
# Same bitstream, same model, same binary, same everything — the ONLY difference between the
# two sweeps is MMFREE_SPLIT_CHUNKS, so the delta is attributable to the split and nothing
# else. Splitting runs a big projection as several COMPUTEs over slices of its output column
# tiles sharing one activation LOAD, which lets chunk j-1's readback run on the CPU while the
# engine computes chunk j (readback was ~28% of engine time and pure dead time).
#
#   cd ~/transfer && sudo ./sweep_split_ab.sh
#
# Produces, per preset: *_nosplit_summary.csv and *_split_summary.csv (+ _ops/_phases), plus
# a combined split_ab_summary.csv. The phase sheets are where the mechanism shows: readback_ms
# should fall sharply in the split run while compute_ms stays put.
#
# GATE=1 (default) runs the bit-exact CPU-vs-FPGA gate on the SPLIT path first — do not skip
# it on the first run. A wrong chunk boundary streams weights from the wrong offset and yields
# plausible-looking garbage; the gate is what catches that, and any tok/s number from an
# ungated split run is worthless.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
[ "$(id -u)" -eq 0 ] || { echo "ERROR: run under sudo: sudo ./sweep_split_ab.sh" >&2; exit 1; }

GEN=${GEN:-64}
REPS=${REPS:-3}

# preset:model:extra-runner-flags. The packed 2.7B is the headline case (it is the best build
# and its N=2560 shapes permit 1.6-bit chunk boundaries); the 2-bit sibling is the control,
# where every boundary is legal so the split gets full coverage.
SPECS=(
    "k26_mmfree2_7b_a16_b4_dsp_s8_packed::--packed-dir $(pwd)/models/ridger/MMfreeLM-2.7B/packed-1p6"
    "k26_mmfree2_7b_a16_b4_dsp_s8:"
)
[ $# -gt 0 ] && SPECS=("$@")

mkdir -p results/logs
failed=""
for spec in "${SPECS[@]}"; do
    IFS=: read -r preset model extra <<< "$spec"
    echo
    echo "############################################################"
    echo "# $preset  (split A/B)"
    echo "############################################################"

    if ! ./deploy_kria.sh "$preset" > "results/logs/deploy_$preset.log" 2>&1; then
        echo "!! deploy-hw FAILED — see results/logs/deploy_$preset.log"
        tail -20 "results/logs/deploy_$preset.log"; failed="$failed $preset(deploy)"; continue
    fi
    grep -E "fclk0|WARN|ERROR" "results/logs/deploy_$preset.log" | head -6 | sed 's/^/    /'

    common=("PRESET=$preset" "GEN=$GEN" "REPS=$REPS")
    [ -n "$model" ] && common+=("MODEL=$model")
    [ -n "$extra" ] && common+=("EXTRA=$extra")

    if [ "${GATE:-1}" != "0" ]; then
        echo "  -- gate on the SPLIT path (bit-exact CPU vs FPGA) --"
        gargs=("PRESET=$preset"); [ -n "$model" ] && gargs+=("MODEL=$model")
        [ -n "$extra" ] && gargs+=("ARGS=$extra")
        glog="results/logs/gate_split_$preset.log"
        if ! make gate "${gargs[@]}" > "$glog" 2>&1; then
            echo "!! GATE FAILED — column-tile splitting is producing wrong results."
            echo "   Skipping the sweep; see $glog"
            tail -15 "$glog"; failed="$failed $preset(gate)"; continue
        fi
        grep -E "GATE|checked" "$glog" | sed 's/^/    /'
    fi

    for mode in nosplit split; do
        echo "  -- $mode --"
        args=("${common[@]}" "TAG=$mode")
        [ "$mode" = nosplit ] && args+=("ENV=MMFREE_SPLIT_CHUNKS=0")
        ./bench_batch.sh "${args[@]}" || failed="$failed $preset($mode)"
    done
done

[ -n "${SUDO_USER:-}" ] && chown -R "$SUDO_USER" results 2>/dev/null
command -v python3 >/dev/null 2>&1 && \
    python3 ./split_sweep_csv.py --combine results/split_ab results/*_batch_sweep.csv >/dev/null 2>&1

echo
echo "############################################################"
[ -n "$failed" ] && echo "# FAILURES:$failed" || echo "# split A/B complete"
echo "# compare:  results/split_ab_summary.csv   (and _phases.csv for the mechanism)"
echo "############################################################"
[ -z "$failed" ]
