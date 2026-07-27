#!/usr/bin/env bash
# scripts/build_queue.sh — run a list of presets through build_all.sh back to back.
#
# Vivado wants the whole machine, so the presets run SERIALLY; each one's full log
# lands in reports/build_queue/<preset>.log and a one-line PASS/FAIL plus the
# post-route WNS goes to reports/build_queue/summary.txt. A preset that fails does
# NOT stop the queue — the point is to leave a long unattended run alone and read
# the summary afterwards.
#
#   ./scripts/build_queue.sh k26_a k26_b ...      # explicit list
#   JOBS=16 ./scripts/build_queue.sh k26_a        # pass through to build_all.sh
#
# REPACKAGE defaults to 1 here (not build_all.sh's 0): a queue is normally run after
# an RTL change, and a changed SV file set synth-fails as "module not found" against
# the cached IP manifest. Set REPACKAGE=0 to skip re-packaging when only preset
# PARAMETERS changed.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

[ $# -gt 0 ] || { echo "usage: $0 <preset> [preset ...]" >&2; exit 2; }

LOG_DIR="$REPO_ROOT/reports/build_queue"
SUMMARY="$LOG_DIR/summary.txt"
mkdir -p "$LOG_DIR"

export REPACKAGE="${REPACKAGE:-1}"
export JOBS="${JOBS:-$(nproc)}"

{
    echo "### build_queue start $(date -Is)  jobs=$JOBS repackage=$REPACKAGE"
    echo "### queue: $*"
} >> "$SUMMARY"

for preset in "$@"; do
    log="$LOG_DIR/$preset.log"
    echo "=== [$(date -Is)] building $preset -> $log"
    start=$(date +%s)
    if PRESET="$preset" ./scripts/build_all.sh > "$log" 2>&1; then
        status=PASS
    else
        status="FAIL(rc=$?)"
    fi
    elapsed=$(( $(date +%s) - start ))

    # Post-route WNS from the timing summary build_all.sh leaves in reports/. Both the
    # per-preset and the legacy flat name are checked; missing/unparsable -> "?".
    wns=$(grep -hoE '(WNS|Slack)[^-0-9]*(-?[0-9]+\.[0-9]+)' \
            "$REPO_ROOT/reports/${preset}"*timing*.rpt "$REPO_ROOT/reports/"*timing*.rpt 2>/dev/null \
          | grep -oE -- '-?[0-9]+\.[0-9]+' | head -1)
    printf '%-42s %-10s %5dm %3ds  WNS=%s\n' \
        "$preset" "$status" $((elapsed / 60)) $((elapsed % 60)) "${wns:-?}" >> "$SUMMARY"
    echo "=== [$(date -Is)] $preset $status (${elapsed}s)"
done

echo "### build_queue done $(date -Is)" >> "$SUMMARY"
echo "=== queue complete; summary:"
cat "$SUMMARY"
