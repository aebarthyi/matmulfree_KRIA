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

# ─── 3. Ensure the u-dma-buf driver is loaded ────────────────────────────
# Vendored as a submodule (external/udmabuf) and shipped in this transfer dir, so
# the board needs no externally-installed module. Build + insmod it here if it
# isn't already loaded; load it BEFORE the overlay so the driver binds the
# ikwzm,u-dma-buf nodes the overlay adds. Building needs kernel headers
# (linux-headers-$(uname -r)).
if lsmod | grep -qw u_dma_buf; then
    echo "==> u-dma-buf module already loaded"
elif [ -f "$HERE/udmabuf/u-dma-buf.c" ]; then
    KHDR="/lib/modules/$(uname -r)/build"
    if [ ! -d "$KHDR" ]; then
        echo "WARNING: kernel headers missing ($KHDR) — cannot build u-dma-buf." >&2
        echo "         Install with: sudo apt install linux-headers-\$(uname -r)" >&2
        echo "         then re-run this script (or: make -C $HERE/udmabuf && sudo insmod $HERE/udmabuf/u-dma-buf.ko)" >&2
    else
        echo "==> Building u-dma-buf from vendored source ($HERE/udmabuf)"
        # cd in (not make -C): the upstream Makefile uses M=$(PWD), which only
        # resolves correctly when make runs from inside the module directory.
        ( cd "$HERE/udmabuf" && make ) >/dev/null
        insmod "$HERE/udmabuf/u-dma-buf.ko"
        echo "==> Loaded u-dma-buf.ko"
        # Best-effort install so modprobe finds it after a reboot.
        ( cd "$HERE/udmabuf" && make modules_install ) >/dev/null 2>&1 && depmod -a 2>/dev/null || true
    fi
else
    echo "WARNING: no u-dma-buf source in $HERE/udmabuf and module not loaded — " >&2
    echo "         /dev/udmabuf-* will be absent. Rebuild with the external/udmabuf" >&2
    echo "         submodule initialized, or load the module manually." >&2
fi

# ─── 4. Load the app ─────────────────────────────────────────────────────
xmutil unloadapp >/dev/null 2>&1 || true
xmutil loadapp "$APP"

# ─── 5. Verify u-dma-buf nodes match the deployed preset manifest ─────────
# Directly prevents the stale-overlay footgun (an old overlay's smaller udmabuf
# silently truncating the new geometry's transfers). preset.env ships in the
# transfer dir; the overlay's nodes must be at least the manifest's sizes.
if [ -f "$HERE/preset.env" ]; then
    # shellcheck disable=SC1091
    . "$HERE/preset.env"
    udmabuf_size() {            # $1=node name → prints size in bytes, or empty
        local cls
        for cls in u-dma-buf udmabuf; do
            [ -r "/sys/class/$cls/$1/size" ] && { cat "/sys/class/$cls/$1/size"; return; }
        done
    }
    verify_node() {            # $1=node name  $2=want bytes
        local got; got=$(udmabuf_size "$1")
        if [ -z "$got" ]; then
            echo "WARN: udmabuf node '$1' not found in sysfs — overlay didn't create it." >&2
            return 1
        fi
        if [ "$got" -lt "$2" ]; then
            echo "WARN: udmabuf '$1' is $got B but $MMFREE_PRESET needs $2 B — STALE OVERLAY; rebuild + redeploy." >&2
            return 1
        fi
        printf "    %-14s %10s B  (>= %s) OK\n" "$1" "$got" "$2"
    }
    n=${NUM_DMA:-1}
    act=$((UDMABUF_ACT_SZ)); wt=$((UDMABUF_WT_SZ)); out=$((UDMABUF_OUT_SZ))
    echo "==> Verifying u-dma-buf nodes against preset '$MMFREE_PRESET' (NUM_DMA=$n)"
    rc=0
    if [ "$n" -eq 1 ]; then
        verify_node udmabuf-act "$act" || rc=1
        verify_node udmabuf-wt  "$wt"  || rc=1
    else
        i=0; while [ "$i" -lt "$n" ]; do
            verify_node "udmabuf-act$i" "$act" || rc=1
            verify_node "udmabuf-wt$i"  "$wt"  || rc=1
            i=$((i + 1))
        done
    fi
    verify_node udmabuf-out "$out" || rc=1
    [ "$rc" -eq 0 ] && echo "==> udmabuf sizes OK" \
                    || echo "WARN: udmabuf verification found problems (see above)."
else
    echo "==> (no preset.env in transfer dir — skipping udmabuf size verification)"
fi

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
