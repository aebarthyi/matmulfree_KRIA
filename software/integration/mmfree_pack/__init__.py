"""mmfree_pack — offline weight packer for the KRIA ternary matmul engine.

Converts MMfreeLM BitLinear weights into the engine's col-tile-major per-port
layout (2-bit or 1.6-bit) plus a manifest the runner loads into resident udmabufs.
"""

from .geometry import Geometry, A16, manifest_path
from .packer import (
    WeightPacker,
    ProjEntry,
    quantize_weight,
    codes_to_2bit,
    codes_to_trit_digits,
    pack_projection,
    pack_projection_packed,
)
from .model import iter_projections, iter_blob_projections, load_checkpoint
from .blob import read_mmfree_blob

__all__ = [
    "Geometry", "A16", "manifest_path",
    "WeightPacker", "ProjEntry",
    "quantize_weight", "codes_to_2bit", "codes_to_trit_digits",
    "pack_projection", "pack_projection_packed",
    "iter_projections", "iter_blob_projections", "load_checkpoint",
    "read_mmfree_blob",
]
