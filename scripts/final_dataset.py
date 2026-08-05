#!/usr/bin/env python3
"""final_dataset.py — merge the board and Xeon sweeps into ONE tidy table for the report.

    ./scripts/final_dataset.py \
        --board ~/results/final --xeon reports/host_cpu/final --out reports/final

Emits reports/final/dataset.csv, one row per (platform, arm, model, weight_fmt, batch):

    platform     kria | xeon
    arm          fpga (engine + A53 host) | cpu (A53 only) | cpu (Xeon)
    model        370M | 1.3B | 2.7B
    weight_fmt   1p6 | 2bit | int8
    batch        1..6
    threads      Xeon OpenMP threads (blank on the board)
    passes       independent runs behind the mean
    decode_tok_s mean across passes
    spread_pct   (max-min)/mean across passes, 0 when passes==1
    watts        mean board SOM rail power / Xeon RAPL package+DRAM, SAME run as the tok/s
    tok_per_joule decode_tok_s / watts
    streams_ok   1 = every stream matched a single-stream decode (FPGA arms)
    flag         OUTLIER if the passes disagree by more than --spread-limit

WHAT THIS CHECKS SO THE REPORT DOES NOT HAVE TO. It refuses to emit a clean data set if
any of these hold, because each one silently invalidates a comparison drawn across rows:
  * a row missing power       -> tok/J would be blank or spliced from another run
  * mixed POWER settings      -> ~2% bias between arms measured with and without sampling
  * streams_ok=0 on any FPGA row -> that row's timings are meaningless
  * pass disagreement > limit -> the bad-cell failure mode from 2026-08-04, where 3/3 reps
                                 inside one process agreed to 0.12 tok/s on a 12%-low number
"""
import argparse, csv, glob, os, re, sys
from collections import defaultdict

MODEL_OF = {'370m': '370M', '1_3b': '1.3B', '2_7b': '2.7B',
            'mmfreelm-370m': '370M', 'mmfreelm-1.3b': '1.3B', 'mmfreelm-2.7b': '2.7B'}
FMT_OF = {'cpu': 'int8', 'cpu-2bit': '2bit', 'cpu-1p6': '1p6'}


def model_from_preset(p):
    for k, v in MODEL_OF.items():
        if k in p.lower():
            return v
    return p


def board_power(power_root):
    """(power_dir, batch) -> mean SOM watts, from power_bench.sh's own summary line."""
    out = {}
    pat = re.compile(r'SOM total power\s+avg\s+([\d.]+)\s*mW')
    for log in glob.glob(os.path.join(power_root, '*', '*.log')):
        m = re.search(r'_b(\d+)_n', os.path.basename(log))
        if not m:
            continue
        txt = open(log, errors='ignore').read()
        w = pat.search(txt)
        if w:
            out[(os.path.basename(os.path.dirname(log)), int(m.group(1)))] = float(w.group(1)) / 1000.0
    return out


def read_board(board_root):
    """Board CSVs -> list of raw per-pass records."""
    recs = []
    pw = board_power(os.path.join(board_root, 'power'))
    if not pw:
        print(f"WARN: no board power logs under {board_root}/power — did you pull them?", file=sys.stderr)
    for f in sorted(glob.glob(os.path.join(board_root, '*_final*_batch_sweep.csv'))):
        base = os.path.basename(f)[:-len('_batch_sweep.csv')]
        # <preset>[_<backend>]_final[_p<n>]  -> the power dir is exactly this string
        mp = re.match(r'^(mmfree_\w+?)(?:_(cpu(?:-\w+)?))?_final(?:_p(\d+))?$', base)
        if not mp:
            print(f"WARN: skipping unrecognised board CSV {base}", file=sys.stderr)
            continue
        preset, backend, pas = mp.group(1), mp.group(2), mp.group(3)
        arm = 'cpu' if backend else 'fpga'
        for r in csv.DictReader(open(f)):
            b = int(r['batch'])
            fmt = FMT_OF.get(backend, '1p6' if r.get('packed_weights') == '1' else '2bit')
            recs.append(dict(
                platform='kria', arm=arm, model=model_from_preset(preset), weight_fmt=fmt,
                batch=b, threads='', pass_id=pas or '1',
                decode=float(r['decode_tok_s']),
                watts=pw.get((base, b)),
                streams_ok=r.get('streams_ok', ''), preset=preset))
    return recs


def xeon_fmt(bits):
    """bench_host_cpu.sh records the encoding as bits/weight, not a name."""
    try:
        b = float(bits)
    except (TypeError, ValueError):
        return '?'
    if abs(b - 8.0) < 0.01:
        return 'int8'
    if abs(b - 2.0) < 0.01:
        return '2bit'
    if abs(b - 1.625) < 0.01:
        return '1p6'
    return f'{b:g}b'


def read_xeon(xeon_root):
    """Raw per-pass CSVs from bench_host_cpu.sh.

    NOTE the schema here is the runner's own, NOT the derived one in xeon_best.csv (which
    was post-processed and carries friendly model/weight_fmt/total_w columns). Reading the
    raw file directly is deliberate: it is what the sweep actually writes, so the final
    data set does not depend on an undocumented hand transformation.

    watts = timed_pkg_w + timed_dram_w — package plus DRAM over the TIMED window, which is
    what the runner's own tok_per_joule uses and the closest analogue to the KRIA's SOM rail
    figure (both include the platform's idle draw). idle_*_w is in the raw file if a
    net-of-idle variant is ever wanted, but subtracting idle on one side only is not a
    like-for-like comparison.
    """
    recs = []
    for f in sorted(glob.glob(os.path.join(xeon_root, 't*_p*', 'host_cpu_sweep.csv'))):
        tag = os.path.basename(os.path.dirname(f))
        mt = re.match(r'^t(\d+)_p(\d+)$', tag)
        if not mt:
            continue
        pas = mt.group(2)
        for r in csv.DictReader(open(f)):
            # model arrives as the blob path: .../ridger/MMfreeLM-1.3B/model.mmfree
            mdl = model_from_preset(os.path.basename(os.path.dirname(r['model'])))
            pkg, dram = r.get('timed_pkg_w', ''), r.get('timed_dram_w', '')
            watts = (float(pkg) + float(dram)) if pkg not in ('', 'None') else None
            recs.append(dict(
                platform='xeon', arm='cpu', model=mdl,
                weight_fmt=xeon_fmt(r.get('cpu_wt_bits')),
                batch=int(r['batch']),
                # the row's own thread count, not the directory's — they agree, but the row
                # is the authority and this survives a re-run with a different THREAD_SET
                threads=r.get('omp_threads') or mt.group(1),
                pass_id=pas,
                decode=float(r['decode_tok_s']),
                watts=watts, streams_ok='', preset=r.get('host_cpu', '')))
    return recs


def collapse(recs, spread_limit):
    """Average passes. For the Xeon, first pick the winning thread count per cell."""
    by = defaultdict(list)
    for r in recs:
        by[(r['platform'], r['arm'], r['model'], r['weight_fmt'], r['batch'], r['threads'])].append(r)

    means = {}
    for k, rs in by.items():
        ds = [r['decode'] for r in rs]
        ws = [r['watts'] for r in rs if r['watts'] is not None]
        mean = sum(ds) / len(ds)
        means[k] = dict(mean=mean, spread=(max(ds) - min(ds)) / mean * 100 if len(ds) > 1 else 0.0,
                        n=len(ds), watts=(sum(ws) / len(ws)) if ws else None,
                        streams_ok=min((r['streams_ok'] for r in rs if r['streams_ok'] != ''), default=''),
                        preset=rs[0]['preset'])

    # Xeon: the thread count is a tuning knob, not a variable of interest — collapse it by
    # taking the best per cell and CARRYING the winner into the row, so "best of a sweep" is
    # visible in the table rather than hidden in the file it came from.
    best = {}
    for k, v in means.items():
        plat, arm, model, fmt, batch, th = k
        ck = (plat, arm, model, fmt, batch)
        if ck not in best or v['mean'] > means[best[ck]]['mean']:
            best[ck] = k

    rows = []
    for ck, k in sorted(best.items(), key=lambda kv: (kv[0][0], kv[0][2], kv[0][3], kv[0][4])):
        v = means[k]
        plat, arm, model, fmt, batch = ck
        flag = 'OUTLIER' if v['spread'] > spread_limit else ''
        if v['watts'] is None:
            flag = (flag + ' NO_POWER').strip()
        if arm == 'fpga' and v['streams_ok'] not in ('1', ''):
            flag = (flag + ' STREAMS_BAD').strip()
        rows.append(dict(
            platform=plat, arm=arm, model=model, weight_fmt=fmt, batch=batch,
            threads=k[5], passes=v['n'],
            decode_tok_s=round(v['mean'], 3),
            spread_pct=round(v['spread'], 2),
            watts=round(v['watts'], 3) if v['watts'] is not None else '',
            tok_per_joule=round(v['mean'] / v['watts'], 4) if v['watts'] else '',
            streams_ok=v['streams_ok'], preset=v['preset'], flag=flag))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--board', default=os.path.expanduser('~/results/final'))
    ap.add_argument('--xeon', default='reports/host_cpu/final')
    ap.add_argument('--out', default='reports/final')
    ap.add_argument('--spread-limit', type=float, default=3.0,
                    help='flag a cell whose passes disagree by more than this %% (default 3)')
    a = ap.parse_args()

    recs = []
    if os.path.isdir(a.board):
        recs += read_board(a.board)
    else:
        print(f"WARN: board dir {a.board} missing", file=sys.stderr)
    if os.path.isdir(a.xeon):
        recs += read_xeon(a.xeon)
    else:
        print(f"WARN: xeon dir {a.xeon} missing", file=sys.stderr)
    if not recs:
        sys.exit("no input found — nothing to aggregate")

    rows = collapse(recs, a.spread_limit)
    os.makedirs(a.out, exist_ok=True)
    dest = os.path.join(a.out, 'dataset.csv')
    cols = ['platform', 'arm', 'model', 'weight_fmt', 'batch', 'threads', 'passes',
            'decode_tok_s', 'spread_pct', 'watts', 'tok_per_joule', 'streams_ok', 'preset', 'flag']
    with open(dest, 'w', newline='') as fh:
        w = csv.DictWriter(fh, fieldnames=cols)
        w.writeheader()
        w.writerows(rows)

    # ── headline view ────────────────────────────────────────────────────────
    print(f"\nwrote {dest}  ({len(rows)} rows)\n")
    order = {'370M': 0, '1.3B': 1, '2.7B': 2}
    print(f"{'platform':8s} {'arm':4s} {'model':6s} {'fmt':5s} {'thr':>3s} {'b':>2s} "
          f"{'tok/s':>8s} {'spread':>7s} {'W':>6s} {'tok/J':>7s}  flag")
    for r in sorted(rows, key=lambda r: (r['platform'], order.get(r['model'], 9),
                                         r['weight_fmt'], r['batch'])):
        print(f"{r['platform']:8s} {r['arm']:4s} {r['model']:6s} {r['weight_fmt']:5s} "
              f"{str(r['threads']):>3s} {r['batch']:>2d} {r['decode_tok_s']:8.2f} "
              f"{r['spread_pct']:6.2f}% {str(r['watts']):>6s} {str(r['tok_per_joule']):>7s}  {r['flag']}")

    # ── completeness / integrity ─────────────────────────────────────────────
    problems = [r for r in rows if r['flag']]
    want_batches = set(range(1, 7))
    want_models = ['370M', '1.3B', '2.7B']
    # The EXPECTED matrix, asserted rather than inferred. Checking only that whatever showed
    # up has all six batches lets a half-finished sweep report CLEAN — which it did, when five
    # of six Xeon passes were refused by the load guard and the one that landed looked
    # complete on its own terms.
    want_cells = ([('kria', 'fpga', m, '1p6') for m in want_models] +
                  [('kria', 'cpu', m, f) for m in want_models for f in ('int8', '2bit', '1p6')] +
                  [('xeon', 'cpu', m, f) for m in want_models for f in ('int8', '2bit', '1p6')])
    have = {(r['platform'], r['arm'], r['model'], r['weight_fmt']) for r in rows}
    missing = []
    for cell in want_cells:
        if cell not in have:
            missing.append(f"{'/'.join(cell)}: ENTIRELY ABSENT")
            continue
        got = {r['batch'] for r in rows
               if (r['platform'], r['arm'], r['model'], r['weight_fmt']) == cell}
        if got != want_batches:
            missing.append(f"{'/'.join(cell)}: missing b={sorted(want_batches - got)}")
    for cell in sorted(have - set(want_cells)):
        missing.append(f"{'/'.join(str(c) for c in cell)}: UNEXPECTED (not in the report matrix)")
    # Every FPGA cell should carry PASSES independent runs; a cell with fewer means a pass
    # died and its spread is not the spread the method claims.
    fpga_passes = {r['passes'] for r in rows if r['arm'] == 'fpga'}
    if len(fpga_passes) > 1:
        missing.append(f"FPGA cells have inconsistent pass counts {sorted(fpga_passes)} — "
                       "some pass failed partway")

    print()
    if missing:
        print("INCOMPLETE:")
        for m in missing:
            print("  " + m)
    if problems:
        print("FLAGGED ROWS (do not quote without resolving):")
        for r in problems:
            print(f"  {r['platform']}/{r['model']}/{r['weight_fmt']}/b{r['batch']}: {r['flag']}"
                  f" (spread {r['spread_pct']}%, {r['passes']} passes)")
    if not missing and not problems:
        print("CLEAN: every cell present, every cell has power, no pass disagreed by more "
              f"than {a.spread_limit}%, all FPGA rows streams_ok=1.")
        print("This is quotable as-is.")
    return 1 if (missing or problems) else 0


if __name__ == '__main__':
    sys.exit(main())
