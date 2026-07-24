"""1.6-bit (5 trits/byte) weight-packing round-trip.

Cross-checks `pack_projection_packed` against an INDEPENDENT emulation of the
on-chip WeightUnpackGearbox: reassemble the wire beats from the per-port blobs,
unpack each byte to 5 PE codes (base-3, LSB-first — the TernaryPack convention),
and confirm the resulting weight-beat stream equals the 2-bit reference codes.

Run standalone (`python test_packed_roundtrip.py`) or under pytest from
software/integration/.
"""

from __future__ import annotations

import numpy as np

from mmfree_pack import Geometry, WeightPacker, pack_projection_packed
from mmfree_pack.geometry import TRITS_PER_BYTE

# base-3 digit {0,1,2} -> PE code {0,1,3} (hold / add / sub) — mirrors TernaryPack.
_DIGIT_TO_CODE = np.array([0, 1, 3], dtype=np.uint8)


def _unpack_gearbox(ports, geom: Geometry) -> np.ndarray:
    """Emulate the gearbox: per-port blobs -> flat PE-code stream (uint8 in {0,1,3}).

    Reassembles wire beats (byte b of a beat comes from port b//portBytes, port 0 =
    beat LSBs), then expands each byte to 5 codes LSB-first."""
    pb, np_ = geom.portBytes, geom.numPorts
    nbeats = ports[0].size // pb
    beats = np.stack([p.reshape(nbeats, pb) for p in ports], axis=1)  # (nbeats,np,pb)
    flat_bytes = beats.reshape(-1)                                    # wire byte order
    digits = np.empty((flat_bytes.size, TRITS_PER_BYTE), np.uint8)
    b = flat_bytes.astype(np.int64)
    for k in range(TRITS_PER_BYTE):
        digits[:, k] = (b // (3 ** k)) % 3
    return _DIGIT_TO_CODE[digits].reshape(-1)


def _reference_codes(codes_MN: np.ndarray, geom: Geometry) -> np.ndarray:
    """The [t][n][lane] PE-code stream the array must see (2-bit reference path)."""
    wt = codes_MN.T.astype(np.int8)                 # (N,M)
    N, M = wt.shape
    T, olt = geom.num_col_tiles(M), geom.outLanesPerTile
    pad = T * olt - M
    if pad:
        wt = np.concatenate([wt, np.zeros((N, pad), np.int8)], axis=1)
    pe = (wt & 0x3).astype(np.uint8)                # -1->3, 1->1, 0->0
    return pe.reshape(N, T, olt).transpose(1, 0, 2).reshape(-1)


def _check_projection(N: int, M: int, seed: int, geom: Geometry) -> None:
    rng = np.random.default_rng(seed)
    codes = rng.integers(-1, 2, size=(M, N), dtype=np.int8)   # (M,N) ternary
    ports = pack_projection_packed(codes, geom)

    # ragged/aligned per-port blobs, all equal, matching the geometry helper.
    assert len({p.size for p in ports}) == 1, "ragged per-port blobs"
    assert ports[0].size == geom.packed_port_blob_bytes(N, M), (
        f"blob {ports[0].size} != helper {geom.packed_port_blob_bytes(N, M)}")

    ref = _reference_codes(codes, geom)
    got = _unpack_gearbox(ports, geom)
    assert got.size >= ref.size
    assert np.array_equal(got[:ref.size], ref), f"mismatch N={N} M={M} seed={seed}"
    # trailing trits are flush-dropped padding -> must be holds (code 0).
    assert np.all(got[ref.size:] == 0), "non-hold padding trits"


def test_packed_roundtrip():
    geom = Geometry.derive(aWidth=16, xDim=32, packed=True)
    olt = geom.outLanesPerTile
    shapes = [(64, 64), (olt, olt), (olt + 5, olt * 2 + 3), (512, 1536), (2048, olt)]
    for i, (N, M) in enumerate(shapes):
        _check_projection(N, M, seed=100 + i, geom=geom)


def test_bandwidth_saving():
    """1.6-bit blob is ~0.8x the 2-bit blob (the whole point)."""
    geom2 = Geometry.derive(aWidth=16, xDim=32, packed=False)
    geomp = Geometry.derive(aWidth=16, xDim=32, packed=True)
    N, M = 2048, 6144
    ratio = geomp.packed_port_blob_bytes(N, M) / geom2.port_blob_bytes(N, M)
    assert 0.79 <= ratio <= 0.82, f"packed ratio {ratio:.4f} not ~0.8"


def test_manifest_format_tag():
    geomp = Geometry.derive(aWidth=16, xDim=32, packed=True)
    packer = WeightPacker(geomp)
    packer.add_quantized("proj", np.ones((64, 128), np.int8), s=1.0)
    m = packer.manifest()
    assert m["weight_format"] == "ternary-1p6"
    assert m["geometry"]["packed"] is True


if __name__ == "__main__":
    test_packed_roundtrip()
    test_bandwidth_saving()
    test_manifest_format_tag()
    print("OK: packed round-trip, bandwidth saving, and format tag all pass.")
