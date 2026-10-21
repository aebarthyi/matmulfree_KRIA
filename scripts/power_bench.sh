#!/usr/bin/env bash
# scripts/power_bench.sh — board-side power monitor around a benchmark workload.
#
# Staged into the bundle by build_all.sh; used by the board Makefile's bench/run verbs. Samples
# rail power with xlnx_platformstats for exactly the workload window.
#
#   power_bench.sh <manifest> <mode> <batch> <cmd> [args...]
#     manifest  preset.env (for the geometry tag in the log name; may be "-")
#     mode      workload label folded into the log name (bench|run|…)
#     batch     effective batch for the tag, or "-" to read MMFREE_BATCH from the manifest
#     cmd…      the workload to run (its stdout/stderr pass through UNTOUCHED, so
#               `make bench | tee x.csv` still works — we only write files + stderr)
#
# Env overrides:
#   POWER_INTERVAL   seconds between samples          (default 1)
#   POWER_ARGS       xlnx_platformstats args to sample (default "-p -n 1")
#   POWER_DIR        directory for the logs           (default ./power)
#
# Output (under $POWER_DIR, named by the CoreConfig geometry + mode):
#   <tag>.samples   raw timestamped xlnx_platformstats output (ground truth)
#   <tag>.log       concise summary: elapsed + per-rail avg/peak power
#
# Without xlnx_platformstats the workload still runs, unmonitored (with a warning).
set -uo pipefail

MANIFEST="${1:?usage: power_bench.sh <manifest> <mode> <batch> <cmd> [args...]}"; shift
MODE="${1:?missing <mode>}"; shift
BATCH_ARG="${1:?missing <batch>}"; shift
# Remaining argv is the workload command.

INTERVAL="${POWER_INTERVAL:-1}"
POWER_ARGS="${POWER_ARGS:--p -n 1}"
POWER_DIR="${POWER_DIR:-power}"

manifest_get() {  # manifest_get KEY DEFAULT
    local v=""
    [ -f "$MANIFEST" ] && v="$(grep -E "^$1=" "$MANIFEST" 2>/dev/null | head -n1 | cut -d= -f2)"
    printf '%s' "${v:-$2}"
}

# Log name: same geometry scheme as the Vivado resource report, plus the mode.
PRESET="$(manifest_get MMFREE_PRESET preset)"
A="$(manifest_get MMFREE_AWIDTH x)"
X="$(manifest_get MMFREE_XDIM x)"
N="$(manifest_get MMFREE_MAXN x)"
M="$(manifest_get MMFREE_MAXM x)"
B="$BATCH_ARG"; [ "$B" = "-" ] && B="$(manifest_get MMFREE_BATCH x)"
TAG="${PRESET}_a${A}_x${X}_b${B}_n${N}_m${M}_${MODE}"

# No monitor available → run the workload plainly so benchmarking is never blocked.
if ! command -v xlnx_platformstats >/dev/null 2>&1; then
    echo "power_bench: xlnx_platformstats not found — running '$MODE' WITHOUT power monitoring." >&2
    exec "$@"
fi

mkdir -p "$POWER_DIR"
RAW="$POWER_DIR/$TAG.samples"
LOG="$POWER_DIR/$TAG.log"
: > "$RAW"

# ─── start the sampler right before the workload ─────────────────────────────
# A timestamped loop (not one streaming call) is portable across xlnx_platformstats versions
# and lets the summary bound the exact workload window.
sample_loop() {
    while :; do
        printf '@%s\n' "$(date +%s.%N)"
        xlnx_platformstats $POWER_ARGS 2>/dev/null
        sleep "$INTERVAL"
    done
}
sample_loop >> "$RAW" &
SAMPLER=$!
# Make sure the sampler dies even if we're interrupted mid-workload.
trap 'kill "$SAMPLER" 2>/dev/null' EXIT INT TERM

T0="$(date +%s.%N)"
"$@"
STATUS=$?
T1="$(date +%s.%N)"

# ─── stop the sampler the instant the workload returns ───────────────────────
kill "$SAMPLER" 2>/dev/null
wait "$SAMPLER" 2>/dev/null
trap - EXIT INT TERM

# ─── summarize: elapsed + per-rail avg/peak power (normalized to mW) ──────────
# Best-effort parse of "<label> : <value> <mW|W>" lines; the raw file is kept as
# ground truth for anything the parser doesn't recognize.
awk -v t0="$T0" -v t1="$T1" '
    function trim(s){ gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
    /^@/ { next }                      # sample timestamps — skip in the parse
    {
        ci = index($0, ":"); if (ci == 0) next
        label = trim(substr($0, 1, ci-1))
        rest  = substr($0, ci+1)
        if (!match(rest, /-?[0-9]+(\.[0-9]+)?[ \t]*(mW|W)/)) next
        tok  = substr(rest, RSTART, RLENGTH)
        unit = (tok ~ /mW/) ? "mW" : "W"
        gsub(/[^0-9.\-]/, "", tok); val = tok + 0
        if (unit == "W") val *= 1000                 # normalize to mW
        sum[label] += val; cnt[label]++
        if (val > max[label]) max[label] = val
    }
    END {
        printf "elapsed_s   %.2f\n", (t1 - t0)
        any = 0
        for (l in sum) {
            printf "%-28s avg %9.1f mW   peak %9.1f mW   (n=%d)\n", l, sum[l]/cnt[l], max[l], cnt[l]
            any = 1
        }
        if (!any) print "(no power lines parsed — check POWER_ARGS; see the .samples file)"
    }
' "$RAW" | tee "$LOG" >&2

echo "power_bench: summary -> $LOG   raw -> $RAW" >&2
exit "$STATUS"
