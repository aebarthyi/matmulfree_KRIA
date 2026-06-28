"""Phase D bridge tests — board-free, via the RefBackend software twin.

Validates the full numeric chain (int16 quant -> integer matmul -> dequant) and
the engine plumbing without torch or the KRIA. The RefBackend computes exactly
what mmfree_bitlinear computes in hardware (acc = codes @ x_int), so a green run
here means the only thing left to verify on-board is the DMA path (already
smoke-tested in C).
"""

import os

import numpy as np
import pytest

from mmfree_pack import Geometry, A16, quantize_weight
from mmfree_bridge import MmfreeEngine, RefBackend
from mmfree_bridge.quant import quantize_act_int16, dequantize


def _synthetic_state_dict(num_layers=2, hidden=256, inter=384, vocab=512, seed=0):
    rng = np.random.default_rng(seed)
    sd = {}
    for L in range(num_layers):
        for name, (out, inn) in {
            "attn.i_proj.weight": (hidden, hidden),
            "attn.f_proj.weight": (hidden, hidden),
            "attn.g_proj.weight": (hidden, hidden),
            "attn.o_proj.weight": (hidden, hidden),
            "mlp.gate_proj.weight": (inter * 2, hidden),
            "mlp.down_proj.weight": (hidden, inter),
        }.items():
            sd[f"model.layers.{L}.{name}"] = rng.standard_normal((out, inn)).astype(np.float32)
    sd["lm_head.weight"] = rng.standard_normal((vocab, hidden)).astype(np.float32)
    return sd


# ---- quant unit tests ----

def test_quant_int16_basic():
    x = np.array([0.0, 1.0, -2.0, 0.5], dtype=np.float32)
    x_int, a_scale = quantize_act_int16(x)
    assert x_int.dtype == np.int16
    # max |x| = 2 maps to +/-32767
    assert a_scale == pytest.approx(32767.0 / 2.0)
    assert x_int[2] == -32767                      # -2 * (32767/2)
    assert abs(int(x_int[1]) - round(1.0 * a_scale)) == 0


def test_quant_dequant_recovers_values():
    rng = np.random.default_rng(1)
    x = rng.standard_normal(64).astype(np.float32)
    x_int, a_scale = quantize_act_int16(x)
    # dividing the int codes back by a_scale (s=1) recovers x within a quant step
    recon = dequantize(x_int, a_scale, 1.0)
    step = 1.0 / a_scale
    assert np.max(np.abs(recon - x)) <= step


def test_quant_batched_per_row_scale():
    x = np.array([[1.0, 0.0], [0.0, 4.0]], dtype=np.float32)
    x_int, a_scale = quantize_act_int16(x)
    assert a_scale.shape == (2, 1)
    assert x_int[0, 0] == 32767 and x_int[1, 1] == 32767


# ---- engine / RefBackend ----

def _build_engine(geom=None, **kw):
    geom = geom or Geometry.derive(aWidth=16, xDim=8)  # small olt=32 -> fast packing
    sd = _synthetic_state_dict(**kw)
    return MmfreeEngine.from_weights(sd, geom, RefBackend(), fuse_ifg=False), sd, geom


def test_engine_projection_names_unfused():
    eng, _, _ = _build_engine()
    names = set(eng.proj)
    for L in (0, 1):
        for leaf in ("attn.i_proj", "attn.f_proj", "attn.g_proj", "attn.o_proj",
                     "mlp.gate_proj", "mlp.down_proj"):
            assert f"layers.{L}.{leaf}" in names
    assert "lm_head" in names
    assert len(names) == 2 * 6 + 1


def test_engine_project_matches_int16_reference_exactly():
    """engine.project must equal the hand-computed int16 path bit-for-bit."""
    eng, sd, _ = _build_engine(seed=2)
    rng = np.random.default_rng(3)

    name = "layers.0.attn.i_proj"
    p = eng.proj[name]
    x_norm = rng.standard_normal(p.N).astype(np.float32)

    # independent reference: same quant + same integer matmul + same dequant
    codes, s = quantize_weight(sd["model.layers.0.attn.i_proj.weight"])  # (M,N)
    x_int, a_scale = quantize_act_int16(x_norm)
    acc_ref = codes.astype(np.int64) @ x_int.astype(np.int64)            # (M,)
    y_ref = acc_ref / (a_scale * s)

    y = eng.project(name, x_norm)
    assert y.shape == (p.M,)
    np.testing.assert_allclose(y, y_ref, rtol=0, atol=1e-3)


def test_engine_project_close_to_full_precision():
    """int16 activations should track the unquantized BitLinear closely."""
    eng, sd, _ = _build_engine(seed=4)
    rng = np.random.default_rng(5)
    name = "layers.1.mlp.down_proj"          # non-pow2 N path
    p = eng.proj[name]
    x_norm = rng.standard_normal(p.N).astype(np.float32)

    codes, s = quantize_weight(sd["model.layers.1.mlp.down_proj.weight"])
    W_dequant = codes.astype(np.float64) / s                 # ternary weight, dequantized
    y_fp = (W_dequant @ x_norm.astype(np.float64))           # activations NOT quantized

    y = eng.project(name, x_norm)
    rel = np.max(np.abs(y - y_fp)) / (np.max(np.abs(y_fp)) + 1e-9)
    assert rel < 1e-3, f"int16 activation error too large: {rel}"


def test_engine_project_batched():
    eng, sd, _ = _build_engine(seed=6)
    rng = np.random.default_rng(7)
    name = "layers.0.mlp.gate_proj"
    p = eng.proj[name]
    X = rng.standard_normal((5, p.N)).astype(np.float32)
    Y = eng.project(name, X)
    assert Y.shape == (5, p.M)
    # each row must equal the single-token path
    for r in range(5):
        np.testing.assert_allclose(Y[r], eng.project(name, X[r]), rtol=0, atol=1e-4)


def test_engine_sizing_properties():
    eng, _, geom = _build_engine(seed=8)
    assert eng.weight_bytes_per_port == eng._packer.total_bytes_per_port
    assert eng.max_N > 0
    assert eng.max_n_outputs % geom.outLanesPerTile == 0


def test_engine_write_blobs(tmp_path):
    eng, _, geom = _build_engine(seed=9)
    man = eng.write_blobs(tmp_path)
    assert len(man["projections"]) == len(eng.proj)
    for p in range(geom.numPorts):
        assert (tmp_path / f"weights.port{p}.bin").exists()


# ---- ctypes ABI guard (runs only if the host .so was built) ----

_SO = os.path.join(os.path.dirname(__file__), "..", "..", "build", "libmmfree.so")


@pytest.mark.skipif(not os.path.exists(_SO), reason="libmmfree.so not built (run `make lib`)")
def test_ctypes_abi_matches_c():
    from mmfree_bridge.ctypes_lib import MmfreeLib
    lib = MmfreeLib(os.path.abspath(_SO))      # constructor asserts sizeof match
    assert MmfreeLib.num_ports(16, 32, os.path.abspath(_SO)) == 4
    assert MmfreeLib.num_ports(8, 64, os.path.abspath(_SO)) == 4
