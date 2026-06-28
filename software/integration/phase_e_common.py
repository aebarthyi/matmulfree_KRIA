"""Shared helpers for the Phase-E validation scripts.

Extracted from the (retired) phase_e_validate.py so phase_e_layer.py keeps working
after the PyTorch reference model was removed from the matmulfreellmCPU submodule.
Pure numpy + the in-repo RefBackend — no torch / mmfreelm dependency.
"""

from __future__ import annotations

import sys
import threading
import time
from pathlib import Path

import numpy as np

# bridge + packer live alongside this script.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from mmfree_bridge.backend import RefBackend  # noqa: E402


def _meminfo():
    """Return (rss, avail, swap_free) in GiB, read from /proc. No deps (psutil-free)."""
    def _kb(path, key):
        try:
            with open(path) as f:
                for line in f:
                    if line.startswith(key):
                        return int(line.split()[1])  # value is in kB
        except OSError:
            pass
        return 0
    g = 1024.0 * 1024.0  # kB -> GiB
    return (_kb("/proc/self/status", "VmRSS:") / g,
            _kb("/proc/meminfo", "MemAvailable:") / g,
            _kb("/proc/meminfo", "SwapFree:") / g)


def _mem(label):
    rss, avail, swap = _meminfo()
    print(f"    [mem] {label}: RSS={rss:.2f}GiB  MemAvail={avail:.2f}GiB  SwapFree={swap:.2f}GiB",
          flush=True)


def _start_mem_monitor(interval=2.0):
    """Daemon thread: print a line each time RSS sets a new high-water mark."""
    state = {"peak": 0.0, "stop": False}

    def _run():
        while not state["stop"]:
            rss, avail, swap = _meminfo()
            if rss > state["peak"] + 0.05:  # only log meaningful (>50 MiB) jumps
                state["peak"] = rss
                print(f"    [mem-hi] RSS={rss:.2f}GiB  MemAvail={avail:.2f}GiB  "
                      f"SwapFree={swap:.2f}GiB", flush=True)
            time.sleep(interval)

    threading.Thread(target=_run, daemon=True).start()
    return state


class Int8RefBackend(RefBackend):
    """RefBackend that stores ternary codes as int8 (1/4 the RAM of int32).

    Exact same arithmetic — codes are in {-1,0,1}, and the (int8 @ int32) matmul
    is promoted to int64 by numpy so the accumulator never overflows.
    """

    def note_codes(self, proj_id: int, codes_MN: np.ndarray) -> None:
        self._codes[proj_id] = np.asarray(codes_MN, dtype=np.int8)

    def bitlinear(self, proj_id: int, x_int16: np.ndarray) -> np.ndarray:
        codes = self._codes[proj_id]
        if codes is None:
            raise RuntimeError(f"Int8RefBackend: no codes for proj {proj_id}")
        x = np.asarray(x_int16, dtype=np.int64)
        return (codes.astype(np.int64) @ x).astype(np.int32)
