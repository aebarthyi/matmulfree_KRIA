"""Offline weight packer (Phase B).

Turns each BitLinear weight matrix into the engine's col-tile-major, 2-bit,
per-port byte layout — byte-identical to bench.c's pack loop — plus the scale
`s` the runtime needs to dequantize. Pure numpy: no torch, no board.

Numeric contract:
    s        = 1 / mean(|W|)                       (per projection, scalar)
    w_t[m,n] = clamp(round(W[m,n] * s), -1, 1)      (ternary, matches weight_quant)
    engine acc[m] = Σ_n x_int[n] * w_t[n,m]
    y[m]          = acc[m] / (a_scale * s)          (runtime divides; not done here)

Orientation: nn.Linear weight W is (out=M, in=N) and F.linear computes x @ W.T,
so the engine's w_t[n,m] is W[m,n] — we index the transpose when packing.

Ternary -> 2-bit code: +1 -> 0b01, -1 -> 0b11, 0 -> 0b00. Two's-complement int8
makes this just `v & 0x3` (-1 -> 0xFF & 3 = 3), matching Core.scala's encoding
(1=add, 3=subtract, 0/2=hold).
"""

from __future__ import annotations

import json
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Dict, List

import numpy as np

from .geometry import Geometry


def quantize_weight(W: np.ndarray) -> tuple[np.ndarray, float]:
    """Per-tensor ternary quant. Returns (codes int8 in {-1,0,1}, s) for W (M,N)."""
    Wf = np.asarray(W, dtype=np.float64)
    mean_abs = np.abs(Wf).mean()
    s = 1.0 / max(mean_abs, 1e-5)  # clamp mirrors weight_quant's clamp_(min=1e-5)
    codes = np.clip(np.round(Wf * s), -1, 1).astype(np.int8)
    return codes, float(s)


def codes_to_2bit(codes_MN: np.ndarray) -> np.ndarray:
    """(M,N) ternary {-1,0,1} -> (N,M) uint8 2-bit codes (engine [n,m] order)."""
    wt = codes_MN.T.astype(np.int8)             # (N, M): w_t[n,m] = W[m,n]
    return (wt & 0x3).astype(np.uint8)


def pack_projection(codes_MN: np.ndarray, geom: Geometry) -> List[np.ndarray]:
    """Pack a quantized (M,N) projection into `geom.numPorts` per-port byte blobs.

    Each port's blob is col-tile-major: for tile t, beat n, the byte at offset
    (t*N + n)*portBytes packs that port's 64 ternary lanes (4 per byte, lane l at
    bit 2l), where lane l is output column m = t*outLanesPerTile + l.
    """
    bits = codes_to_2bit(codes_MN)              # (N, M) uint8
    N, M = bits.shape
    T = geom.num_col_tiles(M)
    olt = geom.outLanesPerTile
    pad = T * olt - M
    if pad:
        bits = np.concatenate([bits, np.zeros((N, pad), np.uint8)], axis=1)

    # (N, T*olt) -> (N, T, olt) -> (T, N, olt): beat order is [t][n].
    g = bits.reshape(N, T, olt).transpose(1, 0, 2)
    lpp, pb = geom.lanesPerPort, geom.portBytes

    ports: List[np.ndarray] = []
    for p in range(geom.numPorts):
        sl = g[:, :, p * lpp:(p + 1) * lpp]     # (T, N, lpp)
        sl = sl.reshape(T, N, pb, 4)            # 4 lanes per byte
        byte = (sl[..., 0] | (sl[..., 1] << 2) |
                (sl[..., 2] << 4) | (sl[..., 3] << 6)).astype(np.uint8)
        ports.append(np.ascontiguousarray(byte.reshape(-1)))  # (T*N*pb,)
    return ports


@dataclass
class ProjEntry:
    name: str
    N: int                  # inner dim (in_features)
    M: int                  # output dim (out_features)
    s: float                # weight scale = 1/mean|W|
    byte_offset: int        # offset into each per-port resident blob
    blob_bytes: int         # this projection's per-port slice length
    num_col_tiles: int
    n_outputs: int          # M padded up to a col-tile (engine STORE count)


class WeightPacker:
    """Accumulates projections into `numPorts` resident blobs + a manifest.

    Weights are loaded into resident udmabufs once (Phase C); each projection
    sits at its `byte_offset` and is never re-copied — COMPUTE_MM just points at
    the offset. All ports share the same offsets (identical per-port structure).
    """

    def __init__(self, geom: Geometry):
        self.geom = geom
        self._blobs: List[List[np.ndarray]] = [[] for _ in range(geom.numPorts)]
        self._entries: List[ProjEntry] = []
        self._offset = 0

    def add(self, name: str, W: np.ndarray) -> ProjEntry:
        """Quantize and pack one (M,N) weight, appending it to the resident blobs."""
        codes, s = quantize_weight(W)
        return self.add_quantized(name, codes, s)

    def add_quantized(self, name: str, codes_MN: np.ndarray, s: float) -> ProjEntry:
        """Pack an already-quantized (M,N) ternary projection (codes in {-1,0,1}).

        Lets a caller that needs the codes anyway (e.g. the bridge's reference
        backend) quantize once and reuse them for both packing and simulation.
        """
        M, N = codes_MN.shape
        ports = pack_projection(codes_MN, self.geom)
        blob_bytes = ports[0].size
        assert all(pb.size == blob_bytes for pb in ports), "ragged per-port blobs"
        for p in range(self.geom.numPorts):
            self._blobs[p].append(ports[p])
        entry = ProjEntry(
            name=name, N=N, M=M, s=s,
            byte_offset=self._offset, blob_bytes=blob_bytes,
            num_col_tiles=self.geom.num_col_tiles(M),
            n_outputs=self.geom.n_outputs(M),
        )
        self._entries.append(entry)
        self._offset += blob_bytes
        return entry

    @property
    def total_bytes_per_port(self) -> int:
        return self._offset

    def manifest(self) -> Dict:
        return {
            "geometry": {
                "aWidth": self.geom.aWidth, "xDim": self.geom.xDim,
                "numPorts": self.geom.numPorts,
                "outLanesPerTile": self.geom.outLanesPerTile,
                "portBytes": self.geom.portBytes,
            },
            "total_bytes_per_port": self._offset,
            "projections": [asdict(e) for e in self._entries],
        }

    def write(self, out_dir: str | Path, prefix: str = "weights") -> Dict:
        """Emit `<prefix>.port{p}.bin` blobs + `<prefix>.manifest.json` (+ `.tsv`).

        The `.tsv` sidecar is the same projection table in a tab-separated form the
        C++ FPGA runner parses without a JSON dependency; it is generated from the
        same data as the JSON, so the two can't drift.
        """
        out = Path(out_dir)
        out.mkdir(parents=True, exist_ok=True)
        for p in range(self.geom.numPorts):
            blob = (np.concatenate(self._blobs[p]) if self._blobs[p]
                    else np.zeros(0, np.uint8))
            blob.tofile(out / f"{prefix}.port{p}.bin")
        manifest = self.manifest()
        (out / f"{prefix}.manifest.json").write_text(json.dumps(manifest, indent=2))
        (out / f"{prefix}.manifest.tsv").write_text(self._manifest_tsv())
        return manifest

    def _manifest_tsv(self) -> str:
        """Tab-separated projection table for the C++ runner (see write()).

        First line is geometry metadata (`# total_bytes_per_port=<n> numPorts=<n>`),
        then a header comment, then one row per projection. Columns:
            name  byte_offset  N  M  n_outputs  s
        """
        lines = [f"# total_bytes_per_port={self._offset} numPorts={self.geom.numPorts}",
                 "# name\tbyte_offset\tN\tM\tn_outputs\ts"]
        for e in self._entries:
            lines.append(f"{e.name}\t{e.byte_offset}\t{e.N}\t{e.M}\t{e.n_outputs}\t{e.s!r}")
        return "\n".join(lines) + "\n"
