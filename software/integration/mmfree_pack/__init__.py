"""mmfree_pack — offline weight packer for the KRIA ternary matmul engine.

Phase B of docs/MMFREELLM_INTEGRATION.md. Converts MMfreeLM-370M BitLinear
weights into the engine's col-tile-major 2-bit per-port layout + a manifest the
C runtime (Phase C) loads into resident udmabufs.
"""

from .geometry import Geometry, A16
from .packer import (
    WeightPacker,
    ProjEntry,
    quantize_weight,
    codes_to_2bit,
    pack_projection,
)
from .model import iter_projections, load_checkpoint

__all__ = [
    "Geometry", "A16",
    "WeightPacker", "ProjEntry",
    "quantize_weight", "codes_to_2bit", "pack_projection",
    "iter_projections", "load_checkpoint",
]
