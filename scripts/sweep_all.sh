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
# Each spec is  preset:model:tag:extra-runner-flags:backend  (trailing fields optional):
#   preset  — presets/<preset>/ in this bundle; its overlay is loaded before its sweep
#   model   — models/<model>/ to run on it; empty = the preset manifest's own MMFREE_MODEL
#   tag     — suffix for the output filename, to sweep one preset twice under different flags
#   extra   — appended verbatim to the runner flags (e.g. a non-default --packed-dir)
#   backend — fpga (default) | cpu (int8) | cpu-2bit | cpu-1p6. A cpu* spec runs the SAME ladder with the engine
#             out of the loop, so it skips the overlay load AND the gate (there is nothing to
#             gate: the gate compares the engine against the CPU, and here the CPU IS the run).
# Override the default list by passing your own specs as arguments.
#
# WEIGHT FORMAT IS A PROPERTY OF THE PRESET, not a flag: packedWeights is an RTL parameter
# (the unpack gearbox only exists when it is set), so the 2-bit and 1.6-bit arms are two
# different bitstreams and each carries the right blob dir in its manifest — transfer/Makefile
# derives packed/ vs packed-1p6/ from MMFREE_PACKED_WEIGHTS. So "sweep both formats" just means
# listing both presets, and no --packed-dir override is needed (an explicit one would in fact
# be a way to feed a bitstream the WRONG blob; the runner hard-errors on the mismatch).
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
HERE="$(pwd)"

[ "$(id -u)" -eq 0 ] || { echo "ERROR: run under sudo (the overlay load and /dev/mem both need root): sudo ./sweep_all.sh" >&2; exit 1; }

GEN=${GEN:-64}
REPS=${REPS:-3}
WARMUP=${WARMUP:-1}

# Default sweep set: the three deployable presets, grouped by model size, each preceded by
# the CPU arm it is quoted against. That is the full production comparison — one FPGA row and
# one CPU row per checkpoint — and nothing else.
#
# The CPU arm here is int8 (BACKEND=cpu), which is the A53's FASTEST configuration and
# therefore the FAIR denominator for a speedup claim. Running the CPU at the FPGA's own
# weight encodings is a narrower question ("same format on both sides") and it makes the CPU
# look worse: measured on the A53, cpu-2bit is 0.96-1.25x of int8 and cpu-1p6 only 0.52-0.82x,
# because the ternary dot is ALU/issue-bound rather than weight-bandwidth-bound, so unpacking
# costs more than the saved traffic buys. Add those arms explicitly if you want them:
#
#     sudo ./sweep_all.sh "mmfree_2_7b::2bit::cpu-2bit" "mmfree_2_7b::1p6::cpu-1p6"
#
# The exhaustive 12-arm matrix that established all of the above (2-bit vs packed bitstreams,
# 200 vs 250 MHz at fixed RTL, matched CPU arms at every encoding) lives on the `exploration`
# branch together with the ablation presets it swept.
#
# CPU arms need no overlay, no CMA and no gate, so they run before the size's overlay loads.
# Runtime is a few hours — the 2.7B arms dominate and every FPGA arm gates before it times.
# Subset by passing your own specs; the ordering is just the array order.
DEFAULT_SPECS=(
    # ── 370M ──── maxN=4096, banking only, no DSP accum ──────────────────────
    "mmfree_370m::::cpu"                    # CPU int8 baseline
    "mmfree_370m"                           # FPGA 1.6-bit @250 — 102.63 tok/s at b=6
    # ── 1.3B ──── the 2.7B bitstream with a 1.3B manifest ────────────────────
    "mmfree_1_3b::::cpu"
    "mmfree_1_3b"                           # FPGA 1.6-bit @250 —  55.43 tok/s at b=6
    # ── 2.7B ──── the build-from preset ──────────────────────────────────────
    "mmfree_2_7b::::cpu"
    "mmfree_2_7b"                           # FPGA 1.6-bit @250 —  34.41 tok/s at b=6
)
# POWER PROFILING. POWER=1 wraps every arm in power_bench.sh (xlnx_platformstats rail
# sampling) and costs ~2% decode, so its rows are NOT comparable to POWER=0 rows —
# only compare POWER=1 against POWER=1.
#
#   sudo POWER=1 TAG=pwr ./sweep_all.sh
#
# ⚠️ PASS TAG= on any re-run under changed conditions. Without it the re-run writes to the
# same <preset>_batch_sweep.csv and OVERWRITES the previous one — that is how a whole set of
# 250 MHz rows was lost on 2026-08-04. The tag lands in the CSV filename.
#
# NOTE power_bench.sh names its samples file after the GEOMETRY, not the batch size, so a
# POWER=1 ladder leaves only the LAST batch's rail samples behind. Fine for a b=6 power
# figure; if you want per-batch perf/W, fix the naming there first.

if [ $# -gt 0 ]; then SPECS=("$@"); else SPECS=("${DEFAULT_SPECS[@]}"); fi

mkdir -p results/logs
failed=""
for spec in "${SPECS[@]}"; do
    IFS=: read -r preset model tag extra backend <<< "$spec"
    backend=${backend:-fpga}
    # A global TAG suffixes EVERY arm's per-spec tag. Without this, `TAG=pwr ./sweep_all.sh`
    # was silently a no-op — tag is only the spec's 3rd field — so a POWER=1 re-run wrote to
    # the same <preset>_batch_sweep.csv as the POWER=0 run and destroyed it. That is exactly
    # how the 250 MHz rows were lost on 2026-08-04. Suffix, not replace, so a
    # spec that already carries a tag (e.g. ":ctl") keeps its identity: ctl_pwr.
    if [ -n "${TAG:-}" ]; then tag="${tag:+${tag}_}${TAG}"; fi
    # A bad 5th field is almost always an `extra` that contained a colon and leaked into
    # backend. Fail loudly rather than silently sweeping the wrong thing.
    case "$backend" in
        fpga|cpu|cpu-2bit|cpu-1p6|cpu-tq1) ;;
        *) echo "!! bad backend '$backend' in spec '$spec' (fpga|cpu|cpu-2bit|cpu-1p6; note extra must not contain ':')" >&2
           failed="$failed $preset(spec)"; continue ;;
    esac
    # cpu* arms never touch the engine: no overlay, no CMA, and nothing for the gate to
    # compare (the gate's reference IS the CPU path).
    hw=1; [ "$backend" = "fpga" ] || hw=0
    echo
    echo "############################################################"
    echo "# $preset  backend=$backend ${model:+model=$model }${tag:+tag=$tag}"
    echo "############################################################"

    if [ "$hw" -eq 1 ]; then
    # CMA pre-flight. u-dma-buf allocates udmabuf-wt from CMA at overlay-bind time, so a
    # preset whose WT working set exceeds what CMA can hand out fails in the driver with a
    # missing /dev/udmabuf-wtN rather than anything self-explanatory. Report the arithmetic
    # up front. Note the budget is CmaFree PLUS whatever the currently-loaded overlay is about
    # to release, and CMA needs each WT slice CONTIGUOUS, so fragmentation can fail an
    # allocation that this headline number says fits. A reboot is the reliable reset.
    # Default every term to 0 before the arithmetic: an EMPTY $(( )) operand is a fatal
    # expansion error that aborts the whole for-loop, not just this iteration — so a host
    # without CMA in /proc/meminfo would silently drop every remaining spec.
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
    else
        echo "    (backend=$backend: no overlay, no CMA — the engine is not used)"
    fi

    # Build the optional KEY=VAL args as array elements. They must NOT go through word
    # splitting: `extra` holds a flag AND its value ("--packed-dir /some/path"), so an
    # unquoted ${extra:+EXTRA=$extra} would split into two arguments and the parser would
    # reject the second. One array element = one argv entry, whitespace and all.
    # The gate runs the CPU reference alongside the FPGA for every projection, so at 2.7B the
    # default --gen 32 costs ~10 min. A geometry/drain bug shows on the first token (every
    # projection and layer is invoked), so GATE_GEN trims it without giving up the signal.
    # GATE_GEN=32 restores the old thorough behavior; GATE=0 skips the gate entirely.
    GATE_GEN=${GATE_GEN:-8}
    gate_args=("PRESET=$preset")
    # POWER=1 samples board rail power for exactly each run's workload window
    # (power_bench.sh -> power/*.samples), giving a per-batch-size power figure alongside the
    # timings. It degrades to a plain run if xlnx_platformstats is missing.
    #
    # DEFAULT IS 0, AND THAT MATTERS: sampling costs ~2% decode. Measured 2026-07-30, same
    # preset/blob/overlay/session — POWER=1 gave 26.88 tok/s and POWER=0 gave 27.44 (-2.04%).
    # This default used to be 1 while every other bench script defaulted to 0, so numbers
    # from this script were silently ~2% below numbers from those, and comparing across them
    # manufactured a regression that did not exist. Pass POWER=1 explicitly when you actually
    # want rail data, and only compare POWER=1 against POWER=1.
    sweep_args=("PRESET=$preset" "GEN=$GEN" "REPS=$REPS" "WARMUP=$WARMUP" "POWER=${POWER:-0}")
    [ -n "$model" ] && { gate_args+=("MODEL=$model"); sweep_args+=("MODEL=$model"); }
    [ -n "$tag" ]   && sweep_args+=("TAG=$tag")
    [ -n "$extra" ] && sweep_args+=("EXTRA=$extra")
    [ "$backend" != "fpga" ] && sweep_args+=("BACKEND=$backend")
    # Gate ARGS = the gate's own --gen PLUS any spec extra (e.g. a non-default --packed-dir,
    # which the gate must honour or it validates a blob this sweep never runs). ARGS lands
    # after RUNNER_ARTIFACTS and the runner takes the LAST occurrence, so extra still wins.
    gargs="${GATE_GEN:+--gen $GATE_GEN}${extra:+ $extra}"
    [ -n "$gargs" ] && gate_args+=("ARGS=$gargs")

    # Correctness before performance: the gate asserts the engine's int32 accumulators are
    # bit-identical to the CPU reference over every projection shape. A failing gate makes
    # every timing number below meaningless, so skip the sweep rather than record garbage.
    # It runs the CPU path too, so at 2.7B it is slow (minutes) — GATE=0 skips it, but then
    # the only correctness signal left is the sweep's own streams_ok column.
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

    # Release this preset's CMA as soon as its sweep is done, rather than leaving it pinned
    # until the next deploy. Two reasons: a following CPU arm has no reason to run with
    # hundreds of MiB still reserved, and the next preset's pre-flight then reports the TRUE
    # free figure instead of one that only becomes true later. Skipped after the last spec is
    # harmless — the next deploy_kria.sh unloads anyway.
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
