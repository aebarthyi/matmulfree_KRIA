#!/usr/bin/env python3
"""Analytical per-token cost + throughput ceiling for MMfreeLM models on the
mmfree engine — the board-free companion to bench.c's per-token rollup.

The engine streams each ternary weight matrix once per token (2 bits/weight),
so decode is bandwidth-bound: the weight traffic per token divided by the HP-port
stream peak is the throughput ceiling. This reproduces bench.c's byte/floor math
exactly (tile-aware), so the numbers here are the upper bound the board run
approaches (real tok/s sits below it: LOAD/STORE phases and DDR < peak).

Usage:
    scripts/model_numbers.py                      # 370m/1.3b/2.7b at a16, 250 MHz
    scripts/model_numbers.py --clk 250 --batch 1 6
    scripts/model_numbers.py --hidden 4096 --inter 11008 --vocab 32000 --layers 32
    scripts/model_numbers.py --awidth 16 --xdim 32   # geometry override
"""
from __future__ import annotations

import argparse
import math
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..",
                                 "software", "integration"))
from mmfree_pack.geometry import Geometry  # noqa: E402

# Released ridger/MMfreeLM checkpoints (vocab=32000). (hidden, inter, vocab, layers)
MODELS = {
    "370m": (1024, 2816, 32000, 24),
    "1.3b": (2048, 5632, 32000, 24),
    "2.7b": (2560, 6912, 32000, 32),
}


def projections(hidden, inter, vocab, layers):
    """The distinct per-token BitLinear shapes: (name, N inner, M out, count)."""
    return [
        ("ifgo_proj", hidden, hidden,    4 * layers),
        ("gate_up",   hidden, 2 * inter, layers),
        ("down_proj", inter,  hidden,    layers),
        ("lm_head",   hidden, vocab,     1),
    ]


def per_token(geom: Geometry, projs):
    """Weight bytes and MAC count for one forward pass (one token, batch=1)."""
    wbytes = 0
    macs = 0
    for _name, N, M, count in projs:
        tiles = geom.num_col_tiles(M)            # ceil(M / outLanesPerTile)
        wbytes += count * N * tiles * geom.sAxisBytes
        macs += count * N * M
    return wbytes, macs


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--awidth", type=int, default=16)
    ap.add_argument("--xdim", type=int, default=32)
    ap.add_argument("--clk", type=float, default=250.0, help="PL clock MHz (stream peak)")
    ap.add_argument("--batch", type=int, nargs="+", default=[1, 6],
                    help="batch sizes to report (decode amortizes weights x B)")
    ap.add_argument("--hidden", type=int)
    ap.add_argument("--inter", type=int)
    ap.add_argument("--vocab", type=int, default=32000)
    ap.add_argument("--layers", type=int)
    args = ap.parse_args()

    geom = Geometry.derive(aWidth=args.awidth, xDim=args.xdim)
    peak_gbps = geom.numPorts * geom.portBytes * args.clk / 1000.0   # bytes/ns = GB/s
    macs_per_cycle = geom.outLanesPerTile                            # xDim * nLanes

    if args.hidden:
        if not (args.inter and args.layers):
            ap.error("--hidden requires --inter and --layers")
        models = {"custom": (args.hidden, args.inter, args.vocab, args.layers)}
    else:
        models = MODELS

    print(f"# engine: aWidth={geom.aWidth} xDim={geom.xDim}  "
          f"{geom.numPorts} HP ports x {geom.portBytes} B/cycle  "
          f"outLanesPerTile={geom.outLanesPerTile}")
    print(f"# stream peak = {peak_gbps:.1f} GB/s @ {args.clk:.0f} MHz   "
          f"compute peak = {2 * macs_per_cycle * args.clk / 1000:.0f} GOPS "
          f"(x batch)")
    print(f"# decode is bandwidth-bound; tok/s below is the ceiling "
          f"(real run sits lower).\n")

    bcols = "".join(f"  floorTok/s B={b:<2d}" for b in args.batch)
    print(f"{'model':6s} {'hidden':>6s} {'inter':>6s} {'layers':>6s} "
          f"{'MB/token':>9s} {'GMAC/tok':>9s}{bcols}")
    for name, (H, I, V, L) in models.items():
        wbytes, macs = per_token(geom, projections(H, I, V, L))
        mb = wbytes / (1024.0 * 1024.0)
        floor_b1 = peak_gbps * 1e9 / wbytes                          # tok/s at B=1
        bvals = "".join(f"  {floor_b1 * b:>12.1f}" for b in args.batch)
        print(f"{name:6s} {H:>6d} {I:>6d} {L:>6d} {mb:>9.1f} "
              f"{macs / 1e9:>9.2f}{bvals}")

    # Sizing the bitstream for these models.
    print()
    for name, (H, I, V, L) in models.items():
        need_n = 1 << (max(H, I) - 1).bit_length()
        need_m = math.ceil(max(2 * I, V) / geom.outLanesPerTile) * geom.outLanesPerTile
        print(f"# {name}: needs maxN>={need_n} (=nextpow2 of inner {max(H, I)}), "
              f"maxM>={need_m}")


if __name__ == "__main__":
    main()
