#!/usr/bin/env bash
# gate_all.sh — run the correctness gate for every deployable preset, on the KRIA.
#
# WHY THIS EXISTS. The 2026-08-09 rebuild swapped in the banked output memory, which
# renames OutMemUltra and therefore produces genuinely new bitstreams for all three
# presets. Nothing about that change is visible to simulation beyond what CoreSpec
# already covers (read semantics and latency are identical by construction), so the
# only thing that can promote "build-verified" to "shipped" is a gate on real hardware
# at temperature. Until this passes, the WNS column in CLAUDE.md carries a ⚠️.
#
# It is a SCRIPT rather than something the assistant runs because `deploy-hw` needs
# sudo and the board prompts for a password interactively.
#
# RUN IT ON THE BOARD, from ~/transfer:
#     ./gate_all.sh                 # all three presets
#     ./gate_all.sh mmfree_2_7b     # just one
#
# Deploy first from the build server:  make deploy BOARD=ubuntu@<kria>
#
# Each preset does: load overlay + driver (sudo), then `make gate`, which checks the
# engine against the CPU reference bit-exactly. A pass is errs=0 on every shape.

set -uo pipefail        # NOT -e: a failing preset must not skip the ones after it

cd "$(dirname "${BASH_SOURCE[0]}")"

PRESETS=("$@")
if [ ${#PRESETS[@]} -eq 0 ]; then
    PRESETS=(mmfree_370m mmfree_1_3b mmfree_2_7b)
fi

for p in "${PRESETS[@]}"; do
    [ -d "presets/$p" ] || { echo "!! no bundle at presets/$p — did 'make deploy' run?" >&2; exit 1; }
done

echo "=== gating: ${PRESETS[*]}"
echo "=== each preset reloads the overlay, so this is not fast; expect a few minutes each"
echo

declare -A RESULT
for p in "${PRESETS[@]}"; do
    echo "############################################################"
    echo "### $p"
    echo "############################################################"

    # The overlay carries the bitstream AND the udmabuf sizing, so it must be reloaded
    # per preset even when two presets share a bitstream (1.3B's resident weight set is
    # half of 2.7B's, and the dtbo is patched for it).
    if ! sudo make deploy-hw PRESET="$p"; then
        echo "!! $p: deploy-hw FAILED — overlay/driver not loaded"
        RESULT[$p]="DEPLOY-FAIL"
        continue
    fi

    # ⚠️ fclk0 sticks at the previously-loaded rate across overlay reloads, so a build
    # constrained at one rung can silently RUN at another. All three presets are 250 here,
    # but print it: a mismatch invalidates the timing claim the gate is meant to support.
    if [ -r /sys/class/fclk/fclk0/set_rate ]; then
        echo "--- fclk0: $(cat /sys/class/fclk/fclk0/set_rate 2>/dev/null || echo '?') Hz (expect 250000000)"
    fi

    if make gate PRESET="$p"; then
        RESULT[$p]="PASS"
    else
        RESULT[$p]="FAIL"
    fi
    echo
done

echo "############################################################"
echo "### SUMMARY"
echo "############################################################"
rc=0
for p in "${PRESETS[@]}"; do
    printf "  %-14s %s\n" "$p" "${RESULT[$p]}"
    [ "${RESULT[$p]}" = "PASS" ] || rc=1
done
echo
if [ $rc -eq 0 ]; then
    echo "ALL PASS — the banked-memory bitstreams are gated. Drop the ⚠️ from the"
    echo "CLAUDE.md preset table, and re-measure tok/s if you intend to quote it."
else
    echo "SOMETHING FAILED — do NOT ship. The previous (legacy-memory) bundles are the"
    echo "known-good fallback; 2.7B's 200 MHz rung is the fallback below that."
fi
exit $rc
