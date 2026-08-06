#!/usr/bin/env bash
# xeon_topup_after_b8.sh — take the Xeon arm from 3 passes to 5, once Vivado is done.
#
#   setsid nohup ./scripts/xeon_topup_after_b8.sh > /tmp/xeon_topup.log 2>&1 < /dev/null &
#
# WHY MORE PASSES. Three passes is enough to SEE a perturbed pass but not enough to be robust
# to one. The aggregator's headline is the median, and with n=3 dropping any single pass
# leaves two whose median is their average — so one transient still moves it. Measured on the
# 3-pass data: Xeon 2.7B 2-bit had pass 1 run 8.5% slow while passes 2 and 3 agreed to 0.9%,
# which left four cells whose median shifts >3% under jackknife. At n=5 a single transient
# cannot move the median at all.
#
# Only the Xeon side is done here; the board arms need sudo and are run by hand.
#
# final_sweep_xeon.sh is idempotent per pass directory, so PASSES=5 skips the three that
# already exist and runs 4 and 5. It also settles between passes and re-checks the AVX-512
# build, so nothing about the method drifts between the first three and the last two.
#
# THE GATE. Vivado owns the machine while the B8 candidates build, and a CPU sweep started
# underneath one would measure the contention. Same lesson as queue_b8_after_xeon.sh, and the
# same ancestry trap avoided the same way: pgrep -f matches any process whose full command
# line contains the pattern, including a shell that merely quotes it.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

POLL="${POLL:-60}"
MAX_WAIT="${MAX_WAIT:-14400}"

MY_CHAIN=" "
_p=$$
while [ "${_p:-1}" -gt 1 ]; do
    MY_CHAIN="$MY_CHAIN$_p "
    _p=$(awk '{print $4}' "/proc/$_p/stat" 2>/dev/null || echo 1)
done

vivado_busy() {
    local pids p
    pids=$(pgrep -f 'vivado|build_queue\.sh|queue_b8_after_xeon\.sh' 2>/dev/null) || return 1
    for p in $pids; do
        case "$MY_CHAIN" in *" $p "*) continue ;; esac
        return 0
    done
    return 1
}

echo "### waiting for the B8 Vivado queue to finish before running any CPU measurement"
waited=0
while vivado_busy; do
    if [ "$waited" -ge "$MAX_WAIT" ]; then
        echo "### GIVING UP: Vivado still running after ${waited}s" >&2; exit 1
    fi
    [ $((waited % 600)) -eq 0 ] && printf '###   [%s] Vivado busy (waited %dm)\n' "$(date +%H:%M)" "$((waited / 60))"
    sleep "$POLL"; waited=$((waited + POLL))
done
echo "### Vivado idle after ${waited}s — topping the Xeon arm up to 5 passes $(date -Is)"
sleep 30

PASSES=5 ./scripts/final_sweep_xeon.sh

echo
echo "### done $(date -Is) — re-aggregate with:"
echo "###   ./scripts/final_dataset.py --board ~/results/final --xeon reports/host_cpu/final"
