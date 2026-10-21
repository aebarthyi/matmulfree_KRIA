#!/usr/bin/env bash
# deploy_kria.sh — run ON the KRIA, from the root of the transfer/ bundle.
#
#   sudo ./deploy_kria.sh <preset>        (or: sudo make deploy-hw PRESET=<preset>)
#
# Installs presets/<preset>/ as the core_bench xmutil app, ensures clk_ignore_unused (needs one
# reboot), builds + loads u-dma-buf from udmabuf/, reloads the overlay, sets fclk0 to the built
# rate, and verifies the udmabuf node sizes against the manifest.

set -euo pipefail

APP=core_bench
FW_DIR=/lib/firmware/xilinx/$APP
HERE="$(cd "$(dirname "$0")" && pwd)"

[ "$(id -u)" -eq 0 ] || { echo "ERROR: run with sudo." >&2; exit 1; }

PRESET="${1:-}"
[ -n "$PRESET" ] || { echo "ERROR: usage: sudo ./deploy_kria.sh <preset>   (bundled: $(ls "$HERE/presets" 2>/dev/null | tr '\n' ' '))" >&2; exit 1; }
PRESET_DIR="$HERE/presets/$PRESET"
[ -d "$PRESET_DIR" ] || { echo "ERROR: $PRESET_DIR missing — '$PRESET' is not in this bundle (have: $(ls "$HERE/presets" 2>/dev/null | tr '\n' ' '))." >&2; exit 1; }

for f in $APP.bit.bin $APP.dtbo shell.json; do
    [ -f "$PRESET_DIR/$f" ] || { echo "ERROR: $PRESET_DIR/$f missing." >&2; exit 1; }
done

# ─── 1. Install firmware files ───────────────────────────────────────────
mkdir -p "$FW_DIR"
cp "$PRESET_DIR/$APP.bit.bin" "$PRESET_DIR/$APP.dtbo" "$PRESET_DIR/shell.json" "$FW_DIR/"
echo "==> Installed $FW_DIR/{$APP.bit.bin, $APP.dtbo, shell.json}  (preset $PRESET)"

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
# Load u-dma-buf BEFORE the overlay so it binds the nodes the overlay adds
# (building it needs linux-headers-$(uname -r)).
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
# Overlay removal frees udmabuf CMA asynchronously; loading the next (bigger) preset before it
# settles fails later as a missing /dev/udmabuf-wtN. So wait for CmaFree to stop rising.
cma_free_kib() { awk '/^CmaFree:/{print $2}' /proc/meminfo; }
cma_before=$(cma_free_kib)
echo "==> Unloading any current overlay (CmaFree ${cma_before:-?} kB before)"
if ! xmutil unloadapp 2>&1 | sed 's/^/    /'; then
    echo "    (nothing loaded, or unloadapp reported an error — continuing)"
fi
# Poll until the reclaim settles: two consecutive identical readings, or 10 s.
settled=0
prev=$(cma_free_kib)
for _ in $(seq 1 20); do
    sleep 0.5
    now=$(cma_free_kib)
    if [ "${now:-0}" = "${prev:-0}" ]; then settled=$((settled + 1)); else settled=0; fi
    [ "$settled" -ge 2 ] && break
    prev="$now"
done
cma_after=$(cma_free_kib)
echo "==> CmaFree ${cma_before:-?} -> ${cma_after:-?} kB (reclaimed $(( (${cma_after:-0} - ${cma_before:-0}) / 1024 )) MiB)"

xmutil loadapp "$APP"
# Report what the udmabuf nodes actually hold: a CmaFree delta is unreliable because page
# cache borrows CMA pages and is migrated out concurrently with the allocation.
udma_total_mib=$(cat /sys/class/u-dma-buf/*/size 2>/dev/null | awk '{s+=$1} END{printf "%d", s/1048576}')
echo "==> u-dma-buf now holds ${udma_total_mib:-0} MiB across $(ls /sys/class/u-dma-buf 2>/dev/null | wc -l) node(s); CmaFree $(cma_free_kib) kB"
echo "    (CmaFree also moves with page cache borrowing the CMA region — the authoritative"
echo "     check that this preset got its memory is the per-node verification below.)"

# ─── 4b. Program the PL fabric clock to the built rate ───────────────────
# An overlay reload does NOT reprogram fclk0 (PL0_REF), so a stale rate would stick. Set it to
# the built rate here and read back what was achieved (only discrete rates exist).
FCLK=/sys/devices/platform/fclk0/set_rate
if [ -f "$PRESET_DIR/preset.env" ]; then
    # shellcheck disable=SC1091
    . "$PRESET_DIR/preset.env"
fi
# The dtbo's assigned-clock-rates (derived from the XSA) is the authoritative built rate. The
# manifest can disagree either way (off-rung request, relabelled bundle): trust the dtbo. Needs dtc.
DTBO_HZ=""
if command -v dtc >/dev/null 2>&1 && [ -f "$PRESET_DIR/$APP.dtbo" ]; then
    DTBO_HZ=$(dtc -I dtb -O dts "$PRESET_DIR/$APP.dtbo" 2>/dev/null \
              | sed -n 's/.*assigned-clock-rates = <\(0x[0-9a-f]*\)>.*/\1/p' | head -1)
    [ -n "$DTBO_HZ" ] && DTBO_HZ=$((DTBO_HZ))
fi
if [ -n "$DTBO_HZ" ] && [ -n "${PL_CLK_MHZ:-}" ]; then
    _want_hz=$(awk -v m="$PL_CLK_MHZ" 'BEGIN{printf "%d", m * 1000000}')
    if [ "$DTBO_HZ" -lt $((_want_hz - _want_hz / 50)) ] || [ "$DTBO_HZ" -gt $((_want_hz + _want_hz / 50)) ]; then
        echo "WARN: manifest says PL_CLK_MHZ=$PL_CLK_MHZ but this preset's overlay programs" >&2
        echo "      $DTBO_HZ Hz. The dtbo comes from the XSA, so it is what the design was" >&2
        echo "      actually built for — treat the manifest as the wrong one and rebuild, or" >&2
        echo "      set plClkMhz to the rung the dtbo names. Using the dtbo rate for fclk0." >&2
        PL_CLK_ACT_MHZ=$(awk -v h="$DTBO_HZ" 'BEGIN{printf "%.3f", h / 1000000}')
    fi
fi

# PL_CLK_ACT_MHZ (appended by build_all.sh) is the rate actually constrained; PL_CLK_MHZ is
# only the request, which Vivado snaps DOWN to a rung (230 -> 199.998).
if [ -n "${PL_CLK_ACT_MHZ:-}" ]; then
    if [ "${PL_CLK_ACT_MHZ%%.*}" != "${PL_CLK_MHZ:-}" ]; then
        echo "==> manifest requested ${PL_CLK_MHZ:-?} MHz but the build was CONSTRAINED at" \
             "$PL_CLK_ACT_MHZ MHz — using the constrained rate"
    fi
    PL_CLK_MHZ="$PL_CLK_ACT_MHZ"
fi
# FCLK_MHZ=<n> overrides the rate for this deploy (decimal ok), e.g. FCLK_MHZ=200 to run a build
# on the rung below. Rungs: 300, 250, 200, 166.67, 142.86, 100.
if [ -n "${FCLK_MHZ:-}" ]; then
    PL_CLK_MHZ="$FCLK_MHZ"
    echo "==> fclk override: FCLK_MHZ=$FCLK_MHZ (manifest rate ignored for this deploy)"
fi
if [ -w "$FCLK" ] && [ -n "${PL_CLK_MHZ:-}" ]; then
    # awk, not $(( )): FCLK_MHZ may be fractional (214.3).
    want=$(awk -v m="$PL_CLK_MHZ" 'BEGIN{printf "%d", m * 1000000}')
    if echo "$want" > "$FCLK" 2>/dev/null; then
        got=$(cat "$FCLK" 2>/dev/null)
        # Only discrete PL0_REF rungs exist (300, 250, 200, 166.67, 142.86, 100 MHz) and the driver
        # rounds an in-between request UP, past STA closure. Walk the REQUEST down 5% per round
        # (stepping from the achieved rate would snap straight back) until the achieved rate is
        # at or below the built one.
        tries=0
        req=$want
        while [ -n "$got" ] && [ "$got" -gt $((want + want / 50)) ] && [ "$tries" -lt 8 ]; do
            req=$(( req * 95 / 100 ))              # -5% of the REQUEST each round
            [ "$req" -gt 1000000 ] || break
            echo "$req" > "$FCLK" 2>/dev/null || break
            new=$(cat "$FCLK" 2>/dev/null)
            [ -n "$new" ] || break
            got=$new
            tries=$((tries + 1))
        done
        printf "==> fclk0 set to PL_CLK_MHZ=%s: requested %s Hz, achieved %s Hz%s\n" \
               "$PL_CLK_MHZ" "$want" "$got" \
               "$([ "$tries" -gt 0 ] && echo " (snapped down $tries rung(s) to stay at or below the built rate)")"
        # Below the built rate is only a fault if we did not deliberately snap down.
        if [ -n "$got" ] && [ "$tries" -eq 0 ] && [ "$got" -lt $((want - want / 50)) ]; then
            echo "WARN: fclk0 achieved $got Hz is below the built $want Hz — the design" >&2
            echo "      will run slower than it was timed for (stale/clamped clock)." >&2
        fi
        # Worse: above the built rate, the bitstream's post-route WNS does not apply.
        if [ -n "$got" ] && [ "$got" -gt $((want + want / 50)) ]; then
            echo "WARN: fclk0 achieved $got Hz EXCEEDS the built $want Hz — the fabric is" >&2
            echo "      running FASTER than this bitstream was timing-closed for, so its" >&2
            echo "      post-route WNS does not apply. Requests snap UP to the next" >&2
            echo "      reachable PL0_REF rate (300, 250, 200, 166.7, 142.9, 100), so a" >&2
            echo "      target like 230 or 225 lands on 250. Either close the build at the" >&2
            echo "      reachable rate, or set PL_CLK_MHZ to the rung below (200, not 214 —" >&2
            echo "      there is no 214 rung)." >&2
        fi
    else
        echo "WARN: could not write $want to $FCLK — fabric clock left as-is." >&2
    fi
else
    echo "==> (fclk0 set_rate not writable or PL_CLK_MHZ unset — leaving fabric clock as-is)"
fi

# ─── 5. Verify u-dma-buf nodes match the deployed preset manifest ─────────
# Catches a stale overlay whose smaller udmabufs would silently truncate transfers.
if [ -f "$PRESET_DIR/preset.env" ]; then
    # shellcheck disable=SC1091
    . "$PRESET_DIR/preset.env"
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
    echo "==> (no preset.env in $PRESET_DIR — skipping udmabuf size verification)"
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
