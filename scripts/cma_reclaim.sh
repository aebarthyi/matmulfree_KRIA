#!/usr/bin/env bash
# cma_reclaim.sh — free the CMA that PYNQ/XRT holds, so the big 2-bit presets can allocate.
#
#   cd ~/transfer && sudo ./cma_reclaim.sh              # reclaim, then report
#   cd ~/transfer && sudo ./cma_reclaim.sh --report      # report only, change nothing
#
# udmabuf-wt comes from CMA at overlay bind, each per-port slice CONTIGUOUS; a 2-bit 2.7B preset
# needs 624 MiB. The stock PYNQ/XRT stack (dfx-mgr's boot-time `pynq` overlay, zocl, jupyter)
# holds ~477 MiB this project never uses. This stops jupyter, unloads the pynq app and removes
# zocl; dfx-mgr stays up (xmutil needs it). All of it returns on reboot (or: systemctl start
# jupyter). Run BEFORE a sweep. For a bigger reservation see cma_boot_raise.sh.
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

# 1. jupyter first (a live notebook can hold CMA of its own). Stop, don't disable.
if systemctl is-active --quiet jupyter.service; then
    echo "== stopping jupyter.service (not disabling) =="
    systemctl stop jupyter.service || echo "  WARN: stop failed"
fi

# 2. Unload the pynq app (what zocl is bound to). Our overlay is left alone; deploy_kria.sh
#    reloads it.
if [ -d /sys/kernel/config/device-tree/overlays/pynq ]; then
    echo "== unloading the pynq overlay =="
    xmutil unloadapp pynq >/dev/null 2>&1 || xmutil unloadapp >/dev/null 2>&1 || \
        echo "  WARN: xmutil unloadapp did not report success"
fi

# 3. zocl last: it only unloads at refcnt 0. Best-effort.
if lsmod | grep -q '^zocl'; then
    ref=$(cat /sys/module/zocl/refcnt 2>/dev/null || echo "?")
    echo "== removing zocl (refcnt=$ref) =="
    modprobe -r zocl 2>/dev/null || rmmod zocl 2>/dev/null || \
        echo "  WARN: zocl still held (refcnt=$(cat /sys/module/zocl/refcnt 2>/dev/null)) — skipping"
fi

echo "== after =="; report

# Restate the budget in the units the sweep's pre-flight uses.
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
