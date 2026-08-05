"""Ternary weight statistics for a model.mmfree blob.

Reports the -1 / 0 / +1 distribution of every quantized projection (`.wq`), so
you can see both the sparsity (zero fraction) and the sign balance:

    python scripts/ternary_stats.py transfer/models/ridger/MMfreeLM-370M/model.mmfree
    python scripts/ternary_stats.py <blob> --per-proj      # full per-projection table

Torch-free: reuses the mmfree_pack blob reader.
"""

from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "software" / "integration"))

from mmfree_pack.blob import read_mmfree_blob  # noqa: E402
from mmfree_pack.model import iter_blob_projections  # noqa: E402

_LAYER_RE = __import__("re").compile(r"layers\.(\d+)\.")


def counts(codes: np.ndarray) -> np.ndarray:
    """Return [n_neg, n_zero, n_pos] for an int8 array of {-1,0,1} codes."""
    flat = codes.reshape(-1)
    c = np.bincount(flat.astype(np.int64) + 1, minlength=3)
    if c.sum() != flat.size:  # anything outside {-1,0,1}
        raise ValueError(f"non-ternary codes present (min={flat.min()}, max={flat.max()})")
    return c


def fmt(c: np.ndarray) -> str:
    tot = c.sum()
    neg, zer, pos = (100.0 * c / tot)
    skew = (c[2] - c[0]) / max(tot - c[1], 1)  # sign imbalance among nonzeros
    return (f"{neg:6.2f}% -1  {zer:6.2f}%  0  {pos:6.2f}% +1   "
            f"nnz={100 - zer:6.2f}%  skew={skew:+.4f}")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("blob", help="path to model.mmfree")
    ap.add_argument("--per-proj", action="store_true",
                    help="print every projection instead of per-kind/per-layer rollups")
    args = ap.parse_args(argv)

    _cfg, tensors = read_mmfree_blob(args.blob)

    total = np.zeros(3, dtype=np.int64)
    by_kind: dict[str, np.ndarray] = defaultdict(lambda: np.zeros(3, dtype=np.int64))
    by_layer: dict[int, np.ndarray] = defaultdict(lambda: np.zeros(3, dtype=np.int64))
    rows = []

    for tag, codes, _s in iter_blob_projections(tensors):
        c = counts(codes)
        total += c
        kind = tag.split(".")[-1] if "layers." in tag else tag
        by_kind[kind] += c
        m = _LAYER_RE.search(tag)
        if m:
            by_layer[int(m.group(1))] += c
        rows.append((tag, codes.shape, c))

    if args.per_proj:
        print(f"{'projection':34s} {'shape':>16s}   distribution")
        for tag, shape, c in rows:
            print(f"{tag:34s} {str(tuple(shape)):>16s}   {fmt(c)}")
        print()

    print(f"{'by projection kind':34s} {'params':>16s}   distribution")
    for kind, c in sorted(by_kind.items(), key=lambda kv: -kv[1].sum()):
        print(f"{kind:34s} {c.sum():>16,d}   {fmt(c)}")

    if by_layer and not args.per_proj:
        zf = np.array([100.0 * by_layer[L][1] / by_layer[L].sum() for L in sorted(by_layer)])
        sk = np.array([(by_layer[L][2] - by_layer[L][0]) / max(by_layer[L].sum() - by_layer[L][1], 1)
                       for L in sorted(by_layer)])
        print(f"\nper-layer zero%  min={zf.min():.2f} max={zf.max():.2f} "
              f"mean={zf.mean():.2f} std={zf.std():.2f}")
        print(f"per-layer skew   min={sk.min():+.4f} max={sk.max():+.4f} "
              f"mean={sk.mean():+.4f} std={sk.std():.4f}")

    print(f"\n{'TOTAL':34s} {total.sum():>16,d}   {fmt(total)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
