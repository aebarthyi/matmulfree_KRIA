"""mmfree_pack — offline weight packer for the KRIA ternary matmul engine.

Phase B — converts MMfreeLM-370M BitLinear
weights into the engine's col-tile-major 2-bit per-port layout + a manifest the
C runtime (Phase C) loads into resident udmabufs.
"""

from .geometry import Geometry, A16, manifest_path
from .packer import (
    WeightPacker,
    ProjEntry,
    quantize_weight,
    codes_to_2bit,
    pack_projection,
)
from .model import iter_projections, iter_blob_projections, load_checkpoint
from .blob import read_mmfree_blob

__all__ = [
    "Geometry", "A16", "manifest_path",
    "WeightPacker", "ProjEntry",
    "quantize_weight", "codes_to_2bit", "pack_projection",
    "iter_projections", "iter_blob_projections", "load_checkpoint",
    "read_mmfree_blob",
]
