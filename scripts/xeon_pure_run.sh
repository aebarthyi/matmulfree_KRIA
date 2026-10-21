#!/usr/bin/env bash
# xeon_pure_run.sh — the Xeon arm with host variability pinned (diagnostic wrapper).
#
#   sudo -v && ./scripts/xeon_pure_run.sh diag     # ~40 min: 370M cpu-2bit, full ladder, 10+20 threads
#   sudo -v && ./scripts/xeon_pure_run.sh full     # a full replacement data set
#
# Wraps final_sweep_xeon.sh (all its guards still run), pinning the cpufreq governor to
# `performance` (restored on exit) and binding OpenMP threads to physical cores, to test whether
# the batch-to-batch wobble in a Xeon ladder comes from the host. Compare with xeon_wobble.py.
# Turbo stays ON: slowing the baseline would flatter the board (NO_TURBO=1 is for A/B only).
# diag runs both 10 and 20 threads so the cost of capping at 10 is measured, not assumed.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

MODE="${1:-diag}"
case "$MODE" in diag|full) ;; *) echo "usage: $0 [diag|full]" >&2; exit 2 ;; esac

STAMP="$(date +%Y%m%d)"
NO_TURBO="${NO_TURBO:-0}"

# ── Governor: set it, and ALWAYS put it back ────────────────────────────────────────
# Restore from an EXIT trap so an interrupted run never leaves the box pinned; the original
# governor is recorded per CPU rather than assumed uniform.
GOV_WANT=performance
declare -a GOV_WAS=()
CPUS=(/sys/devices/system/cpu/cpu[0-9]*/cpufreq)
TURBO_WAS=""

restore_host() {
    local i=0 c
    for c in "${CPUS[@]}"; do
        [ -w "$c/scaling_governor" ] && printf '%s' "${GOV_WAS[$i]}" | sudo tee "$c/scaling_governor" >/dev/null 2>&1
        i=$((i + 1))
    done
    [ -n "$TURBO_WAS" ] && printf '%s' "$TURBO_WAS" | sudo tee /sys/devices/system/cpu/intel_pstate/no_turbo >/dev/null 2>&1
    echo "# host restored: governor=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null)" \
         "no_turbo=$(cat /sys/devices/system/cpu/intel_pstate/no_turbo 2>/dev/null || echo n/a)"
}

if ! sudo -n true 2>/dev/null; then
    cat >&2 <<'EOF'
ERROR: this needs a live sudo ticket to pin the CPU governor, and pinning the governor is
the whole point of the run — proceeding without it would produce another schedutil data
set under a filename that claims otherwise.

  sudo -v && ./scripts/xeon_pure_run.sh <mode>
EOF
    exit 1
fi

for c in "${CPUS[@]}"; do GOV_WAS+=("$(cat "$c/scaling_governor")"); done
grep -qw "$GOV_WANT" /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors || {
    echo "ERROR: '$GOV_WANT' not in $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors)" >&2
    exit 1
}
trap restore_host EXIT
for c in "${CPUS[@]}"; do printf '%s' "$GOV_WANT" | sudo tee "$c/scaling_governor" >/dev/null; done

if [ "$NO_TURBO" = "1" ]; then
    TURBO_WAS="$(cat /sys/devices/system/cpu/intel_pstate/no_turbo)"
    printf '1' | sudo tee /sys/devices/system/cpu/intel_pstate/no_turbo >/dev/null
fi

# Verify rather than assume — a tee into a sysfs file can silently no-op.
gov_now="$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
[ "$gov_now" = "$GOV_WANT" ] || { echo "ERROR: governor is '$gov_now', wanted '$GOV_WANT'" >&2; exit 1; }
uniq_gov="$(cat /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_governor | sort -u | tr '\n' ' ')"
[ "$(echo $uniq_gov)" = "$GOV_WANT" ] || { echo "ERROR: governors not uniform: $uniq_gov" >&2; exit 1; }

# ── OpenMP ──────────────────────────────────────────────────────────────────────────
# OMP_WAIT_POLICY is deliberately UNSET: `passive` turns each of this decoder's tens of
# thousands of short parallel regions into a futex sleep/wake (+64% on 370M). Don't "fix" it.
# spread/cores matches bench_host_cpu.sh, set explicitly so the governor is the only change.
export OMP_PROC_BIND="${OMP_PROC_BIND:-spread}"
export OMP_PLACES="${OMP_PLACES:-cores}"

if [ "$MODE" = "diag" ]; then
    # The full ladder (the thing under test) on 370M, its widest excursion; both thread
    # counts; two passes suffice (pass-to-pass agreement <1% vs a 10-25% effect).
    export PASSES=2 THREAD_SET="10 20" \
           MODELS="ridger/MMfreeLM-370M" BACKENDS="cpu-2bit" \
           OUT_ROOT="$REPO_ROOT/reports/host_cpu/pure_diag_$STAMP"
else
    export PASSES="${PASSES:-5}" THREAD_SET="${THREAD_SET:-10}" \
           OUT_ROOT="${OUT_ROOT:-$REPO_ROOT/reports/host_cpu/pure_$STAMP}"
fi

mkdir -p "$OUT_ROOT"
# Provenance beside the data: turbo and OMP_WAIT_POLICY are not CSV columns.
{
    echo "date=$(date -Is)"
    echo "mode=$MODE"
    echo "governor=$GOV_WANT  (was: ${GOV_WAS[0]})"
    echo "no_turbo=$(cat /sys/devices/system/cpu/intel_pstate/no_turbo 2>/dev/null || echo n/a)"
    echo "OMP_WAIT_POLICY=${OMP_WAIT_POLICY:-<unset: libgomp default spin>}"
    echo "OMP_PROC_BIND=$OMP_PROC_BIND"
    echo "OMP_PLACES=$OMP_PLACES"
    echo "THREAD_SET=$THREAD_SET"
    echo "passes=$PASSES"
    echo "cpu=$(lscpu | awk -F: '/Model name/{gsub(/^ +/,"",$2);print $2}')"
} > "$OUT_ROOT/host_state.txt"

echo "############################################################"
echo "# Xeon PURE run — mode=$MODE"
echo "#   governor : $GOV_WANT (was ${GOV_WAS[0]}), no_turbo=$(cat /sys/devices/system/cpu/intel_pstate/no_turbo 2>/dev/null || echo n/a)"
echo "#   omp      : WAIT_POLICY=${OMP_WAIT_POLICY:-<unset: spin>} PROC_BIND=$OMP_PROC_BIND PLACES=$OMP_PLACES"
echo "#   threads  : $THREAD_SET"
echo "#   out      : $OUT_ROOT"
echo "############################################################"

./scripts/final_sweep_xeon.sh
rc=$?

cat <<EOF

# ── compare against the schedutil set ───────────────────────────────────────────────
#   ./scripts/xeon_wobble.py --a reports/host_cpu/final --b $OUT_ROOT
#
# What to look for: the batch-to-batch spread within one (model, encoding) curve. The
# schedutil set runs 10-25%. If the pinned set drops that to a few percent, the wobble was
# the host and this becomes the Xeon arm. If it does not move, it is in the kernel and the
# next place to look is the matmul's thread decomposition, not the box.
EOF
exit $rc
