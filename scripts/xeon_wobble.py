#!/usr/bin/env python3
"""xeon_wobble.py — quantify the batch-to-batch wobble in a Xeon sweep, and diff two sets.

    ./scripts/xeon_wobble.py --a reports/host_cpu/final
    ./scripts/xeon_wobble.py --a reports/host_cpu/final --b reports/host_cpu/pure_20260816

Assumes an UNBATCHED CPU arm, whose per-stream cost should be flat in b. Per (model, encoding,
threads) curve it reports:

  pass spread   the largest disagreement between passes at one batch -- the noise floor
  batch spread  the largest disagreement between batches of the per-stream cost -- the wobble

A ratio well above 1 means the curve is structured, not noisy (contention perturbs passes,
not batches).
"""
import argparse
import collections
import csv
import glob
import os
import statistics
import sys

FMT = {'1.6250': '1p6', '2.0000': '2bit', '8.0000': 'int8'}


def load(root):
    """-> {(model, fmt, threads): {batch: [per-stream ms, one per pass]}}"""
    out = collections.defaultdict(lambda: collections.defaultdict(list))
    pat = os.path.join(root, '*', 't*_p*', 'host_cpu_sweep.csv')
    files = sorted(glob.glob(pat))
    if not files:
        sys.exit(f"no host_cpu_sweep.csv under {root}/<model>/t<n>_p<n>/")
    for f in files:
        model = os.path.basename(os.path.dirname(os.path.dirname(f)))
        for r in csv.DictReader(open(f)):
            fmt = FMT.get(r['cpu_wt_bits'])
            if fmt is None:
                continue
            b = int(r['batch'])
            lat = float(r['token_latency_ms'])
            # token_latency_ms is the whole step for all b streams; per-stream is the
            # quantity that should be invariant on an unbatched arm.
            out[(model, fmt, int(r['omp_threads']))][b].append(lat / b)
    return out


def spreads(by_batch):
    """(pass spread %, batch spread %, per-batch medians) for one curve."""
    med = {b: statistics.median(v) for b, v in sorted(by_batch.items())}
    pass_s = max((max(v) - min(v)) / statistics.median(v) * 100
                 for v in by_batch.values() if len(v) > 1) if any(
                     len(v) > 1 for v in by_batch.values()) else float('nan')
    vals = list(med.values())
    batch_s = (max(vals) - min(vals)) / statistics.median(vals) * 100
    return pass_s, batch_s, med


def report(name, data):
    print(f"\n=== {name}")
    print(f"{'model':6} {'fmt':5} {'thr':>4} {'pass spread':>12} {'batch spread':>13} "
          f"{'ratio':>7}   worst b")
    rows = {}
    for key in sorted(data):
        model, fmt, thr = key
        ps, bs, med = spreads(data[key])
        lo = min(med, key=med.get)
        hi = max(med, key=med.get)
        ratio = bs / ps if ps and ps == ps else float('nan')
        print(f"{model:6} {fmt:5} {thr:>4} {ps:11.2f}% {bs:12.2f}% {ratio:7.1f}   "
              f"b={hi} slowest ({med[hi]:.2f} ms/stream), b={lo} fastest ({med[lo]:.2f})")
        rows[key] = (ps, bs, med)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--a', required=True, help='baseline sweep root')
    ap.add_argument('--b', help='second sweep root to compare against --a')
    args = ap.parse_args()

    a = report(args.a, load(args.a))
    if not args.b:
        print("\nPass spread is the noise floor; batch spread is the wobble. A ratio well "
              "above 1 means\nthe curve is structured, not noisy.")
        return

    b = report(args.b, load(args.b))
    print(f"\n=== change, {args.a} -> {args.b}")
    print(f"{'model':6} {'fmt':5} {'thr':>4} {'batch spread':>22} {'median ms/stream':>22}")
    shared = sorted(set(a) & set(b))
    if not shared:
        print("  (no (model, fmt, threads) key in common — different thread sets?)")
    for key in shared:
        model, fmt, thr = key
        _, bs_a, med_a = a[key]
        _, bs_b, med_b = b[key]
        ma = statistics.median(med_a.values())
        mb = statistics.median(med_b.values())
        print(f"{model:6} {fmt:5} {thr:>4} {bs_a:9.2f}% -> {bs_b:8.2f}% "
              f"{ma:11.2f} -> {mb:8.2f}  ({100 * (mb - ma) / ma:+.1f}% cost)")
    print("\nBatch spread collapsing = the wobble was the host. Batch spread unchanged = it "
          "is in the\nkernel's decomposition. Watch the cost column too: a pinned run that "
          "is also SLOWER has\nbought reproducibility by understating the baseline, which is "
          "the wrong trade for a comparator.")


if __name__ == '__main__':
    main()
