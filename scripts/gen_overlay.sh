#!/usr/bin/env bash
# scripts/gen_overlay.sh — convenience wrapper for gen_overlay.tcl.
#
# Usage:
#   ./scripts/gen_overlay.sh <xsa_file> [<out_dir>] [-ip <name>] [-addr <hex>] [-irq <n>]
#
# Requires:
#   - xsct on $PATH (ships with Vivado/Vitis; source /tools/Xilinx/Vivado/<ver>/settings64.sh)
#   - dtc on $PATH (device-tree-compiler, apt-get install device-tree-compiler)
#   - unzip on $PATH

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <xsa_file> [<out_dir>] [-ip <name>] [-addr <hex>] [-irq <n>]" >&2
    exit 1
fi

if ! command -v xsct >/dev/null 2>&1; then
    echo "ERROR: xsct not found on PATH." >&2
    echo "       Source your Vivado/Vitis settings64.sh first, e.g.:" >&2
    echo "         source /tools/Xilinx/Vivado/2024.1/settings64.sh" >&2
    exit 1
fi

if ! command -v dtc >/dev/null 2>&1; then
    echo "ERROR: dtc not found. Install with: sudo apt-get install device-tree-compiler" >&2
    exit 1
fi

exec xsct "$SCRIPT_DIR/gen_overlay.tcl" "$@"
