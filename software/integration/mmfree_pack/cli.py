"""CLI: pack a checkpoint into per-port weight blobs + a manifest.

    python -m mmfree_pack.cli --checkpoint ridger/MMfreeLM-370M --out-dir packed/
    python -m mmfree_pack.cli --checkpoint model.safetensors --out-dir packed/ \
        --awidth 16 --xdim 32

Needs torch only for --checkpoint loading (the packing itself is torch-free).
"""

from __future__ import annotations

import argparse
import sys

from .geometry import Geometry
from .model import iter_projections, load_checkpoint
from .packer import WeightPacker


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--checkpoint", required=True,
                    help="HF model id/dir, .safetensors, or .bin/.pt checkpoint")
    ap.add_argument("--out-dir", required=True, help="output directory for blobs + manifest")
    ap.add_argument("--prefix", default="weights", help="output filename prefix")
    ap.add_argument("--awidth", type=int, default=16, help="activation bit width (a16 engine = 16)")
    ap.add_argument("--xdim", type=int, default=32, help="systolic array columns (a16 engine = 32)")
    args = ap.parse_args(argv)

    geom = Geometry.derive(args.awidth, args.xdim)
    print(f"geometry: aWidth={geom.aWidth} xDim={geom.xDim} "
          f"numPorts={geom.numPorts} outLanesPerTile={geom.outLanesPerTile} "
          f"portBytes={geom.portBytes}", file=sys.stderr)

    weights = load_checkpoint(args.checkpoint)
    packer = WeightPacker(geom)
    for name, W in iter_projections(weights):
        e = packer.add(name, W)
        print(f"  {name:24s} N={e.N:5d} M={e.M:6d} s={e.s:9.4f} "
              f"@{e.byte_offset:>10d} (+{e.blob_bytes} B/port)", file=sys.stderr)

    manifest = packer.write(args.out_dir, args.prefix)
    total = manifest["total_bytes_per_port"]
    print(f"\n{len(manifest['projections'])} projections, "
          f"{total/1e6:.1f} MB/port, {total*geom.numPorts/1e6:.1f} MB total resident",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
