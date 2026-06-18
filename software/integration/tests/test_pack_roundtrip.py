"""Phase B packer tests.

The load-bearing test is `test_pack_matches_bench_c_scalar`: it re-implements
bench.c's pack loop (run_shape, software/bench/bench.c) as a dead-simple scalar
reference and asserts the vectorized packer is byte-for-byte identical. If the
engine consumes what bench.c writes (verified on board, errs=0), and we match
bench.c's bytes, we match the engine.
"""

import numpy as np
import pytest

from mmfree_pack import (
    Geometry, A16, WeightPacker, quantize_weight, pack_projection, iter_projections,
)

_TERNARY_CODE = {0: 0, 1: 1, -1: 3}


def _scalar_pack(codes_MN: np.ndarray, geom: Geometry):
    """Independent scalar reference mirroring bench.c run_shape's weight loop.

    codes_MN is the quantized weight (M,N) in {-1,0,1}; the engine sees
    w_t[n,m] = codes_MN[m,n]. Returns one bytearray per port.
    """
    M, N = codes_MN.shape
    olt = geom.outLanesPerTile
    T = geom.num_col_tiles(M)
    lpp = geom.lanesPerPort
    pb = geom.portBytes
    ports = [bytearray(T * N * pb) for _ in range(geom.numPorts)]

    for t in range(T):
        for n in range(N):
            lanes = [0] * olt
            for lane in range(olt):
                m = t * olt + lane
                if m < M:
                    lanes[lane] = _TERNARY_CODE[int(codes_MN[m, n])]
            for p in range(geom.numPorts):
                for b in range(pb):
                    v = 0
                    for k in range(4):
                        v |= (lanes[p * lpp + b * 4 + k] & 0x3) << (k * 2)
                    ports[p][(t * N + n) * pb + b] = v
    return [np.frombuffer(bytes(buf), dtype=np.uint8) for buf in ports]


def test_geometry_a16():
    g = A16
    assert (g.aWidth, g.xDim) == (16, 32)
    assert g.nLanes == 8
    assert g.outLanesPerTile == 256
    assert g.numPorts == 4
    assert g.portBytes == 16
    assert g.lanesPerPort == 64
    assert g.portBytes * 4 == g.lanesPerPort
    # 1024-wide projection -> 4 col-tiles, exact (no padding)
    assert g.num_col_tiles(1024) == 4
    assert g.n_outputs(1024) == 1024
    # lm_head vocab 32000 -> 125 tiles, exact
    assert g.num_col_tiles(32000) == 125


def test_geometry_int8_64():
    g = Geometry.derive(aWidth=8, xDim=64)  # k26_mmfree370m int8 sibling
    assert g.nLanes == 4
    assert g.outLanesPerTile == 256
    assert g.numPorts == 4
    assert g.portBytes == 16


def test_quantize_contract():
    rng = np.random.default_rng(0)
    W = rng.standard_normal((40, 24)).astype(np.float32)
    codes, s = quantize_weight(W)
    assert codes.dtype == np.int8
    assert set(np.unique(codes)).issubset({-1, 0, 1})
    # s = 1/mean|W|, and codes = clamp(round(W*s), -1, 1)
    assert s == pytest.approx(1.0 / np.abs(W).mean())
    np.testing.assert_array_equal(codes, np.clip(np.round(W * s), -1, 1).astype(np.int8))


@pytest.mark.parametrize("N,M", [
    (1024, 1024),   # ifg-slice / o_proj — exact tiles
    (1024, 3072),   # fused ifg
    (2816, 1024),   # down_proj — non-pow2 N
    (1024, 5632),   # gate_up
    (1024, 320),    # forces col-tile padding (320 -> 2 tiles, 192 pad lanes)
    (17, 200),      # small + padded, stresses indexing
])
def test_pack_matches_bench_c_scalar(N, M):
    rng = np.random.default_rng(N * 7919 + M)
    W = rng.standard_normal((M, N)).astype(np.float32)  # nn.Linear (out, in)
    codes, _ = quantize_weight(W)

    fast = pack_projection(codes, A16)
    ref = _scalar_pack(codes, A16)

    assert len(fast) == len(ref) == A16.numPorts
    for p in range(A16.numPorts):
        assert fast[p].size == A16.port_blob_bytes(N, M)
        np.testing.assert_array_equal(fast[p], ref[p], err_msg=f"port {p}")


def test_pack_padding_lanes_are_zero():
    # M=320 -> tile 1 has lanes 320..511 as padding; they must encode as 0 (hold).
    N, M = 8, 320
    W = np.ones((M, N), dtype=np.float32)  # all +1 codes for real lanes
    codes, _ = quantize_weight(W)
    ports = pack_projection(codes, A16)
    T = A16.num_col_tiles(M)
    assert T == 2
    # Tile 1 (t=1) covers cols 256..511; only 256..319 are real (64 lanes),
    # which all land in port 0 (lanes 0..63). Ports 1..3 of tile 1 are all pad.
    pb, N_beats = A16.portBytes, N
    for p in (1, 2, 3):
        tile1 = ports[p][(1 * N_beats) * pb:(2 * N_beats) * pb]
        np.testing.assert_array_equal(tile1, 0, err_msg=f"port {p} tile1 not all-pad")


def test_packer_offsets_and_manifest():
    packer = WeightPacker(A16)
    rng = np.random.default_rng(1)
    e0 = packer.add("layers.0.ifg", rng.standard_normal((3072, 1024)).astype(np.float32))
    e1 = packer.add("layers.0.o_proj", rng.standard_normal((1024, 1024)).astype(np.float32))
    assert e0.byte_offset == 0
    assert e1.byte_offset == e0.blob_bytes
    assert e0.blob_bytes == A16.port_blob_bytes(1024, 3072)
    assert e0.M == 3072 and e0.N == 1024
    assert e0.num_col_tiles == 12 and e0.n_outputs == 3072
    man = packer.manifest()
    assert man["geometry"]["numPorts"] == 4
    assert man["total_bytes_per_port"] == e0.blob_bytes + e1.blob_bytes
    assert [p["name"] for p in man["projections"]] == ["layers.0.ifg", "layers.0.o_proj"]


def test_packer_write_blobs(tmp_path):
    packer = WeightPacker(A16)
    rng = np.random.default_rng(2)
    packer.add("layers.0.ifg", rng.standard_normal((3072, 1024)).astype(np.float32))
    packer.add("lm_head", rng.standard_normal((32000, 1024)).astype(np.float32))
    man = packer.write(tmp_path, prefix="w")
    for p in range(A16.numPorts):
        blob = tmp_path / f"w.port{p}.bin"
        assert blob.exists()
        assert blob.stat().st_size == man["total_bytes_per_port"]
    assert (tmp_path / "w.manifest.json").exists()


def _synthetic_state_dict(num_layers=2, hidden=8, inter=12, vocab=20):
    rng = np.random.default_rng(42)
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


def test_iter_projections_fusion_and_order():
    hidden, inter, vocab, L = 8, 12, 20, 2
    sd = _synthetic_state_dict(L, hidden, inter, vocab)
    projs = list(iter_projections(sd))

    names = [n for n, _ in projs]
    assert names == [
        "layers.0.ifg", "layers.0.o_proj", "layers.0.gate_up", "layers.0.down_proj",
        "layers.1.ifg", "layers.1.o_proj", "layers.1.gate_up", "layers.1.down_proj",
        "lm_head",
    ]
    shapes = {n: W.shape for n, W in projs}
    # i/f/g fused along out dim: (3*hidden, hidden)
    assert shapes["layers.0.ifg"] == (3 * hidden, hidden)
    assert shapes["layers.0.gate_up"] == (inter * 2, hidden)
    assert shapes["layers.0.down_proj"] == (hidden, inter)
    assert shapes["lm_head"] == (vocab, hidden)


def test_iter_projections_tied_lm_head():
    sd = _synthetic_state_dict()
    del sd["lm_head.weight"]
    sd["model.embeddings.weight"] = np.zeros((20, 8), np.float32)
    names = [n for n, _ in iter_projections(sd)]
    assert names[-1] == "lm_head"
