"""CLI: pack model weights into per-port engine blobs + a manifest.

Two sources:

    # from the C++ runtime's model.mmfree blob (already-ternary wq; PREFERRED for
    # the C++↔FPGA path — engine codes stay identical to the CPU FixedQ510 path):
    python -m mmfree_pack.cli --blob ../../matmulfreellmCPU/cpp/model.mmfree \
        --out-dir packed/

    # from an HF checkpoint (re-quantizes float weights; needs torch):
    python -m mmfree_pack.cli --checkpoint ridger/MMfreeLM-370M --out-dir packed/

--blob is torch-free. --checkpoint needs torch only for loading. Both default to
the verified a16 engine geometry (aWidth=16, xDim=32).
"""

from __future__ import annotations

import argparse
import sys

from .blob import read_mmfree_blob
from .geometry import Geometry, manifest_path
from .model import iter_blob_projections, iter_projections, load_checkpoint
from .packer import WeightPacker


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--blob", help="model.mmfree blob (pack already-quantized wq directly)")
    src.add_argument("--checkpoint",
                     help="HF model id/dir, .safetensors, or .bin/.pt checkpoint")
    ap.add_argument("--out-dir", required=True, help="output directory for blobs + manifest")
    ap.add_argument("--prefix", default="weights", help="output filename prefix")
    ap.add_argument("--awidth", type=int, default=16, help="activation bit width (a16 engine = 16)")
    ap.add_argument("--xdim", type=int, default=32, help="systolic array columns (a16 engine = 32)")
    ap.add_argument("--manifest", default=None,
                    help="preset.json/.env from control.EmitCore — geometry is read "
                         "from it (overrides --awidth/--xdim). Defaults to $MMFREE_MANIFEST.")
    args = ap.parse_args(argv)

    mpath = manifest_path(args.manifest)
    geom = Geometry.from_manifest(mpath) if mpath else Geometry.derive(args.awidth, args.xdim)
    if mpath:
        print(f"geometry from manifest: {mpath}", file=sys.stderr)
    print(f"geometry: aWidth={geom.aWidth} xDim={geom.xDim} "
          f"numPorts={geom.numPorts} outLanesPerTile={geom.outLanesPerTile} "
          f"portBytes={geom.portBytes}", file=sys.stderr)

    packer = WeightPacker(geom)
    if args.blob:
        _cfg, tensors = read_mmfree_blob(args.blob)
        for name, codes, s in iter_blob_projections(tensors):
            e = packer.add_quantized(name, codes, s)
            print(f"  {name:28s} N={e.N:5d} M={e.M:6d} s={e.s:9.4f} "
                  f"@{e.byte_offset:>10d} (+{e.blob_bytes} B/port)", file=sys.stderr)
    else:
        weights = load_checkpoint(args.checkpoint)
        for name, W in iter_projections(weights):
            e = packer.add(name, W)
            print(f"  {name:28s} N={e.N:5d} M={e.M:6d} s={e.s:9.4f} "
                  f"@{e.byte_offset:>10d} (+{e.blob_bytes} B/port)", file=sys.stderr)

    manifest = packer.write(args.out_dir, args.prefix)
    total = manifest["total_bytes_per_port"]
    print(f"\n{len(manifest['projections'])} projections, "
          f"{total/1e6:.1f} MB/port, {total*geom.numPorts/1e6:.1f} MB total resident",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
