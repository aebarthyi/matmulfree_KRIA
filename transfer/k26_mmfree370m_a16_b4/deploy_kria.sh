#!/usr/bin/env bash
#
# deploy_kria.sh — run ON the KRIA, from inside the transfer/ directory.
#
# Installs the core_bench overlay app where xmutil expects it, ensures the
# PL clock isn't gated by the kernel, and (re)loads the app:
#
#   1. /lib/firmware/xilinx/core_bench/{core_bench.bit.bin,core_bench.dtbo,shell.json}
#   2. add clk_ignore_unused to the kernel cmdline if missing (PL clock
#      gating causes bus errors / hard hangs on PL MMIO — needs reboot once)
#   3. xmutil unloadapp + loadapp core_bench
#
# Usage:  sudo ./deploy_kria.sh

set -euo pipefail

APP=core_bench
FW_DIR=/lib/firmware/xilinx/$APP
HERE="$(cd "$(dirname "$0")" && pwd)"

[ "$(id -u)" -eq 0 ] || { echo "ERROR: run with sudo." >&2; exit 1; }

for f in $APP.bit.bin $APP.dtbo shell.json; do
    [ -f "$HERE/$f" ] || { echo "ERROR: $HERE/$f missing." >&2; exit 1; }
done

# ─── 1. Install firmware files ───────────────────────────────────────────
mkdir -p "$FW_DIR"
cp "$HERE/$APP.bit.bin" "$HERE/$APP.dtbo" "$HERE/shell.json" "$FW_DIR/"
echo "==> Installed $FW_DIR/{$APP.bit.bin, $APP.dtbo, shell.json}"

# ─── 2. PL clock gating fix ──────────────────────────────────────────────
# The overlay's generic-uio node lists pl0_ref but never claims it; without
# clk_ignore_unused the kernel disables it ~30 s after boot — every PL MMIO
# access then bus-errors or hangs the CPU.
REBOOT_NEEDED=0
if ! grep -qw clk_ignore_unused /proc/cmdline; then
    CMDLINE_FILE=""
    for c in /boot/firmware/cmdline.txt /boot/cmdline.txt; do
        [ -f "$c" ] && { CMDLINE_FILE="$c"; break; }
    done
    if [ -n "$CMDLINE_FILE" ]; then
        if ! grep -qw clk_ignore_unused "$CMDLINE_FILE"; then
            cp "$CMDLINE_FILE" "$CMDLINE_FILE.bak"
            sed -i '1 s/$/ clk_ignore_unused/' "$CMDLINE_FILE"
            echo "==> Added clk_ignore_unused to $CMDLINE_FILE (backup: $CMDLINE_FILE.bak)"
        fi
        REBOOT_NEEDED=1
    else
        echo "WARNING: clk_ignore_unused not in /proc/cmdline and no cmdline.txt found." >&2
        echo "         Add it to the U-Boot bootargs manually, then reboot." >&2
    fi
else
    echo "==> clk_ignore_unused already active"
fi

# ─── 3. Load the app ─────────────────────────────────────────────────────
xmutil unloadapp >/dev/null 2>&1 || true
xmutil loadapp "$APP"

if [ "$REBOOT_NEEDED" -eq 1 ]; then
    echo
    echo "# PL clocks are still gated for this boot. REBOOT NOW, then run:"
    echo "#   sudo xmutil unloadapp; sudo xmutil loadapp $APP"
fi
echo
echo "# Sanity check — MM2S_DMASR low half, expect halted bit set, no bus error:"
echo "#   sudo devmem2 0xA0000004 h   # expect 0x1 (DMASR.Halted)"
echo "# CAUTION: devmem2 'w' reads 64 bits on arm64 (unsigned long) — it SIGBUSes"
echo "# at any address not 8-byte aligned, even on healthy hardware. Use 'h'/'b',"
echo "# or 'w' only at 8-byte-aligned addresses, or busybox devmem (true 32-bit)."
