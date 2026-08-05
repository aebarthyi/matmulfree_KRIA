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

# Default sweep set (2026-08-03): GROUPED BY MODEL SIZE, ascending, and within each size the
# four arms in this order — CPU 2-bit, CPU 1.6-bit, FPGA 2-bit, FPGA 1.6-bit. So each size's
# whole story lands together instead of being split across the run, and the two CPU arms are
# measured before the size's overlay is loaded (they need no CMA at all).
#
# The CPU arms use the SAME weight encodings as the FPGA arms (mmfree/tq1.hpp): cpu-2bit is
# 4 trits/byte = 2.0 bits/weight, cpu-1p6 is llama.cpp's TQ1_0 = 1.625. Both are bit-identical
# to the CPU's native int8 path, so the arms differ only in speed.
#
#   NOTE: int8 is NOT in this list, and it is the CPU's FASTEST configuration (A53-measured:
#   2-bit 0.96-1.25x of int8, 1.6-bit 0.52-0.82x — the ternary dot is ALU-bound, so packing
#   costs more than the saved traffic buys). The four arms below answer "same format on both
#   sides"; if you also want the CPU's best-case denominator, add e.g.
#       "k26_mmfree370m_a16_b6_s8::int8::cpu"
#   which is the plain int8 arm tagged so it does not collide.
#
#   370M PAIR: now matched. Both 370M arms are dsp=0/s8=8 @250 (k26_mmfree370m_a16_b6_s8 was
#   built for exactly this), so all three sizes isolate the weight encoding and nothing else.
#
# Runtime is many hours — the 2.7B arms dominate and every FPGA arm gates before it times.
# Subset by passing your own specs; the ordering is just the array order.
DEFAULT_SPECS=(
    # ── 370M ────────────────────────────────────────────────────────────────
    "k26_mmfree370m_a16_b6_s8::::cpu-2bit"          # CPU  2.0   bits/weight
    "k26_mmfree370m_a16_b6_s8::::cpu-1p6"           # CPU  1.625 bits/weight
    "k26_mmfree370m_a16_b6_s8"                      # FPGA 2-bit    (250 MHz, s8)
    "k26_mmfree370m_a16_b6_packed"                  # FPGA 1.6-bit  (250 MHz, s8)
    # ── 1.3B ────────────────────────────────────────────────────────────────
    "k26_mmfree1_3b_a16_b6_dsp_s8_200::::cpu-2bit"
    "k26_mmfree1_3b_a16_b6_dsp_s8_200::::cpu-1p6"
    "k26_mmfree1_3b_a16_b6_dsp_s8_200"              # FPGA 2-bit    (200 MHz, dsp+s8)
    "k26_mmfree1_3b_a16_b6_dsp_s8_packed"           # FPGA 1.6-bit  (200 MHz, dsp+s8)
    # ── 2.7B ────────────────────────────────────────────────────────────────
    "k26_mmfree2_7b_a16_b6_dsp_s8_200::::cpu-2bit"
    "k26_mmfree2_7b_a16_b6_dsp_s8_200::::cpu-1p6"
    "k26_mmfree2_7b_a16_b6_dsp_s8_200"              # FPGA 2-bit    (200 MHz, dsp+s8)
    "k26_mmfree2_7b_a16_b6_dsp_s8_packed"           # FPGA 1.6-bit  (200 MHz, dsp+s8)
    # ── 250 MHz arms (2026-08-04): the first maxN=8192 B6 builds to CLOSE 250 ──
    # colBankSize=4 + actRamStyle=block/cascade 2 fixed the TWO paths that failed 0.005 ns
    # apart (see NEXT_STEPS §BB). WNS +0.042 (2-bit) / +0.025 (packed), both @250.000 MHz.
    # The 1.3B pair is the SAME two bitstreams via clone_preset_hw.sh — identical RTL, 1.3B
    # manifest. Paired with the 200 MHz arms above these give the clock axis at fixed RTL,
    # which is the comparison the earlier "230 MHz" mislabel made impossible.
    # GATE THESE FIRST: colBankSize=4 and the ActMem swap are simulation-verified only and
    # have never run on hardware.
    "k26_mmfree1_3b_a16_b6_dsp_s4_acb2"             # FPGA 2-bit    (250 MHz)
    "k26_mmfree1_3b_a16_b6_dsp_s4_acb2_packed"      # FPGA 1.6-bit  (250 MHz)
    "k26_mmfree2_7b_a16_b6_dsp_s4_acb2"             # FPGA 2-bit    (250 MHz)
    "k26_mmfree2_7b_a16_b6_dsp_s4_acb2_packed"      # FPGA 1.6-bit  (250 MHz)
)
# POWER PROFILING. POWER=1 wraps every arm in power_bench.sh (xlnx_platformstats rail
# sampling) and costs ~2% decode, so its rows are NOT comparable to the POWER=0 rows already
# in reports/sweeps/ — only compare POWER=1 against POWER=1.
#
#   sudo POWER=1 TAG=pwr ./sweep_all.sh
#
# ⚠️ PASS TAG=. Without it a POWER=1 re-run writes to the same
# <preset>_batch_sweep.csv as the POWER=0 run and OVERWRITES it — exactly how the 250 MHz
# rows were lost on 2026-08-04 (NEXT_STEPS §A). The tag lands in the CSV filename.
# The 1.3B/2.7B arms were named "_230" until 2026-08-04 and the same directories are still
# on the board under the old name. They are the SAME bitstreams — 230 is not a PL0_REF rung,
# so those builds were always constrained at 199.998 MHz (their own dtbos ask the kernel for
# 199998001 Hz) and, after the deploy snap-down fix, always ran there. Only the label moved.
# Re-run `make deploy` from the build server before this sweep or the "_200" presets will not
# exist on the board.

if [ $# -gt 0 ]; then SPECS=("$@"); else SPECS=("${DEFAULT_SPECS[@]}"); fi

mkdir -p results/logs
failed=""
for spec in "${SPECS[@]}"; do
    IFS=: read -r preset model tag extra backend <<< "$spec"
    backend=${backend:-fpga}
    # A global TAG suffixes EVERY arm's per-spec tag. Without this, `TAG=pwr ./sweep_all.sh`
    # was silently a no-op — tag is only the spec's 3rd field — so a POWER=1 re-run wrote to
    # the same <preset>_batch_sweep.csv as the POWER=0 run and destroyed it. That is exactly
    # how the 250 MHz rows were lost on 2026-08-04 (NEXT_STEPS §A). Suffix, not replace, so a
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
    # preset/blob/overlay/session, ladder split both times — POWER=1 gave 26.88 tok/s and
    # POWER=0 gave 27.44 (-2.04%). This default used to be 1 while bench_batch.sh and
    # sweep_split_k.sh default to 0, so numbers from this script were silently ~2% below
    # numbers from those, and comparing across them manufactured a regression that did not
    # exist. Pass POWER=1 explicitly when you actually want rail data, and only compare
    # POWER=1 against POWER=1.
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
