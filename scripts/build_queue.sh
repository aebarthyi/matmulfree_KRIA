#!/usr/bin/env bash
# scripts/build_queue.sh — run a list of presets through build_all.sh back to back.
#
# Vivado wants the whole machine, so the presets run SERIALLY; each one's full log
# lands in reports/build_queue/<preset>.log and a one-line PASS/FAIL plus the
# post-route WNS goes to reports/build_queue/summary.txt. A preset that fails does
# NOT stop the queue — the point is to leave a long unattended run alone and read
# the summary afterwards.
#
#   ./scripts/build_queue.sh mmfree_370m mmfree_2_7b     # explicit list
#   JOBS=16 ./scripts/build_queue.sh mmfree_2_7b          # pass through to build_all.sh
#
# NOTE on `main`: there are only three deployable presets and 1.3B is a clone of the
# 2.7B bitstream (scripts/clone_preset_hw.sh), so the real queue here is two builds.
# This is mainly a tool for `exploration`, where the ablation presets live.
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

    # Post-route WNS, read from the report build_all.sh leaves in reports/. The file is
    # named "<preset>_a16_x32_..._<clk>MHz.rpt" and contains a line like
    #   "# Target clock: 250 MHz requested -> 250.000 MHz CONSTRAINED   post-route WNS: 0.042 ns"
    # NOTE the "_a16_" anchor is load-bearing: a bare "${preset}*" glob would also match a
    # LONGER preset that extends this name (…_packed vs …_packed_200) and report its WNS.
    # The "MHz.rpt" suffix is equally load-bearing, and was NOT there until 2026-08-04: since
    # build_project.tcl started also writing "<tag>.timing.rpt" (2026-08-03), a "*.rpt" glob
    # matched that too — and because the timing report is written AFTER the utilization one,
    # `ls -t | head -1` picked it every time. Its timing-summary table has a "WNS(ns)" COLUMN
    # rather than a "WNS: <n>" line, so the grep found nothing and every row in this summary
    # silently read "WNS=?" — including the two builds on 2026-08-03/04.
    # Newest matching report wins, so re-running one preset at a new clock reads the new file.
    rpt=$(ls -t "$REPO_ROOT/reports/${preset}_a16_"*MHz.rpt 2>/dev/null | head -1)
    wns=""
    [ -n "$rpt" ] && wns=$(grep -hoE 'WNS:[[:space:]]*-?[0-9]+\.[0-9]+' "$rpt" \
                           | grep -oE -- '-?[0-9]+\.[0-9]+' | head -1)
    # The CONSTRAINED rate, not the requested one — an off-rung PL_CLK_MHZ snaps down, so the
    # request tells you nothing about what the WNS is slack against (see CoreConfig.plClkRungs).
    clk=""
    [ -n "$rpt" ] && clk=$(sed -n 's/.*-> \([0-9.]*\) MHz CONSTRAINED.*/\1/p' "$rpt" | head -1)
    printf '%-42s %-10s %5dm %3ds  WNS=%-7s @%s MHz\n' \
        "$preset" "$status" $((elapsed / 60)) $((elapsed % 60)) "${wns:-?}" "${clk:-?}" >> "$SUMMARY"
    echo "=== [$(date -Is)] $preset $status (${elapsed}s)"
done

echo "### build_queue done $(date -Is)" >> "$SUMMARY"
echo "=== queue complete; summary:"
cat "$SUMMARY"
