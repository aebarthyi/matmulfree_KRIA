#!/usr/bin/env python3
"""split_sweep_csv.py — reshape a wide batch-sweep CSV into tidy per-concern sheets.

bench_batch.sh emits ONE row per batch size with every metric as its own column, which is
the right wire format (one row per run, trivially appended) but ~65 columns wide: 19 headline
metrics followed by three columns per profiled op and three per engine phase. That is
unreadable as a table.

This splits one such file into three, keeping the wide original as the raw record:

  <stem>_summary.csv   one row per batch size, headline metrics only — the benchmark table:
                       ttft / prefill / token latency / tok-s. Wide is CORRECT here (each
                       column is a different quantity of the same run).
  <stem>_ops.csv       LONG: one row per (batch, op). Columns: batch, op, ms_per_run, pct,
                       us_per_call. Long, not wide, because the op set is data — this
                       pivots directly and does not change shape when an op appears or goes.
  <stem>_phases.csv    LONG: one row per (batch, engine phase) — pack / load / compute /
                       store / readback, the per-projection cost split.

Identity columns (preset, clk_mhz, hw_batch, packed_weights, model) are carried onto every
row of every sheet, so each file stands alone and several sweeps can be concatenated for
comparison without losing which build a row came from.

  ./split_sweep_csv.py results/*_batch_sweep.csv          # -> alongside each input
  ./split_sweep_csv.py --out-dir tidy/ results/x.csv
"""
import argparse
import csv
import os
import sys

# Identity of the run: repeated on every row of every sheet so each is self-describing.
ID_COLS = ["preset", "clk_mhz", "hw_batch", "packed_weights", "model", "batch"]

# The headline benchmark table, in report order. Anything here that the input lacks is
# skipped rather than emitted empty, so this tolerates older/newer runner versions.
SUMMARY_COLS = [
    "prompt_tokens", "gen_tokens", "reps", "streams_ok",
    "distinct_prompts", "distinct_refs",
    "ttft_ms", "prefill_ms", "prefill_tok_s",
    "token_latency_ms",
    "decode_tok_s", "decode_tok_s_per_stream", "overall_tok_s",
    "decode_s", "total_s",
]

# Per-item metric suffixes, in output order. (column suffix -> output column name)
METRICS = [("ms", "ms_per_run"), ("pct", "pct"), ("us_call", "us_per_call")]

# Wide aggregates that are NOT per-op and so belong on the summary row, not the long sheet.
OP_AGGREGATES = ["op_total_ms", "op_matmul_incl_lmhead_pct", "op_other_pct"]
PHASE_AGGREGATES = ["bl_calls_per_run", "bl_total_ms", "bl_us_call",
                    "bl_act_sync_us_call", "bl_out_sync_us_call",
                    "bl_readback_hidden_ms", "bl_readback_hidden_pct"]


def collect_items(fieldnames, prefix):
    """Find the per-item names under `prefix` that have a full metric set.

    e.g. prefix 'op_' over op_matmul_ms/_pct/_us_call yields 'matmul'. Requires ALL of
    METRICS to be present so an aggregate like op_total_ms (no _pct/_us_call sibling) is not
    mistaken for an item — that is what keeps OP_AGGREGATES off the long sheet.
    """
    names = []
    for f in fieldnames:
        if not f.startswith(prefix) or not f.endswith("_ms"):
            continue
        item = f[len(prefix):-len("_ms")]
        if all(f"{prefix}{item}_{suf}" in fieldnames for suf, _ in METRICS):
            names.append(item)
    return names


def model_slug(value):
    """'/home/ubuntu/transfer/models/370m/model.mmfree' -> '370m'.

    The runner records the blob it loaded, i.e. an absolute board path, which is pure noise
    repeated on every row. The containing directory IS the model identity (models/<slug>/ or
    models/<org>/<name>/), so use that.
    """
    if not value:
        return value
    d = os.path.dirname(value.strip())
    return os.path.basename(d) or value


def split_one(path, out_dir=None):
    with open(path, newline="") as fh:
        rows = list(csv.DictReader(fh))
    if not rows:
        print(f"  !! {path}: no data rows, skipped", file=sys.stderr)
        return []

    fields = list(rows[0].keys())
    if "model" in fields:
        for r in rows:
            r["model"] = model_slug(r["model"])
    ids = [c for c in ID_COLS if c in fields]
    ops = collect_items(fields, "op_")
    phases = collect_items(fields, "bl_")

    stem = os.path.basename(path)
    for suffix in ("_batch_sweep.csv", ".csv"):
        if stem.endswith(suffix):
            stem = stem[: -len(suffix)]
            break
    dest = out_dir or os.path.dirname(path) or "."
    os.makedirs(dest, exist_ok=True)
    written = []

    def write(name, header, records):
        out = os.path.join(dest, f"{stem}_{name}.csv")
        with open(out, "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(header)
            w.writerows(records)
        written.append((out, len(records)))

    # ---- summary: headline metrics + the profiling AGGREGATES (one number each, so they
    # belong on the benchmark table rather than forcing a reader into the long sheets).
    agg = [c for c in OP_AGGREGATES + PHASE_AGGREGATES if c in fields]
    cols = ids + [c for c in SUMMARY_COLS if c in fields] + agg
    write("summary", cols, [[r[c] for c in cols] for r in rows])

    # ---- ops / phases: long, one row per (batch, item).
    for name, prefix, items, label in (("ops", "op_", ops, "op"),
                                       ("phases", "bl_", phases, "phase")):
        if not items:
            continue
        header = ids + [label] + [out for _, out in METRICS]
        recs = [[r[c] for c in ids] + [item] +
                [r[f"{prefix}{item}_{suf}"] for suf, _ in METRICS]
                for r in rows for item in items]
        write(name, header, recs)

    return written


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv", nargs="+", help="wide batch-sweep CSV(s) from bench_batch.sh")
    ap.add_argument("--out-dir", help="write the sheets here (default: beside each input)")
    ap.add_argument("--combine", metavar="PREFIX",
                    help="also concatenate every input's sheets into PREFIX_{summary,ops,"
                         "phases}.csv for cross-build comparison (identity columns keep the "
                         "rows apart). Only sheets sharing a header are combined.")
    args = ap.parse_args()

    rc = 0
    combined = {}   # sheet name -> (header, [rows])
    for path in args.csv:
        if not os.path.isfile(path):
            print(f"  !! {path}: not found", file=sys.stderr)
            rc = 1
            continue
        print(f"{path}")
        try:
            for out, n in split_one(path, args.out_dir):
                print(f"  -> {out}  ({n} rows)")
                if args.combine:
                    sheet = os.path.basename(out).rsplit("_", 1)[-1][: -len(".csv")]
                    with open(out, newline="") as fh:
                        r = list(csv.reader(fh))
                    hdr, body = r[0], r[1:]
                    if sheet not in combined:
                        combined[sheet] = (hdr, [])
                    if combined[sheet][0] != hdr:
                        print(f"  !! {out}: header differs from the other {sheet} sheets, "
                              f"not combined", file=sys.stderr)
                        continue
                    combined[sheet][1].extend(body)
        except (KeyError, csv.Error) as e:
            print(f"  !! {path}: {e}", file=sys.stderr)
            rc = 1

    for sheet, (hdr, body) in combined.items():
        out = f"{args.combine}_{sheet}.csv"
        os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
        with open(out, "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(hdr)
            w.writerows(body)
        print(f"  => {out}  ({len(body)} rows, all builds)")
    return rc


if __name__ == "__main__":
    sys.exit(main())
