#!/usr/bin/env bash
# gate_all.sh — run the bit-exact correctness gate for every deployable preset, on the KRIA.
#
#     ./gate_all.sh                 # all three presets  (from ~/transfer, after `make deploy`)
#     ./gate_all.sh mmfree_2_7b     # just one
#
# Each preset: load overlay + driver (sudo; prompts for a password), then `make gate`, which
# checks the engine against the CPU reference bit-exactly. A pass is errs=0 on every shape.

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

    # Reload per preset even when presets share a bitstream: the dtbo also carries udmabuf sizing.
    if ! sudo make deploy-hw PRESET="$p"; then
        echo "!! $p: deploy-hw FAILED — overlay/driver not loaded"
        RESULT[$p]="DEPLOY-FAIL"
        continue
    fi

    # fclk0 sticks across overlay reloads, so print it: a mismatch invalidates the timing claim.
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
    echo "ALL PASS — every preset gated bit-exact against the CPU reference."
else
    echo "SOMETHING FAILED — do NOT ship these bundles."
fi
exit $rc
