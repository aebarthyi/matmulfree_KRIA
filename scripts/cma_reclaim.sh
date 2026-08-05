#!/usr/bin/env bash
# cma_reclaim.sh — free the CMA that PYNQ/XRT holds, so the big 2-bit presets can allocate.
#
# WHY. u-dma-buf allocates udmabuf-wt from CMA when the overlay binds, and each per-port slice
# must be CONTIGUOUS. The board boots with cma=1000M, but a 2-bit 2.7B preset needs 624 MiB
# (4 x 0x09c22000). Measured 2026-07-29: CmaTotal 1000 MiB, CmaFree 23 MiB, our own overlay
# holding 499 MiB — so ~477 MiB is held by something that is NOT ours. That something is the
# PYNQ/XRT stack: dfx-mgr loads a `pynq` overlay at boot, zocl binds to it
# (fpga-full:zyxclmm_drm), and jupyter.service sits on top. We use NONE of it — this project's
# path is xmutil overlay + u-dma-buf + UIO.
#
# Reclaiming ~477 MiB is ~4.8x the ~100 MiB actually short, so this is the cheap fix. Raising
# cma= in boot.scr (see cma_boot_raise.sh) is the persistent alternative and is NOT needed just
# to clear this shortfall.
#
# WHAT IT DOES NOT DO. It does not touch dfx-mgr.service — deploy_kria.sh drives the overlay
# with `xmutil unloadapp/loadapp`, which talks to dfx-mgr, so stopping it would break every
# deploy. Only the pynq *app*, jupyter, and the zocl module are touched.
#
# REVERSIBLE. Everything here comes back on reboot (dfx-mgr reloads pynq, jupyter is still
# enabled). To undo without rebooting: sudo systemctl start jupyter.
#
#   cd ~/transfer && sudo ./cma_reclaim.sh              # reclaim, then report
#   cd ~/transfer && sudo ./cma_reclaim.sh --report      # report only, change nothing
#
# Run this BEFORE sweep_all.sh, then let deploy_kria.sh load our overlay fresh.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
[ "$(id -u)" -eq 0 ] || { echo "ERROR: needs root (module + overlay operations): sudo ./cma_reclaim.sh" >&2; exit 1; }

report() {
    local tot free
    tot=$(( $(grep -E '^CmaTotal:' /proc/meminfo | awk '{print $2}') / 1024 ))
    free=$(( $(grep -E '^CmaFree:'  /proc/meminfo | awk '{print $2}') / 1024 ))
    local held
    held=$(cat /sys/class/u-dma-buf/udmabuf-wt*/size 2>/dev/null | awk '{s+=$1} END{printf "%d", s/1048576}')
    held=${held:-0}
    printf "  CmaTotal %4d MiB | CmaFree %4d MiB | udmabuf-wt (ours) %4d MiB | other %4d MiB\n" \
        "$tot" "$free" "$held" "$(( tot - free - held ))"
    echo "  overlays: $(ls /sys/kernel/config/device-tree/overlays/ 2>/dev/null | tr '\n' ' ')"
}

echo "== before =="; report

if [ "${1:-}" = "--report" ]; then exit 0; fi

# 1. jupyter first — it imports pynq, and a live notebook can hold CMA buffers of its own.
#    Stop only; leave it enabled so a reboot restores the board's stock behaviour.
if systemctl is-active --quiet jupyter.service; then
    echo "== stopping jupyter.service (not disabling) =="
    systemctl stop jupyter.service || echo "  WARN: stop failed"
fi

# 2. Unload the pynq app. This is what zocl is bound to; unbinding is what actually returns
#    the memory. Our own overlay is deliberately left alone — deploy_kria.sh does
#    `xmutil unloadapp` + `loadapp` itself, so it will be reloaded cleanly right after.
if [ -d /sys/kernel/config/device-tree/overlays/pynq ]; then
    echo "== unloading the pynq overlay =="
    xmutil unloadapp pynq >/dev/null 2>&1 || xmutil unloadapp >/dev/null 2>&1 || \
        echo "  WARN: xmutil unloadapp did not report success"
fi

# 3. zocl last: it only unloads once nothing holds it (refcnt 0), which is why the app has to
#    go first. Best-effort — a nonzero refcnt here just means something still has it open, and
#    the run can still proceed if step 2 freed enough.
if lsmod | grep -q '^zocl'; then
    ref=$(cat /sys/module/zocl/refcnt 2>/dev/null || echo "?")
    echo "== removing zocl (refcnt=$ref) =="
    modprobe -r zocl 2>/dev/null || rmmod zocl 2>/dev/null || \
        echo "  WARN: zocl still held (refcnt=$(cat /sys/module/zocl/refcnt 2>/dev/null)) — skipping"
fi

echo "== after =="; report

# Restate the goal in the same units the sweep's pre-flight uses, so it is obvious whether this
# was enough without having to re-derive the arithmetic.
free_now=$(( $(grep -E '^CmaFree:' /proc/meminfo | awk '{print $2}') / 1024 ))
held_now=$(cat /sys/class/u-dma-buf/udmabuf-wt*/size 2>/dev/null | awk '{s+=$1} END{printf "%d", s/1048576}')
held_now=${held_now:-0}
budget=$(( free_now + held_now ))
echo
echo "# CMA budget for the next overlay: ${budget} MiB (CmaFree + what our loaded overlay releases)"
if [ "$budget" -ge 624 ]; then
    echo "# >= 624 MiB — the 2-bit 2.7B presets should allocate. Run sweep_all.sh now."
else
    echo "# < 624 MiB — still short for 2-bit 2.7B. Options: reboot and re-run this;"
    echo "#   or raise the ceiling persistently with ./cma_boot_raise.sh 1536M."
    echo "#   NOTE: CMA also needs each 156 MiB slice CONTIGUOUS, so a fragmented 624 MiB can"
    echo "#   still fail even when this total looks sufficient — a reboot is the reliable reset."
fi
