#!/usr/bin/env bash
# queue_b8_after_xeon.sh — build the three B8 candidates, but ONLY once the Xeon sweep
# has finished measuring.
#
#   ./scripts/queue_b8_after_xeon.sh            # waits, then builds
#   nohup ./scripts/queue_b8_after_xeon.sh &    # same, detached
#
# WHY THE GATE. Vivado takes every core. The Xeon arm of the final data set runs on this
# same machine, and a build started underneath it would not merely slow the sweep — it
# would silently record the CONTENTION as the model's throughput. bench_host_cpu.sh has a
# load guard that refuses to start above nproc/4, so in practice a concurrent build would
# either corrupt rows already in flight or abort every remaining pass. Either way the data
# set is gone and the 90-minute sweep has to be redone.
#
# So: poll until no bench_host_cpu.sh / mmfree-cli-fpga process remains AND the sweep's
# output is complete, then start. Waiting on the process alone is not enough — a sweep that
# died on the load guard also has no processes, and building on top of a half-finished data
# set means the re-run collides with the build instead.
#
# WHAT IT BUILDS. Two Vivado runs, not three: 1.3B and 2.7B emit byte-identical RTL (same
# maxN/maxM), verified here before the clone rather than assumed, so the 1.3B bundle is
# assembled from the 2.7B bitstream by clone_preset_hw.sh in seconds. 370M is a genuinely
# different geometry (maxN 4096) and needs its own run.
#
#   mmfree_2_7b_b8   B8 + dspAccum + colBankSize=4 + ActMem cascade cap, packed, @250
#   mmfree_1_3b_b8   cloned from the above
#   mmfree_370m_b8   B8 + dspAccum + colBankSize=4, packed, @250 (maxN=4096: no cascade cap)
#
# READING THE RESULT (reports/build_queue/summary.txt):
#   closes 250            -> promote: B8 beats B6 on the engine AND on the host's ceil(b/4)
#                            wave quantization, so it should win outright.
#   misses by < ~0.3 ns   -> rebuild at the 200 rung. Costs ~5%, which B8's upside exceeds.
#   LUT/placement overflow-> the capacity wall is real even with dspAccum; abandon B8.
#   misses by a lot with  -> the 800 spilled accumulator lanes congested the broadcast the
#   utilization in range     way 288 did at B6. Read reports/<tag>.paths.txt before another
#                            build; colBankSize=2 is the next lever.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

XEON_ROOT="${XEON_ROOT:-$REPO_ROOT/reports/host_cpu/final}"
POLL="${POLL:-60}"
MAX_WAIT="${MAX_WAIT:-21600}"          # 6 h, then give up rather than hang forever
MODELS_EXPECTED="${MODELS_EXPECTED:-370M 1.3B 2.7B}"
PASSES_EXPECTED="${PASSES_EXPECTED:-3}"
THREADS_EXPECTED="${THREADS_EXPECTED:-10 20}"
EXPECT_ROWS="${EXPECT_ROWS:-18}"       # backends x batches per pass

# Our own ancestry, captured once. pgrep -f matches any process whose FULL COMMAND LINE
# contains the pattern — including a shell that merely quotes those names. That is not
# hypothetical: this script was launched from a `git commit -m` whose message described the
# gate and therefore contained the literal strings "bench_host_cpu.sh" and
# "final_sweep_xeon.sh", so the gate matched its own grandparent and waited on itself for an
# hour after the sweep had finished. Excluding our ancestors is the fix; matching on word
# boundaries is not, because the message contained the names as whole words too.
MY_CHAIN=" "
_p=$$
while [ "${_p:-1}" -gt 1 ]; do
    MY_CHAIN="$MY_CHAIN$_p "
    _p=$(awk '{print $4}' "/proc/$_p/stat" 2>/dev/null || echo 1)
done

xeon_busy() {
    local pids p
    pids=$(pgrep -f 'bench_host_cpu\.sh|final_sweep_xeon\.sh' 2>/dev/null) || return 1
    for p in $pids; do
        case "$MY_CHAIN" in *" $p "*) continue ;; esac   # us, or something that launched us
        return 0
    done
    return 1
}

xeon_complete() {
    local m t p d n
    for m in $MODELS_EXPECTED; do
        for t in $THREADS_EXPECTED; do
            for p in $(seq 1 "$PASSES_EXPECTED"); do
                d="$XEON_ROOT/$m/t${t}_p${p}/host_cpu_sweep.csv"
                [ -f "$d" ] || return 1
                n=$(( $(wc -l < "$d") - 1 ))
                [ "$n" -ge "$EXPECT_ROWS" ] || return 1
            done
        done
    done
    return 0
}

echo "### waiting for the Xeon sweep before touching Vivado"
echo "###   watching $XEON_ROOT for ${MODELS_EXPECTED// /, } x {$THREADS_EXPECTED}T x $PASSES_EXPECTED passes"
waited=0
while :; do
    if ! xeon_busy && xeon_complete; then
        echo "### Xeon sweep complete and idle after ${waited}s — starting builds $(date -Is)"
        break
    fi
    if [ "$waited" -ge "$MAX_WAIT" ]; then
        if xeon_busy; then
            echo "### GIVING UP: still running after ${waited}s. Not starting Vivado underneath it." >&2
            exit 1
        fi
        echo "### WARNING: idle but INCOMPLETE after ${waited}s — the sweep probably died." >&2
        echo "###          Not building: a re-run would then collide with Vivado. Fix the sweep first." >&2
        exit 1
    fi
    if [ $((waited % 600)) -eq 0 ]; then
        printf '###   [%s] %s, %s (waited %dm)\n' "$(date +%H:%M)" \
            "$(xeon_busy && echo running || echo idle)" \
            "$(xeon_complete && echo complete || echo incomplete)" "$((waited / 60))"
    fi
    sleep "$POLL"; waited=$((waited + POLL))
done

# Let the machine settle so the first synthesis is not competing with the sweep's tail.
sleep 30

echo
echo "### [1/3] Vivado: mmfree_2_7b_b8 (maxN=8192 B8)"
./scripts/build_queue.sh mmfree_2_7b_b8

echo
echo "### [2/3] Vivado: mmfree_370m_b8 (maxN=4096 B8)"
./scripts/build_queue.sh mmfree_370m_b8

echo
echo "### [3/3] clone mmfree_1_3b_b8 from the 2.7B bitstream (no Vivado)"
if [ -d "transfer/presets/mmfree_2_7b_b8" ]; then
    ./mill matmulfree_KRIA.runMain control.EmitCore mmfree_1_3b_b8 generated/mmfree_1_3b_b8 \
        && ./scripts/clone_preset_hw.sh mmfree_2_7b_b8 mmfree_1_3b_b8
else
    echo "### skipped: the 2.7B B8 build produced no bundle, so there is nothing to clone." >&2
    echo "###          See reports/build_queue/mmfree_2_7b_b8.log" >&2
fi

echo
echo "############################################################"
echo "# B8 candidates done $(date -Is)"
echo "############################################################"
grep -E 'mmfree_(370m|1_3b|2_7b)_b8' reports/build_queue/summary.txt 2>/dev/null | tail -6
echo
echo "Utilization + WNS:  reports/*_b8_*MHz.rpt"
echo "Critical paths:     reports/*_b8_*.paths.txt"
echo "For reference, the shipped B6 at 250: LUT 62.49%, DSP 100%, URAM 37.5%, WNS +0.025 ns"
echo "Extrapolated B8 target: ~81% LUT, ~47% reg, ~20% CARRY8, ~49% BRAM, ~50% URAM"
