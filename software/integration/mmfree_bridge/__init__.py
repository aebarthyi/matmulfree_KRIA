"""mmfree_bridge — run MMfreeLM BitLinear projections on the KRIA engine (Phase D).

    from mmfree_bridge import MmfreeEngine, RefBackend
    eng = MmfreeEngine.from_weights(weights, geom, RefBackend())   # board-free twin
    y = eng.project("layers.0.attn.i_proj", x_norm)

For the real engine, build a CtypesBackend over an opened MmfreeLib, write the
packed blobs, and load them. The torch monkeypatch (patch.prepare_for_inference)
wires this into FusedBitLinear.forward.
"""

from .quant import quantize_act_int16, dequantize
from .backend import RefBackend, CtypesBackend
from .engine import MmfreeEngine

__all__ = [
    "quantize_act_int16", "dequantize",
    "RefBackend", "CtypesBackend",
    "MmfreeEngine",
]
