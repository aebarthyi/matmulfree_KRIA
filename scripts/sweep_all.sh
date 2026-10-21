#!/usr/bin/env bash
# sweep_all.sh — deploy each bitstream in turn and run its batch sweep, unattended.
#
#   cd ~/transfer && sudo ./sweep_all.sh [spec ...]     # whole script under sudo (overlay, /dev/mem)
#
# Results: results/*.csv (one per arm, one row per batch, chowned back to you); logs in results/logs/.
#
# Each spec is  preset:model:tag:extra-runner-flags:backend  (trailing fields optional):
#   preset  — presets/<preset>/ in this bundle; its overlay is loaded before its sweep
#   model   — models/<model>/ to run on it; empty = the preset manifest's own MMFREE_MODEL
#   tag     — suffix for the output filename, to sweep one preset twice under different flags
#   extra   — appended verbatim to the runner flags (e.g. a non-default --packed-dir)
#   backend — fpga (default) | cpu (int8) | cpu-2bit | cpu-1p6. cpu* runs the same ladder with
#             the engine out of the loop, skipping the overlay load and the gate.
# Override the default list by passing your own specs as arguments.
# Weight format is a property of the preset (an RTL parameter); the bundle Makefile derives the
# matching blob dir from the manifest, so no --packed-dir override is needed.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

[ "$(id -u)" -eq 0 ] || { echo "ERROR: run under sudo (the overlay load and /dev/mem both need root): sudo ./sweep_all.sh" >&2; exit 1; }

GEN=${GEN:-64}
REPS=${REPS:-3}
WARMUP=${WARMUP:-1}

# Default: the three deployable presets, each preceded by its CPU int8 arm (the A53's fastest
# configuration, hence the fair speedup denominator). Add matched-encoding CPU arms explicitly:
#     sudo ./sweep_all.sh "mmfree_2_7b::2bit::cpu-2bit" "mmfree_2_7b::1p6::cpu-1p6"
# CPU arms need no overlay, CMA or gate, so they run before their size's overlay loads.
DEFAULT_SPECS=(
    # ── 370M ──── maxN=4096 ─────────────────────────────────────────────────
    "mmfree_370m::::cpu"                    # CPU int8 baseline
    "mmfree_370m"                           # FPGA 1.6-bit @250
    # ── 1.3B ──── the 2.7B bitstream with a 1.3B manifest ────────────────────
    "mmfree_1_3b::::cpu"
    "mmfree_1_3b"                           # FPGA 1.6-bit @250
    # ── 2.7B ──── the build-from preset ──────────────────────────────────────
    "mmfree_2_7b::::cpu"
    "mmfree_2_7b"                           # FPGA 1.6-bit @250
)
# POWER=1 (rail sampling via power_bench.sh) costs ~2% decode: only compare POWER=1 with POWER=1.
# Pass TAG= on any re-run under changed conditions, or it OVERWRITES the previous CSV:
#   sudo POWER=1 TAG=pwr ./sweep_all.sh

if [ $# -gt 0 ]; then SPECS=("$@"); else SPECS=("${DEFAULT_SPECS[@]}"); fi

mkdir -p results/logs
failed=""
for spec in "${SPECS[@]}"; do
    IFS=: read -r preset model tag extra backend <<< "$spec"
    backend=${backend:-fpga}
    # A global TAG suffixes each spec's own tag (ctl -> ctl_pwr), so a re-run never overwrites.
    if [ -n "${TAG:-}" ]; then tag="${tag:+${tag}_}${TAG}"; fi
    # A bad 5th field is usually an `extra` that contained a colon: fail loudly.
    case "$backend" in
        fpga|cpu|cpu-2bit|cpu-1p6|cpu-tq1) ;;
        *) echo "!! bad backend '$backend' in spec '$spec' (fpga|cpu|cpu-2bit|cpu-1p6; note extra must not contain ':')" >&2
           failed="$failed $preset(spec)"; continue ;;
    esac
    # cpu* arms never touch the engine: no overlay, no CMA, no gate.
    hw=1; [ "$backend" = "fpga" ] || hw=0
    echo
    echo "############################################################"
    echo "# $preset  backend=$backend ${model:+model=$model }${tag:+tag=$tag}"
    echo "############################################################"

    if [ "$hw" -eq 1 ]; then
    # CMA pre-flight: an oversized WT set fails in the driver as a missing /dev/udmabuf-wtN.
    # Budget = CmaFree + what the loaded overlay will release; slices must be contiguous, so
    # fragmentation can still fail it (reboot to reset). Every term defaults to 0: an empty
    # $(( )) operand would abort the whole loop.
    wt_sz=$(grep -hE '^UDMABUF_WT_SZ=' "presets/$preset/preset.env" | cut -d= -f2)
    n_dma=$(grep -hE '^NUM_DMA=' "presets/$preset/preset.env" | cut -d= -f2)
    cma_free_kb=$(grep -E '^CmaFree:' /proc/meminfo | awk '{print $2}')
    need_mib=$(( $(printf '%d' "${wt_sz:-0}") * ${n_dma:-0} / 1048576 ))
    cma_free_mib=$(( ${cma_free_kb:-0} / 1024 ))
    held_mib=$(cat /sys/class/u-dma-buf/udmabuf-wt*/size 2>/dev/null | awk '{s+=$1} END{printf "%d", s/1048576}')
    held_mib=${held_mib:-0}
    echo "    cma: need ${need_mib} MiB WT | free ${cma_free_mib} MiB + ${held_mib} MiB held by the loaded overlay"
    [ "$need_mib" -gt "$(( cma_free_mib + held_mib ))" ] && \
        echo "    !! WARNING: WT need exceeds the CMA budget — expect udmabuf-wt allocation to fail. Reboot first, or free CMA (zocl/pynq)."

    # deploy_kria.sh also sets fclk0 (an overlay reload does not reset the fabric clock).
    if ! ./deploy_kria.sh "$preset" > "results/logs/deploy_$preset.log" 2>&1; then
        echo "!! deploy-hw FAILED — see results/logs/deploy_$preset.log"
        tail -20 "results/logs/deploy_$preset.log"
        failed="$failed $preset(deploy)"
        continue
    fi
    grep -E "fclk0|udmabuf|WARN|ERROR" "results/logs/deploy_$preset.log" | head -12 | sed 's/^/    /'
    else
        echo "    (backend=$backend: no overlay, no CMA — the engine is not used)"
    fi

    # Optional KEY=VAL args as array elements: `extra` holds a flag AND its value, so it must
    # not word-split. GATE_GEN=8 keeps the gate fast (a geometry/drain bug shows on the first
    # token); GATE_GEN=32 is thorough, GATE=0 skips the gate.
    GATE_GEN=${GATE_GEN:-8}
    gate_args=("PRESET=$preset")
    # POWER defaults to 0 here (sampling costs ~2% decode); pass POWER=1 for rail data.
    sweep_args=("PRESET=$preset" "GEN=$GEN" "REPS=$REPS" "WARMUP=$WARMUP" "POWER=${POWER:-0}")
    [ -n "$model" ] && { gate_args+=("MODEL=$model"); sweep_args+=("MODEL=$model"); }
    [ -n "$tag" ]   && sweep_args+=("TAG=$tag")
    [ -n "$extra" ] && sweep_args+=("EXTRA=$extra")
    [ "$backend" != "fpga" ] && sweep_args+=("BACKEND=$backend")
    # The gate gets the spec's extra too, so it validates the blob the sweep runs (ARGS comes
    # last and the runner keeps the last occurrence of a flag).
    gargs="${GATE_GEN:+--gen $GATE_GEN}${extra:+ $extra}"
    [ -n "$gargs" ] && gate_args+=("ARGS=$gargs")

    # Correctness before performance: a failing bit-exact gate makes every timing meaningless.
    glog="results/logs/gate_$preset${tag:+_$tag}.log"
    if [ "$hw" -eq 1 ] && [ "${GATE:-1}" != "0" ]; then
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

    # Release this preset's CMA now, so the next arm's pre-flight sees the true free figure.
    if [ "$hw" -eq 1 ]; then
        before=$(awk '/^CmaFree:/{print $2}' /proc/meminfo)
        xmutil unloadapp >/dev/null 2>&1
        sleep 1
        after=$(awk '/^CmaFree:/{print $2}' /proc/meminfo)
        echo "    cma: released $(( (${after:-0} - ${before:-0}) / 1024 )) MiB (CmaFree now $(( ${after:-0} / 1024 )) MiB)"
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
