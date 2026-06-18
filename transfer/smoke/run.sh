#!/usr/bin/env bash
# run.sh — run the libmmfree smoke test ON the KRIA.
#
# Requires the k26_mmfree370m_a16 bitstream loaded (same one the a16 bench uses:
# aWidth=16, xDim=32, signed). Override device paths/addresses via the env vars
# below if your board differs. smoke_test finds libmmfree.so via $ORIGIN, so keep
# both files in this directory.
#
#   sudo ./run.sh
set -euo pipefail

CORE_PHYS=${CORE_PHYS:-0xA0010000}
DMA_PHYS=${DMA_PHYS:-0xA0000000}
UIO=${UIO:-/dev/uio4}
ACT=${ACT:-/dev/udmabuf-act}
WT=${WT:-/dev/udmabuf-wt}
OUT=${OUT:-/dev/udmabuf-out}

HERE="$(cd "$(dirname "$0")" && pwd)"
exec "$HERE/smoke_test" "$CORE_PHYS" "$DMA_PHYS" "$UIO" "$ACT" "$WT" "$OUT"
