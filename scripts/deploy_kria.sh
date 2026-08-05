#!/usr/bin/env bash
#
# deploy_kria.sh — run ON the KRIA, from the root of the transfer/ bundle.
#
# Installs the core_bench overlay app where xmutil expects it, ensures the
# PL clock isn't gated by the kernel, and (re)loads the app:
#
#   1. /lib/firmware/xilinx/core_bench/{core_bench.bit.bin,core_bench.dtbo,shell.json}
#   2. add clk_ignore_unused to the kernel cmdline if missing (PL clock
#      gating causes bus errors / hard hangs on PL MMIO — needs reboot once)
#   3. xmutil unloadapp + loadapp core_bench
#
# The HW files for a given preset live in presets/<preset>/; the u-dma-buf driver
# source is shared at udmabuf/. Both are resolved relative to this script's dir, so
# the bundle is fully self-contained.
#
# Usage:  sudo ./deploy_kria.sh <preset>     (e.g. sudo ./deploy_kria.sh mmfree_2_7b)
#         or via the bundle Makefile:  sudo make deploy-hw PRESET=<preset>

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
# CMA RELEASE IS THE FRAGILE STEP, so it is verified rather than assumed. u-dma-buf
# allocates udmabuf-wt from CMA when the overlay BINDS and frees it when the overlay is
# REMOVED — but the removal is not instantaneous, and unloadapp used to run with its output
# and its exit status both discarded. Chaining presets then loaded the next (bigger) overlay
# while the previous one's CMA was still held, and the failure surfaced far downstream as a
# missing /dev/udmabuf-wtN, which reads like a driver or overlay bug rather than "the last
# preset had not let go yet".
#
# So: unload, then WAIT for CmaFree to stop rising before loading. Ascending model order
# (370M -> 1.3B -> 2.7B) is the worst case for this — each step needs a bigger contiguous
# slice than the last, and CMA slices must be physically contiguous, so a late large
# allocation can fail even when the total free figure looks sufficient.
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
# Report what the udmabuf nodes ACTUALLY hold, not a CmaFree delta. CmaFree is not a
# reliable measure of "what this overlay took": the CMA region doubles as backing for
# MOVABLE allocations, so the kernel lends idle CMA pages to page cache and migrates them
# back out on demand. Reading a 0.5-3 GB model.mmfree fills the region with page cache, and
# the migration that frees it again happens CONCURRENTLY with the udmabuf allocation — so the
# before/after difference mixes "allocated by us" with "reclaimed by the kernel" and can
# report a fraction of the true size. (Measured 2026-08-03: a 624 MiB allocation showed as a
# 199 MiB CmaFree delta while every node verified at its full size.)
udma_total_mib=$(cat /sys/class/u-dma-buf/*/size 2>/dev/null | awk '{s+=$1} END{printf "%d", s/1048576}')
echo "==> u-dma-buf now holds ${udma_total_mib:-0} MiB across $(ls /sys/class/u-dma-buf 2>/dev/null | wc -l) node(s); CmaFree $(cma_free_kib) kB"
echo "    (CmaFree also moves with page cache borrowing the CMA region — the authoritative"
echo "     check that this preset got its memory is the per-node verification below.)"

# ─── 4b. Program the PL fabric clock to the built rate ───────────────────
# fclk0 (PL0_REF) drives CoreTop directly, but an overlay reload does NOT
# reprogram it — once an earlier overlay claimed it at some rate it stays pinned,
# so a bitstream STA-closed for a higher clock silently runs at the stale rate
# unless we set it here. PL_CLK_MHZ from the manifest is the rate the design was
# built and timing-closed for. The PS PLL yields only discrete rates, so the
# achieved value may snap to the nearest available — read it back and report it.
FCLK=/sys/devices/platform/fclk0/set_rate
if [ -f "$PRESET_DIR/preset.env" ]; then
    # shellcheck disable=SC1091
    . "$PRESET_DIR/preset.env"
fi
# The overlay itself records the rate: createdts emits the PL clock into the dtbo's
# clocking0 node as `assigned-clock-rates`, in Hz, and the kernel programs it on load. That
# makes the dtbo the AUTHORITATIVE record of what a built bundle runs at — it is derived
# from the XSA, so it carries the snapped rate rather than the request. Checked here because
# it can disagree with the manifest in BOTH directions, and both were seen in practice: a
# manifest naming an off-rung rate the PS IP silently snapped down, and a bundle relabelled
# to a new rate without being rebuilt. Trust the dtbo when they differ.
# Needs dtc; skipped silently if the board has no dtc.
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

# PL_CLK_ACT_MHZ is the rate the bitstream was actually CONSTRAINED at, read off the clock
# object by build_project.tcl and appended to the manifest by build_all.sh. Prefer it over
# PL_CLK_MHZ, which is only the REQUEST: Vivado snaps an unreachable request DOWN to the rung
# below (230 -> 199.998), so a manifest saying 230 describes a 200 MHz build. Confirmed on the
# STA side by scripts/probe_pl_clk.tcl and on this board by the readback below — the rungs are
# the same set either way: 300, 250, 200, 166.67, 142.86, 100.
if [ -n "${PL_CLK_ACT_MHZ:-}" ]; then
    if [ "${PL_CLK_ACT_MHZ%%.*}" != "${PL_CLK_MHZ:-}" ]; then
        echo "==> manifest requested ${PL_CLK_MHZ:-?} MHz but the build was CONSTRAINED at" \
             "$PL_CLK_ACT_MHZ MHz — using the constrained rate"
    fi
    PL_CLK_MHZ="$PL_CLK_ACT_MHZ"
fi
# FCLK_MHZ=<n> overrides the manifest rate for this deploy (accepts a decimal). Used to
# measure a build IN SPEC when its built rate is not itself reachable — e.g. FCLK_MHZ=200 to
# sit on the rung below 250. Reachable rungs: 300, 250, 200, 166.67, 142.86, 100.
if [ -n "${FCLK_MHZ:-}" ]; then
    PL_CLK_MHZ="$FCLK_MHZ"
    echo "==> fclk override: FCLK_MHZ=$FCLK_MHZ (manifest rate ignored for this deploy)"
fi
if [ -w "$FCLK" ] && [ -n "${PL_CLK_MHZ:-}" ]; then
    # awk, not $(( )): FCLK_MHZ may be fractional (214.3).
    want=$(awk -v m="$PL_CLK_MHZ" 'BEGIN{printf "%d", m * 1000000}')
    if echo "$want" > "$FCLK" 2>/dev/null; then
        got=$(cat "$FCLK" 2>/dev/null)
        # PL0_REF is divided down from a fixed PLL, so only discrete rates exist. MEASURED on
        # this board 2026-08-04 and independently confirmed on the STA side the same day by
        # scripts/probe_pl_clk.tcl (which reads ACT_FREQMHZ back out of the PS IP): the rungs
        # are 300, 250, 200, 166.67, 142.86, 100 MHz. (An earlier comment here guessed 1500/N
        # = 250 / 214.29 / 187.5; that was WRONG -- there is no 214 rung. A 230 MHz build
        # snapped down lands on 200 MHz, a 20% cut, not the 14% a 214.29 rung would imply.) A
        # request for anything between two rungs lands on the HIGHER one, so a 230 MHz build
        # is handed a 250 MHz fabric and runs ~8.7% past its STA closure. Walk the request
        # DOWN until the achieved rate is at or below what the build was closed for.
        #
        # The step must reduce the REQUEST, not the achieved rate. The previous version did
        # `next = got - 1MHz`, i.e. it asked for 249 MHz, which snaps straight back up to 250;
        # the "no movement" guard then fired and it gave up after one iteration with tries=0.
        # That is why every 230-labelled preset in the 2026-08-03 sweep still logged
        # "achieved 249999998" with no snap-down. A multiplicative step on the request crosses
        # the gap in one or two iterations (230 -> ask 218.5 -> get 214.29) and terminates
        # because the request is strictly decreasing.
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
        # Only a warning when we did NOT ask for it: after a deliberate snap-down, being below
        # the built rate is the intended, timing-safe outcome, not a fault.
        if [ -n "$got" ] && [ "$tries" -eq 0 ] && [ "$got" -lt $((want - want / 50)) ]; then
            echo "WARN: fclk0 achieved $got Hz is below the built $want Hz — the design" >&2
            echo "      will run slower than it was timed for (stale/clamped clock)." >&2
        fi
        # The OTHER direction, and the worse one: an achieved rate ABOVE the built rate means
        # the fabric is being clocked FASTER than the design was timing-closed for, so STA
        # says nothing about it. This went unnoticed for a long time because only the "too
        # slow" case was checked: every PL_CLK_MHZ=230 preset silently ran at 250 MHz — and
        # 2.7B b4_dsp_s8_packed, closed at 230 with WNS +0.096, measures WNS -0.097 at 250.
        # CAUSE: PL0_REF is a divided PLL output, so only discrete rates exist and 230 is not
        # one of them. What the driver does with an unreachable request is undocumented and
        # NOT a simple round-down -- measured here, 230 and 250 both land on 249999998 Hz.
        # Consequence: "a number below 250" in PL_CLK_MHZ does not give you a slower fabric.
        # The retry loop above tries to walk down to a reachable rate at or below the built
        # one; if you see this warning it could not, so either close the build at the rate the
        # board actually runs, or find the next reachable rung by experiment.
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
# Directly prevents the stale-overlay footgun (an old overlay's smaller udmabuf
# silently truncating the new geometry's transfers). preset.env ships in the
# transfer dir; the overlay's nodes must be at least the manifest's sizes.
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
