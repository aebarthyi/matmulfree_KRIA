"""Compute backends for the bridge engine.

Both expose the same tiny interface so the engine (quant + dequant) is identical
whether it runs on hardware or in a board-free numpy twin:

    register(byte_offset, N, M) -> proj_id
    note_codes(proj_id, codes_MN)        # only RefBackend uses it
    bitlinear(proj_id, x_int16) -> int32[M]   # raw engine accumulator

RefBackend is a faithful software model of the engine: acc[m] = Σ_n x[n]·codes[m,n],
exactly what mmfree_bitlinear computes in hardware — so the full numeric chain is
testable without the KRIA.
"""

from __future__ import annotations

from typing import List, Optional, Tuple

import numpy as np


class _BaseBackend:
    def __init__(self):
        self._dims: List[Tuple[int, int]] = []  # (N, M) per proj_id

    def register(self, byte_offset: int, N: int, M: int) -> int:
        pid = len(self._dims)
        self._dims.append((N, M))
        return pid

    def note_codes(self, proj_id: int, codes_MN: np.ndarray) -> None:
        pass  # default: backends that don't need codes ignore them

    def bitlinear(self, proj_id: int, x_int16: np.ndarray) -> np.ndarray:
        raise NotImplementedError


class RefBackend(_BaseBackend):
    """Numpy software twin of the engine — no hardware, exact integer matmul."""

    def __init__(self):
        super().__init__()
        self._codes: List[Optional[np.ndarray]] = []

    def register(self, byte_offset: int, N: int, M: int) -> int:
        pid = super().register(byte_offset, N, M)
        self._codes.append(None)
        return pid

    def note_codes(self, proj_id: int, codes_MN: np.ndarray) -> None:
        self._codes[proj_id] = np.asarray(codes_MN, dtype=np.int32)  # (M, N) in {-1,0,1}

    def bitlinear(self, proj_id: int, x_int16: np.ndarray) -> np.ndarray:
        codes = self._codes[proj_id]
        if codes is None:
            raise RuntimeError(f"RefBackend: no codes for proj {proj_id}")
        x = np.asarray(x_int16, dtype=np.int32)               # (N,)
        return (codes @ x).astype(np.int32)                    # (M,) = Σ_n x[n]·codes[m,n]


class CtypesBackend(_BaseBackend):
    """Real backend: delegates to libmmfree.so via MmfreeLib."""

    def __init__(self, lib):
        super().__init__()
        self._lib = lib  # an opened MmfreeLib

    def register(self, byte_offset: int, N: int, M: int) -> int:
        pid_c = self._lib.register(byte_offset, N, M)
        pid = super().register(byte_offset, N, M)
        assert pid == pid_c, f"proj_id drift: lib={pid_c} bridge={pid}"
        return pid

    def bitlinear(self, proj_id: int, x_int16: np.ndarray) -> np.ndarray:
        _, M = self._dims[proj_id]
        return self._lib.bitlinear(proj_id, x_int16, M)
