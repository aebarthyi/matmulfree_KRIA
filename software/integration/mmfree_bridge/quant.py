"""Activation quantization / output dequantization for the int16 engine.

Numeric contract (docs/MMFREELLM_INTEGRATION.md §1, int16 variant):
    a_scale  = 32767 / max(|x_norm|)          (per token / per row)
    x_int    = round(x_norm * a_scale)         (int16)
    engine     acc[m] = Σ_n x_int[n] * w_t[n,m]
    y[m]     = acc[m] / (a_scale * s)          (s = 1/mean|W|, from the manifest)

numpy-only so the whole chain is testable without torch or hardware; the torch
patch converts tensors to/from numpy at the boundary.
"""

from __future__ import annotations

import numpy as np

INT16_MAX = 32767
INT16_MIN = -32768
ACT_EPS = 1e-5  # mirrors the model's clamp_(min=1e-5) on the scale denominator


def quantize_act_int16(x: np.ndarray, eps: float = ACT_EPS):
    """Per-row symmetric int16 quant. Returns (x_int int16, a_scale float64).

    x may be 1-D (a single token, length N) or 2-D (T tokens x N); a_scale is a
    scalar or (T,1) so dequantize broadcasts back over the M output columns.
    """
    xf = np.asarray(x, dtype=np.float64)
    maxabs = np.max(np.abs(xf), axis=-1, keepdims=True)
    a_scale = INT16_MAX / np.maximum(maxabs, eps)
    x_int = np.clip(np.rint(xf * a_scale), INT16_MIN, INT16_MAX).astype(np.int16)
    return x_int, (a_scale if xf.ndim > 1 else float(a_scale.reshape(())))


def dequantize(acc: np.ndarray, a_scale, s: float) -> np.ndarray:
    """y = acc / (a_scale * s). acc is (M,) or (T,M); a_scale scalar or (T,1)."""
    return np.asarray(acc, dtype=np.float64) / (np.asarray(a_scale) * s)
