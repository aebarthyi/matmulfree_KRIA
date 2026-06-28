"""Systolic-array geometry — Python mirror of mmfree_geom_init (mmfree_runtime.c).

The packed weight byte layout is fully determined by (aWidth, xDim): everything
else here is derived exactly as the C runtime derives it, so a blob packed with
this Geometry is byte-identical to what the loaded bitstream consumes. Keep this
in lockstep with mmfree_runtime.c / CoreConfig.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Optional


def _ceil_div(a: int, b: int) -> int:
    return (a + b - 1) // b


def manifest_path(explicit: Optional[str] = None) -> Optional[str]:
    """Resolve the preset.json manifest path: an explicit arg, else the
    MMFREE_MANIFEST env var (a preset.env or preset.json path — we normalize to
    the .json sibling). Returns None if nothing is configured."""
    p = explicit or os.environ.get("MMFREE_MANIFEST")
    if not p:
        return None
    if p.endswith(".env"):
        p = p[:-4] + ".json"
    return p


@dataclass(frozen=True)
class Geometry:
    """Array geometry. Only aWidth and xDim are free; the rest are derived."""

    aWidth: int          # activation bit width (16 for the signed a16 engine)
    xDim: int            # systolic-array columns

    # ---- derived (mirror mmfree_geom_init) ----
    nLanes: int = 0              # aWidth / 2 — ternary lanes per PE
    outLanesPerTile: int = 0     # xDim * nLanes — output cols per col-tile
    sAxisBytes: int = 0          # xDim * aWidth / 8 — bytes per wide s_axis beat
    numPorts: int = 0            # min(4, ceil(bits/128)) — HP-port split
    portBytes: int = 0           # sAxisBytes / numPorts — bytes per port per beat
    lanesPerPort: int = 0        # outLanesPerTile / numPorts

    MAX_PORTS = 4
    PORT_BITS = 128

    @classmethod
    def from_manifest(cls, path: str) -> "Geometry":
        """Derive geometry from a preset.json manifest (control.EmitCore), so the
        packed blob matches the loaded bitstream without hand-passing aWidth/xDim."""
        with open(path) as f:
            m = json.load(f)
        return cls.derive(aWidth=int(m["aWidth"]), xDim=int(m["xDim"]))

    @classmethod
    def derive(cls, aWidth: int, xDim: int) -> "Geometry":
        bits = xDim * aWidth
        if bits % 8 != 0:
            raise ValueError(f"xDim*aWidth = {bits} bits is not a whole number of bytes")
        nLanes = aWidth // 2
        if aWidth % 2 != 0:
            raise ValueError(f"aWidth={aWidth} must be even (2 bits per ternary lane)")
        outLanesPerTile = xDim * nLanes
        sAxisBytes = bits // 8
        numPorts = min(cls.MAX_PORTS, _ceil_div(bits, cls.PORT_BITS))
        if sAxisBytes % numPorts != 0:
            raise ValueError(f"sAxisBytes={sAxisBytes} not divisible across {numPorts} ports")
        if outLanesPerTile % numPorts != 0:
            raise ValueError(f"outLanesPerTile={outLanesPerTile} not divisible across {numPorts} ports")
        portBytes = sAxisBytes // numPorts
        lanesPerPort = outLanesPerTile // numPorts
        # The packing identity the byte loop relies on: 4 ternary codes per byte.
        if portBytes * 4 != lanesPerPort:
            raise ValueError(f"portBytes*4 ({portBytes*4}) != lanesPerPort ({lanesPerPort})")
        return cls(
            aWidth=aWidth, xDim=xDim, nLanes=nLanes,
            outLanesPerTile=outLanesPerTile, sAxisBytes=sAxisBytes,
            numPorts=numPorts, portBytes=portBytes, lanesPerPort=lanesPerPort,
        )

    def acc_width(self, maxAcc: int) -> int:
        """Real accumulator bit width = log2up(maxAcc) + aWidth (mirror C)."""
        r = 0
        while (1 << r) < maxAcc:
            r += 1
        return r + self.aWidth

    def out_lane_bytes(self, maxAcc: int) -> int:
        """Bytes per m_axis output lane = nextPow2(accWidth, min 8) / 8 (mirror C)."""
        aw = self.acc_width(maxAcc)
        w = 8
        while w < aw:
            w <<= 1
        return w // 8

    def num_col_tiles(self, M: int) -> int:
        """Number of col-tiles a projection of output width M is split into."""
        return _ceil_div(M, self.outLanesPerTile)

    def n_outputs(self, M: int) -> int:
        """Output lanes the engine emits for output width M (padded to a tile)."""
        return self.num_col_tiles(M) * self.outLanesPerTile

    def port_blob_bytes(self, N: int, M: int) -> int:
        """Bytes one port's slice occupies for an (N x M) projection."""
        return self.num_col_tiles(M) * N * self.portBytes


# The verified signed-int16 engine (CoreConfig K26_MMFree370M_A16).
A16 = Geometry.derive(aWidth=16, xDim=32)
