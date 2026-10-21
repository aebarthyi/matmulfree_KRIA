#!/usr/bin/env bash
# scripts/build_queue.sh — run a list of presets through build_all.sh back to back.
#
#   ./scripts/build_queue.sh mmfree_370m mmfree_2_7b     # explicit list
#   JOBS=16 ./scripts/build_queue.sh mmfree_2_7b          # pass through to build_all.sh
#
# Serial (Vivado wants the whole machine); a failed preset does not stop the queue. Logs go to
# reports/build_queue/<preset>.log, one PASS/FAIL + post-route WNS line per preset to summary.txt.
# REPACKAGE defaults to 1 here: a queue usually follows an RTL change, and a changed SV file set
# synth-fails against the cached IP. Set REPACKAGE=0 when only preset parameters changed.
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

    # Post-route WNS from build_all.sh's "<preset>_a16_..._<clk>MHz.rpt". Both anchors matter:
    # "_a16_" stops a longer preset name matching, and "MHz.rpt" excludes the .timing.rpt
    # (which has no "WNS:" line). Newest wins, so a re-run at a new clock reads the new file.
    rpt=$(ls -t "$REPO_ROOT/reports/${preset}_a16_"*MHz.rpt 2>/dev/null | head -1)
    wns=""
    [ -n "$rpt" ] && wns=$(grep -hoE 'WNS:[[:space:]]*-?[0-9]+\.[0-9]+' "$rpt" \
                           | grep -oE -- '-?[0-9]+\.[0-9]+' | head -1)
    # The CONSTRAINED rate, not the requested one (an off-rung request snaps down).
    clk=""
    [ -n "$rpt" ] && clk=$(sed -n 's/.*-> \([0-9.]*\) MHz CONSTRAINED.*/\1/p' "$rpt" | head -1)
    printf '%-42s %-10s %5dm %3ds  WNS=%-7s @%s MHz\n' \
        "$preset" "$status" $((elapsed / 60)) $((elapsed % 60)) "${wns:-?}" "${clk:-?}" >> "$SUMMARY"
    echo "=== [$(date -Is)] $preset $status (${elapsed}s)"
done

echo "### build_queue done $(date -Is)" >> "$SUMMARY"
echo "=== queue complete; summary:"
cat "$SUMMARY"
