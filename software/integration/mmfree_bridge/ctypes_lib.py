"""ctypes binding to libmmfree.so (Phase D).

Mirrors software/integration/libmmfree/mmfree_lib.h. The struct layout is
asserted against the C lib's own sizeof at load time (mmfree_lib_cfg_sizeof),
so header/ABI drift fails loudly instead of corrupting the config silently.
"""

from __future__ import annotations

import ctypes
from dataclasses import dataclass, field
from typing import List, Optional, Sequence

import numpy as np

MAX_DMA = 4  # MMFREE_MAX_DMA


class _Cfg(ctypes.Structure):
    """Exact mirror of mmfree_lib_cfg_t (field order + types must match)."""
    _fields_ = [
        ("aWidth", ctypes.c_uint32),
        ("xDim", ctypes.c_uint32),
        ("maxAcc", ctypes.c_uint32),
        ("maxN", ctypes.c_uint32),
        ("maxM", ctypes.c_uint32),
        ("core_phys", ctypes.c_uint64),
        ("core_size", ctypes.c_size_t),
        ("dma_phys", ctypes.c_uint64 * MAX_DMA),
        ("num_dma", ctypes.c_uint32),
        ("dma_size", ctypes.c_size_t),
        ("uio_dev", ctypes.c_char_p),
        ("wt_dev", ctypes.c_char_p * MAX_DMA),
        ("act_dev", ctypes.c_char_p * MAX_DMA),
        ("out_dev", ctypes.c_char_p),
        ("weight_bytes_per_port", ctypes.c_size_t),
        ("act_bytes_per_port", ctypes.c_size_t),
        ("out_bytes", ctypes.c_size_t),
        ("max_proj", ctypes.c_uint32),
    ]


@dataclass
class LibConfig:
    """Board configuration for mmfree_lib_open (Python-friendly; -> _Cfg)."""
    aWidth: int = 16
    xDim: int = 32
    maxAcc: int = 4096
    maxN: int = 4096
    maxM: int = 32000
    core_phys: int = 0xA0010000
    core_size: int = 0x1000
    dma_phys: Sequence[int] = field(default_factory=lambda: [0xA0000000, 0xA0020000, 0xA0030000, 0xA0040000])
    dma_size: int = 0x10000
    uio_dev: str = "/dev/uio4"
    wt_dev: Sequence[str] = field(default_factory=lambda: [f"/dev/udmabuf-wt{i}" for i in range(MAX_DMA)])
    act_dev: Sequence[str] = field(default_factory=lambda: [f"/dev/udmabuf-act{i}" for i in range(MAX_DMA)])
    out_dev: str = "/dev/udmabuf-out"
    weight_bytes_per_port: int = 0
    act_bytes_per_port: int = 0
    out_bytes: int = 0
    max_proj: int = 256


def _bytes(s: str) -> bytes:
    return s.encode() if isinstance(s, str) else s


class MmfreeLib:
    """Thin OO wrapper over the C handle. One in-flight op at a time."""

    def __init__(self, so_path: str = "libmmfree.so"):
        self._lib = ctypes.CDLL(so_path)
        self._bind()
        self._h: Optional[ctypes.c_void_p] = None
        self._kept: List[object] = []  # keep c_char_p buffers alive while open

        py = ctypes.sizeof(_Cfg)
        c = self._lib.mmfree_lib_cfg_sizeof()
        if py != c:
            raise RuntimeError(
                f"mmfree_lib_cfg_t ABI mismatch: ctypes={py} B, C lib={c} B — "
                "ctypes_lib.py is out of sync with mmfree_lib.h")

    def _bind(self):
        L = self._lib
        L.mmfree_lib_cfg_sizeof.restype = ctypes.c_size_t
        L.mmfree_lib_num_ports.argtypes = [ctypes.c_uint32, ctypes.c_uint32]
        L.mmfree_lib_num_ports.restype = ctypes.c_uint32
        L.mmfree_lib_open.argtypes = [ctypes.POINTER(_Cfg)]
        L.mmfree_lib_open.restype = ctypes.c_void_p
        L.mmfree_lib_close.argtypes = [ctypes.c_void_p]
        L.mmfree_lib_load_weights_file.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_char_p]
        L.mmfree_lib_load_weights_file.restype = ctypes.c_int
        L.mmfree_register.argtypes = [ctypes.c_void_p, ctypes.c_uint64, ctypes.c_uint32, ctypes.c_uint32]
        L.mmfree_register.restype = ctypes.c_int
        L.mmfree_bitlinear.argtypes = [
            ctypes.c_void_p, ctypes.c_int,
            ctypes.POINTER(ctypes.c_int16), ctypes.POINTER(ctypes.c_int32),
        ]
        L.mmfree_bitlinear.restype = ctypes.c_int

    @staticmethod
    def num_ports(aWidth: int, xDim: int, so_path: str = "libmmfree.so") -> int:
        return ctypes.CDLL(so_path).mmfree_lib_num_ports(aWidth, xDim)

    def open(self, cfg: LibConfig):
        c = _Cfg()
        c.aWidth, c.xDim = cfg.aWidth, cfg.xDim
        c.maxAcc, c.maxN, c.maxM = cfg.maxAcc, cfg.maxN, cfg.maxM
        c.core_phys, c.core_size = cfg.core_phys, cfg.core_size
        c.num_dma, c.dma_size = len(cfg.wt_dev), cfg.dma_size
        for i, p in enumerate(cfg.dma_phys):
            c.dma_phys[i] = p
        # c_char_p buffers must outlive the struct — stash them.
        uio = _bytes(cfg.uio_dev); out = _bytes(cfg.out_dev)
        self._kept = [uio, out]
        c.uio_dev, c.out_dev = uio, out
        for i in range(len(cfg.wt_dev)):
            wb, ab = _bytes(cfg.wt_dev[i]), _bytes(cfg.act_dev[i])
            self._kept += [wb, ab]
            c.wt_dev[i], c.act_dev[i] = wb, ab
        c.weight_bytes_per_port = cfg.weight_bytes_per_port
        c.act_bytes_per_port = cfg.act_bytes_per_port
        c.out_bytes = cfg.out_bytes
        c.max_proj = cfg.max_proj
        self._cfg_keep = c  # keep the struct alive too

        h = self._lib.mmfree_lib_open(ctypes.byref(c))
        if not h:
            raise RuntimeError("mmfree_lib_open failed (see stderr)")
        self._h = ctypes.c_void_p(h)
        return self

    def load_weights_file(self, port: int, path: str):
        rc = self._lib.mmfree_lib_load_weights_file(self._h, port, _bytes(path))
        if rc < 0:
            raise RuntimeError(f"load_weights_file(port={port}, {path}) failed rc={rc}")

    def register(self, byte_offset: int, N: int, M: int) -> int:
        pid = self._lib.mmfree_register(self._h, byte_offset, N, M)
        if pid < 0:
            raise RuntimeError(f"mmfree_register(off={byte_offset}, N={N}, M={M}) failed rc={pid}")
        return pid

    def bitlinear(self, proj_id: int, x_int16: np.ndarray, M: int) -> np.ndarray:
        x = np.ascontiguousarray(x_int16, dtype=np.int16)
        acc = np.empty(M, dtype=np.int32)
        rc = self._lib.mmfree_bitlinear(
            self._h, proj_id,
            x.ctypes.data_as(ctypes.POINTER(ctypes.c_int16)),
            acc.ctypes.data_as(ctypes.POINTER(ctypes.c_int32)),
        )
        if rc < 0:
            raise RuntimeError(f"mmfree_bitlinear(id={proj_id}) failed rc={rc}")
        return acc

    def close(self):
        if self._h is not None:
            self._lib.mmfree_lib_close(self._h)
            self._h = None
            self._kept = []
