"""Reader for the C++ runtime's `model.mmfree` weight blob (Phase B).

The blob is produced by matmulfreellmCPU/tools/pack_weights.py and consumed by
cpp/src/io/weights.cpp. We read it here to pack the *already-ternary* projection
weights for the engine — using the blob's own `wq`/`scale_w` rather than
re-quantizing from float — so the engine's ternary codes are byte-for-byte the
codes the C++ FixedQ510 path uses. That identity is what makes the C++↔FPGA
accumulator exactly equal (the P-D exact-match gate).

Blob layout (mirror weights.cpp / pack_weights.py):
    [8]   magic "MMFREE1\n"
    [8]   u64 header_len (little-endian)
    [hl]  header JSON {"config":{...},"tensors":{name:{dtype,shape,offset,nbytes}}}
    pad to 64
    data section: each tensor 64-byte aligned; `offset` is relative to its start.
"""

from __future__ import annotations

import json
import struct
from pathlib import Path
from typing import Dict, Tuple

import numpy as np

MAGIC = b"MMFREE1\n"
ALIGN = 64
_DTYPE = {"f32": np.float32, "i8": np.int8}


def _align(n: int) -> int:
    return (n + ALIGN - 1) // ALIGN * ALIGN


def read_mmfree_blob(path: str | Path) -> Tuple[Dict, Dict[str, np.ndarray]]:
    """Parse a model.mmfree blob into (config dict, {tensor_name: np.ndarray}).

    Arrays are views into a single in-memory copy of the file (read-only); reshape
    to the stored shape with the stored dtype. Raises ValueError on a bad magic or
    unknown dtype.
    """
    data = Path(path).read_bytes()
    if data[:8] != MAGIC:
        raise ValueError(f"{path}: bad magic {data[:8]!r} (expected {MAGIC!r})")
    (header_len,) = struct.unpack_from("<Q", data, 8)
    header = json.loads(data[16:16 + header_len].decode("utf-8"))
    data_start = _align(16 + header_len)

    tensors: Dict[str, np.ndarray] = {}
    for name, m in header["tensors"].items():
        dt = _DTYPE.get(m["dtype"])
        if dt is None:
            raise ValueError(f"{path}: tensor {name} has unknown dtype {m['dtype']!r}")
        off = data_start + int(m["offset"])
        count = int(m["nbytes"]) // np.dtype(dt).itemsize
        arr = np.frombuffer(data, dtype=dt, count=count, offset=off)
        tensors[name] = arr.reshape([int(s) for s in m["shape"]])
    return header.get("config", {}), tensors
