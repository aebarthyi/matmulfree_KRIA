"""Assemble a real (hardware) MmfreeEngine on the KRIA.

Sequences the chicken-and-egg of the real backend: the lib's resident buffers
must be sized from the packed weights, but registration needs the lib already
open. So: pack -> size -> open -> load blobs -> register. Returns (engine, lib);
keep `lib` alive for the engine's lifetime and call lib.close() when done.

Only importable where libmmfree.so + the udmabufs exist (the board). The math is
identical to the RefBackend path (board-free tested) — only the backend differs.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Dict, Optional, Tuple

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from mmfree_pack import Geometry, WeightPacker, quantize_weight, iter_projections  # noqa: E402

from .ctypes_lib import LibConfig, MmfreeLib  # noqa: E402
from .backend import CtypesBackend  # noqa: E402
from .engine import MmfreeEngine, _Proj  # noqa: E402


def open_real_engine(
    weights: Dict[str, np.ndarray],
    geom: Geometry,
    libcfg: LibConfig,
    blob_dir,
    so_path: str = "libmmfree.so",
    *,
    fuse_ifg: bool = False,
) -> Tuple[MmfreeEngine, MmfreeLib]:
    blob_dir = Path(blob_dir)

    # 1. Pack everything; record (name, entry, s) in compute order.
    packer = WeightPacker(geom)
    recorded = []
    for name, W in iter_projections(weights, fuse_ifg=fuse_ifg):
        codes, s = quantize_weight(W)
        entry = packer.add_quantized(name, codes, s)
        recorded.append((name, entry, s))
    packer.write(blob_dir)

    # 2. Size the resident buffers from the pack.
    max_N = max(e.N for _, e, _ in recorded)
    max_outl = max(geom.n_outputs(e.M) for _, e, _ in recorded)
    libcfg.aWidth, libcfg.xDim = geom.aWidth, geom.xDim
    libcfg.weight_bytes_per_port = packer.total_bytes_per_port
    libcfg.act_bytes_per_port = max_N * geom.portBytes
    libcfg.out_bytes = max_outl * geom.out_lane_bytes(libcfg.maxAcc)
    libcfg.max_proj = len(recorded)

    # 3. Open the lib (sized) and load resident weights once.
    lib = MmfreeLib(so_path).open(libcfg)
    for p in range(geom.numPorts):
        lib.load_weights_file(p, str(blob_dir / f"weights.port{p}.bin"))

    # 4. Register every projection in pack order (ids align with the C side).
    backend = CtypesBackend(lib)
    eng = MmfreeEngine(geom, backend)
    eng._packer = packer
    for name, entry, s in recorded:
        pid = backend.register(entry.byte_offset, entry.N, entry.M)
        eng.proj[name] = _Proj(pid, s, entry.N, entry.M)
    return eng, lib
