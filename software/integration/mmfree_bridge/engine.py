"""MmfreeEngine — runs MMfreeLM BitLinear projections on a backend.

Packs a checkpoint's weights once, registers each projection with the backend,
and exposes project(name, x_norm) -> y, applying the int16 quant/dequant around
the backend's raw integer matmul. Backend-agnostic: a RefBackend instance gives
a board-free software twin; a CtypesBackend drives the real engine.

The torch patch supplies x_norm = RMSNorm(x) (each FusedBitLinear owns its norm);
this layer is torch-free and operates on numpy.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Optional

import numpy as np

# mmfree_pack is a sibling package under software/integration.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from mmfree_pack import Geometry, WeightPacker, quantize_weight, iter_projections  # noqa: E402

from .quant import quantize_act_int16, dequantize  # noqa: E402


@dataclass
class _Proj:
    proj_id: int
    s: float
    N: int
    M: int


class MmfreeEngine:
    def __init__(self, geom: Geometry, backend):
        self.geom = geom
        self.backend = backend
        self.proj: Dict[str, _Proj] = {}
        self._packer = WeightPacker(geom)

    # ---- build ----

    @classmethod
    def from_weights(cls, weights: Dict[str, np.ndarray], geom: Geometry, backend,
                     *, fuse_ifg: bool = False) -> "MmfreeEngine":
        """Quantize + register every projection. Codes are reused for the packer
        (real-backend blobs) and the backend's note_codes (ref simulation)."""
        eng = cls(geom, backend)
        for name, W in iter_projections(weights, fuse_ifg=fuse_ifg):
            codes, s = quantize_weight(W)                 # (M, N) ternary, scalar s
            entry = eng._packer.add_quantized(name, codes, s)
            pid = backend.register(entry.byte_offset, entry.N, entry.M)
            backend.note_codes(pid, codes)
            eng.proj[name] = _Proj(pid, s, entry.N, entry.M)
        return eng

    # ---- sizing for the real backend's resident buffers ----

    @property
    def weight_bytes_per_port(self) -> int:
        return self._packer.total_bytes_per_port

    @property
    def max_N(self) -> int:
        return max((p.N for p in self.proj.values()), default=0)

    @property
    def max_n_outputs(self) -> int:
        return max((self.geom.n_outputs(p.M) for p in self.proj.values()), default=0)

    def write_blobs(self, out_dir, prefix: str = "weights"):
        """Emit per-port blobs + manifest (for the CtypesBackend to load)."""
        return self._packer.write(out_dir, prefix)

    # ---- inference ----

    def project(self, name: str, x_norm: np.ndarray) -> np.ndarray:
        """y = BitLinear(name) applied to already-normalized x_norm.

        x_norm is (N,) for single-token decode or (T, N) for a batch of tokens;
        returns (M,) or (T, M) float32.
        """
        p = self.proj[name]
        x = np.asarray(x_norm, dtype=np.float64)
        single = x.ndim == 1
        rows = x[None, :] if single else x
        if rows.shape[1] != p.N:
            raise ValueError(f"{name}: x has inner dim {rows.shape[1]}, expected N={p.N}")

        out = np.empty((rows.shape[0], p.M), dtype=np.float64)
        for r in range(rows.shape[0]):
            x_int, a_scale = quantize_act_int16(rows[r])
            acc = self.backend.bitlinear(p.proj_id, x_int)    # int32 (M,)
            out[r] = dequantize(acc, a_scale, p.s)
        out = out.astype(np.float32)
        return out[0] if single else out
