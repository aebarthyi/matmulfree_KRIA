"""Phase B: pack-from-blob tests.

Verifies that packing directly from a C++ `model.mmfree` blob (already-ternary wq)
produces (a) manifest keys equal to the runtime's projection tags and (b) per-port
bytes byte-identical to packing the same codes through pack_projection. The latter
is already proven equal to bench.c's loop (test_pack_roundtrip), so matching it
means the engine consumes exactly the codes the C++ FixedQ510 path uses — the
invariant the C++↔FPGA exact-match gate relies on.

No torch, no board: a synthetic MMFREE1 blob is built in-memory (mirroring
matmulfreellmCPU/tools/pack_weights.py's writer).
"""

import json
import struct

import numpy as np

from mmfree_pack import A16, WeightPacker, pack_projection, read_mmfree_blob
from mmfree_pack.blob import ALIGN, MAGIC
from mmfree_pack.model import iter_blob_projections

_ITEMSIZE = {"f32": 4, "i8": 1}


def _align(n):
    return (n + ALIGN - 1) // ALIGN * ALIGN


def _write_blob(path, config, tensors):
    """Write a model.mmfree blob. `tensors`: name -> (dtype_str, np.ndarray)."""
    meta, offset = {}, 0
    for name, (dt, arr) in tensors.items():
        nbytes = arr.nbytes
        meta[name] = {"dtype": dt, "shape": list(arr.shape), "offset": offset, "nbytes": nbytes}
        offset = _align(offset + nbytes)
    header = json.dumps({"config": config, "tensors": meta}).encode("utf-8")
    data_start = _align(len(MAGIC) + 8 + len(header))
    with open(path, "wb") as f:
        f.write(MAGIC)
        f.write(struct.pack("<Q", len(header)))
        f.write(header)
        f.write(b"\0" * (data_start - f.tell()))
        base = f.tell()
        for name, (_dt, arr) in tensors.items():
            f.write(b"\0" * (base + meta[name]["offset"] - f.tell()))
            f.write(np.ascontiguousarray(arr).tobytes())
        f.write(b"\0" * (base + offset - f.tell()))


def _ternary(rng, shape):
    return rng.integers(-1, 2, size=shape).astype(np.int8)  # {-1,0,1}


def _synthetic_blob_tensors(num_layers=2, hidden=8, inter=12, vocab=20):
    """Tensors a model.mmfree carries, with the C++ tag naming. wq shapes are
    nn.Linear (out=M, in=N); only the .wq/.scale_w pairs matter for packing."""
    rng = np.random.default_rng(2024)
    projs = {
        "i_proj": (hidden, hidden), "f_proj": (hidden, hidden), "g_proj": (hidden, hidden),
        "o_proj": (hidden, hidden), "gate_proj": (inter * 2, hidden),
        "down_proj": (hidden, inter),
    }
    t = {}
    for L in range(num_layers):
        for tag, (M, N) in projs.items():
            base = f"model.layers.{L}.{tag}"
            t[base + ".wq"] = ("i8", _ternary(rng, (M, N)))
            t[base + ".scale_w"] = ("f32", np.array([0.1 + 0.01 * L], np.float32))
    t["lm_head.wq"] = ("i8", _ternary(rng, (vocab, hidden)))
    t["lm_head.scale_w"] = ("f32", np.array([0.137], np.float32))
    return t


def test_blob_roundtrip_reader(tmp_path):
    cfg = {"vocab_size": 20, "hidden_size": 8, "num_hidden_layers": 2}
    tensors = _synthetic_blob_tensors()
    blob = tmp_path / "model.mmfree"
    _write_blob(blob, cfg, tensors)

    got_cfg, got = read_mmfree_blob(blob)
    assert got_cfg["hidden_size"] == 8
    for name, (dt, arr) in tensors.items():
        assert got[name].dtype == np.dtype({"f32": np.float32, "i8": np.int8}[dt])
        np.testing.assert_array_equal(got[name], arr)


def test_blob_projection_tags_and_order(tmp_path):
    blob = tmp_path / "model.mmfree"
    _write_blob(blob, {}, _synthetic_blob_tensors(num_layers=2))
    _cfg, tensors = read_mmfree_blob(blob)
    tags = [tag for tag, _c, _s in iter_blob_projections(tensors)]
    assert tags == [
        "model.layers.0.i_proj", "model.layers.0.f_proj", "model.layers.0.g_proj",
        "model.layers.0.o_proj", "model.layers.0.gate_proj", "model.layers.0.down_proj",
        "model.layers.1.i_proj", "model.layers.1.f_proj", "model.layers.1.g_proj",
        "model.layers.1.o_proj", "model.layers.1.gate_proj", "model.layers.1.down_proj",
        "lm_head",
    ]


def test_blob_pack_bytes_match_direct_pack(tmp_path):
    # Use the real a16 geometry and engine-relevant shapes so padding/tiling is exercised.
    rng = np.random.default_rng(7)
    tensors = {
        "model.layers.0.i_proj.wq": ("i8", _ternary(rng, (1024, 1024))),
        "model.layers.0.i_proj.scale_w": ("f32", np.array([0.21], np.float32)),
        "model.layers.0.down_proj.wq": ("i8", _ternary(rng, (1024, 2816))),  # non-pow2 N
        "model.layers.0.down_proj.scale_w": ("f32", np.array([0.33], np.float32)),
        "lm_head.wq": ("i8", _ternary(rng, (32000, 1024))),                 # padded tiles
        "lm_head.scale_w": ("f32", np.array([0.05], np.float32)),
    }
    blob = tmp_path / "model.mmfree"
    _write_blob(blob, {}, tensors)
    _cfg, got = read_mmfree_blob(blob)

    packer = WeightPacker(A16)
    entries = {}
    for tag, codes, s in iter_blob_projections(got):
        e = packer.add_quantized(tag, codes, s)
        entries[tag] = (e, codes, s)

    man = packer.write(tmp_path, prefix="w")
    blobs = [np.fromfile(tmp_path / f"w.port{p}.bin", np.uint8) for p in range(A16.numPorts)]

    # manifest keys == C++ tags, scale_w preserved verbatim from the blob
    assert [p["name"] for p in man["projections"]] == list(entries)
    for p in man["projections"]:
        e, codes, s = entries[p["name"]]
        assert p["s"] == s and (p["N"], p["M"]) == (e.N, e.M)
        # the resident slice for this projection == a direct pack of its codes
        direct = pack_projection(codes, A16)
        for port in range(A16.numPorts):
            sl = blobs[port][e.byte_offset:e.byte_offset + e.blob_bytes]
            np.testing.assert_array_equal(sl, direct[port], err_msg=f"{p['name']} port {port}")
