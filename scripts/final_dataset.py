#!/usr/bin/env python3
"""final_dataset.py — merge the board and Xeon sweeps into one tidy table for the report.

    ./scripts/final_dataset.py \
        --board ~/results/final --xeon reports/host_cpu/final --out reports/final

Writes under --out:
  dataset.csv      one row per (platform, arm, model, weight_fmt, batch); DATASET_COLS is the schema
  ops_<model>.csv  per-model op/phase profiling breakdown, long format (one row per op)

decode_tok_s is the median across passes, aggregated over streams. tok_per_joule is
decode_tok_s / watts_net (total minus that platform's own idle draw); a row with no idle
sample falls back to total and is flagged NO_IDLE, so a table never mixes denominators silently.

Exits non-zero, listing why, on: a row missing power, an unstable median (jackknife shift >
--spread-limit), streams_ok=0 on an FPGA row, a missing or unexpected cell, or inconsistent
FPGA pass counts. The expected batch ladder is read per model from the sweeps (see ladders()).
"""
import argparse, csv, datetime, glob, os, re, sys
from collections import defaultdict

# ── output schema ─────────────────────────────────────────────────────────────────────
# Consumers read this column order, so new columns are appended.
DATASET_COLS = [
    # identity / provenance
    'date', 'platform', 'arm', 'model', 'weight_fmt', 'batch', 'threads', 'preset',
    'clk_mhz', 'fclk_hz', 'passes',
    # workload shape
    'prompt_tokens', 'gen_tokens',
    # latency
    'ttft_ms', 'prefill_ms', 'prefill_tok_s', 'token_latency_ms',
    # throughput
    'decode_tok_s', 'decode_tok_s_per_stream',
    # power / efficiency
    'watts_total', 'watts_idle', 'watts_net', 'power_domain', 'tok_per_joule',
    # quality
    'mean_tok_s', 'spread_pct', 'median_shift_pct', 'streams_ok', 'flag',
]

OPS_COLS = ['date', 'platform', 'arm', 'model', 'weight_fmt', 'batch', 'threads',
            'kind', 'op', 'ms_per_run', 'pct', 'us_per_call']

MODEL_OF = {'370m': '370M', '1_3b': '1.3B', '2_7b': '2.7B',
            'mmfreelm-370m': '370M', 'mmfreelm-1.3b': '1.3B', 'mmfreelm-2.7b': '2.7B'}
FMT_OF = {'cpu': 'int8', 'cpu-2bit': '2bit', 'cpu-1p6': '1p6'}


def model_from_preset(p):
    for k, v in MODEL_OF.items():
        if k in p.lower():
            return v
    return p


def idle_samples(samples_path):
    """power_bench.sh raw .samples -> [(epoch_s, mW)], one per '@<epoch>' record."""
    out, ts = [], None
    for line in open(samples_path, errors='ignore'):
        if line.startswith('@'):
            ts = float(line[1:])
            continue
        m = re.match(r'SOM total power\s*:\s*([\d.]+)\s*mW', line)
        if m and ts is not None:
            out.append((ts, float(m.group(1))))
            ts = None
    return out


def drop_idle_samples(idle_dir, idle_logs, drop):
    """Recompute the idle mean from the raw samples, excluding the DECLARED epochs.

    -> (idle_w, problems). Every declared epoch must match exactly one sample, and the
    all-samples mean must reproduce the summary log's avg. Otherwise a stale flag carried onto
    another set would quietly do nothing, or a parse slip would quietly move the denominator.
    """
    problems, means, hits = [], [], {e: [] for e in drop}
    for log, logged_w in idle_logs:
        raw = log[:-len('.log')] + '.samples'
        if not os.path.exists(raw):
            problems.append(f"--idle-drop-sample: {os.path.basename(raw)} missing — cannot drop "
                            "individual samples from a summary log")
            continue
        s = idle_samples(raw)
        if not s or abs(sum(w for _, w in s) / len(s) / 1000.0 - logged_w) > 0.0005:
            problems.append(f"--idle-drop-sample: {os.path.basename(raw)} does not reproduce its "
                            f"log's avg {logged_w:.4f} W — refusing to recompute from it")
            continue
        keep = []
        for t, w in s:
            if int(t) in hits:
                hits[int(t)].append((os.path.basename(raw), t, w))
            else:
                keep.append(w)
        if not keep:
            problems.append(f"--idle-drop-sample would drop every sample of {os.path.basename(raw)}")
            continue
        means.append(sum(keep) / len(keep) / 1000.0)
        print(f"kria idle: {os.path.basename(raw)}: n={len(keep)} of {len(s)} kept, "
              f"{logged_w:.3f} W as logged -> {means[-1]:.3f} W", file=sys.stderr)
    for e, h in sorted(hits.items()):
        if len(h) != 1:
            problems.append(f"--idle-drop-sample {e}: matches {len(h)} idle samples under "
                            f"{idle_dir} (want exactly 1) — wrong set, or a stale flag?")
            continue
        f, t, w = h[0]
        when = datetime.datetime.fromtimestamp(t, datetime.timezone.utc).strftime('%H:%M:%S UTC')
        print(f"kria idle: DROPPED sample @{t:.3f} ({when}, {w:.0f} mW) from {f} — declared "
              "via --idle-drop-sample", file=sys.stderr)
    if problems:
        return None, problems
    return sum(means) / len(means), []


def board_power(power_root, drop=(), idle_max_w=None):
    """Board rail power from power_bench.sh summary lines -> (workload, idle, problems).

    workload maps (power_dir, batch) -> mean SOM watts; idle is the mean SOM watts of the
    `_idle` logs in power/idle/, or None. Idle logs are matched on BOTH the _idle suffix (they
    also carry a _b<N>_ tag) and the directory being exactly idle/: archived baselines parked
    in power/idle_<x>/ are reported, never averaged into the denominator.

    drop: epochs (int seconds) of idle samples to exclude, recomputed from the raw .samples.
    idle_max_w: a baseline above it is a problem. A bitstream left resident after a sweep
    (xmutil unloadapp does not clear the fabric) reads ~0.9 W over a fresh-boot idle.
    """
    out, idle, archived, idle_logs, problems = {}, [], {}, [], []
    pat = re.compile(r'SOM total power\s+avg\s+([\d.]+)\s*mW')
    for log in glob.glob(os.path.join(power_root, '*', '*.log')):
        base = os.path.basename(log)
        parent = os.path.basename(os.path.dirname(log))
        txt = open(log, errors='ignore').read()
        w = pat.search(txt)
        if not w:
            continue
        watts = float(w.group(1)) / 1000.0
        if base.endswith('_idle.log'):
            if parent == 'idle':
                idle.append(watts)
                idle_logs.append((log, watts))
            else:
                archived.setdefault(parent, []).append(watts)
            continue
        m = re.search(r'_b(\d+)_n', base)
        if m:
            out[(parent, int(m.group(1)))] = watts
    idle_w = (sum(idle) / len(idle)) if idle else None
    if drop:
        if not idle_logs:
            problems.append("--idle-drop-sample given but there is no power/idle/ baseline")
        else:
            idle_w, problems = drop_idle_samples(os.path.join(power_root, 'idle'), idle_logs, drop)
    for name, ws in sorted(archived.items()):
        w = sum(ws) / len(ws)
        delta = f", {100 * (w - idle_w) / idle_w:+.2f}% vs this run" if idle_w else ""
        print(f"note: archived idle baseline power/{name}/ = {w:.3f} W{delta} "
              f"— NOT averaged into the denominator", file=sys.stderr)
    if idle_w is not None and idle_max_w is not None and idle_w > idle_max_w:
        problems.append(f"kria idle baseline {idle_w:.3f} W > --idle-max-w {idle_max_w} W: a "
                        "bitstream resident in the fabric (idle sampled after a sweep without a "
                        "reboot?) would inflate every board tok/J. Re-sample from a fresh boot; "
                        "raise --idle-max-w only if this really is the board's idle")
    return out, idle_w, problems


def fnum(r, key):
    """Float from a CSV field, or None when absent/blank/unparseable (never a fake 0)."""
    try:
        v = r.get(key, '')
        return float(v) if v not in ('', None, '-') else None
    except (TypeError, ValueError):
        return None


def num(v, nd):
    """Round for output, or an empty cell when the quantity was never measured."""
    return round(v, nd) if v is not None else ''


# Profiling columns for ops_<model>.csv: op_* exist on both platforms, bl_* (BitLinear
# engine phases) only on the FPGA arm.
OPS = ['elementwise', 'gate', 'lm_head', 'matmul', 'rmsnorm', 'scan', 'swiglu', 'swishgate']
PHASES = ['pack', 'load', 'compute', 'store', 'readback']


def read_profile(r):
    """Pull the op/phase breakdown out of one sweep row -> list of dicts (long format)."""
    prof = []
    for op in OPS:
        ms = fnum(r, f'op_{op}_ms')
        if ms is None:
            continue
        prof.append(dict(kind='op', op=op, ms_per_run=ms,
                         pct=fnum(r, f'op_{op}_pct'), us_per_call=fnum(r, f'op_{op}_us_call')))
    for ph in PHASES:
        ms = fnum(r, f'bl_{ph}_ms')
        if ms is None:
            continue
        prof.append(dict(kind='phase', op=ph, ms_per_run=ms,
                         pct=fnum(r, f'bl_{ph}_pct'), us_per_call=fnum(r, f'bl_{ph}_us_call')))
    return prof


def metrics(r):
    """The measured quantities every platform reports, under one set of names."""
    return dict(
        decode=float(r['decode_tok_s']),
        decode_per_stream=fnum(r, 'decode_tok_s_per_stream'),
        # ttft_ms = first stream's first token; prefill_ms = the last stream's (whole prefill).
        ttft_ms=fnum(r, 'ttft_ms'),
        prefill_ms=fnum(r, 'prefill_ms'),
        prefill_tok_s=fnum(r, 'prefill_tok_s'),
        token_latency_ms=fnum(r, 'token_latency_ms'),
        prompt_tokens=fnum(r, 'prompt_tokens'),
        gen_tokens=fnum(r, 'gen_tokens'),
        profile=read_profile(r),
    )


def read_board(board_root, idle_drop=(), idle_max_w=None):
    """Board CSVs -> (list of raw per-pass records, idle-baseline problems)."""
    recs = []
    pw, idle_w, idle_problems = board_power(os.path.join(board_root, 'power'),
                                            idle_drop, idle_max_w)
    if not pw:
        print(f"WARN: no board power logs under {board_root}/power — did you pull them?", file=sys.stderr)
    if idle_w is None and idle_problems:
        print("kria idle baseline: REFUSED (listed at the end) — kria tok/J falls back to TOTAL "
              "draw and every kria row is flagged NO_IDLE until it is resolved", file=sys.stderr)
    elif idle_w is None:
        print(f"WARN: no idle baseline under {board_root}/power/idle — tok/J will fall back "
              "to TOTAL draw for the kria rows, which is not comparable to the Xeon's "
              "net-of-idle. Re-run final_sweep_board.sh (it samples one per sweep).",
              file=sys.stderr)
    else:
        # Always printed: every kria tok/J is net of this number.
        print(f"kria idle baseline: {idle_w:.3f} W (power/idle/) — every kria tok/J is net "
              "of this", file=sys.stderr)
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
                power_domain='board_rails', watts_idle=idle_w,
                clk_mhz=r.get('clk_mhz', ''), fclk_hz=r.get('fclk_hz', ''),
                **metrics(r),
                watts=pw.get((base, b)),
                # hw_batch_max = the bitstream's compile-time batchSize, i.e. the expected
                # ladder for every arm of this model (hw_batch is just the per-run batch).
                hw_batch=int(r['hw_batch_max']) if r.get('hw_batch_max') else None,
                streams_ok=r.get('streams_ok', ''), preset=preset))
    return recs, idle_problems


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
    """Raw per-pass CSVs from bench_host_cpu.sh (its own schema, not the derived xeon_best.csv).

    watts_total = timed pkg + dram (RAPL, the analogue of the SOM rail); watts_idle = idle
    pkg + dram sampled before the workload, so tok/J is net of idle on both platforms.
    """
    recs = []
    # <root>/<model>/t<th>_p<n>/ (per-model GEN), or the older flat <root>/t<th>_p<n>/.
    files = (glob.glob(os.path.join(xeon_root, 't*_p*', 'host_cpu_sweep.csv')) +
             glob.glob(os.path.join(xeon_root, '*', 't*_p*', 'host_cpu_sweep.csv')))
    for f in sorted(set(files)):
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
                # the row's own thread count is authoritative over the directory name
                threads=r.get('omp_threads') or mt.group(1),
                pass_id=pas,
                power_domain='rapl_pkg+dram',
                watts_idle=((float(r['idle_pkg_w']) + float(r.get('idle_dram_w') or 0))
                            if r.get('idle_pkg_w') not in ('', None, 'None') else None),
                clk_mhz=r.get('clk_mhz', ''), fclk_hz=r.get('fclk_hz', ''),
                **metrics(r),
                # no hardware ceiling; the expected ladder comes from the board's
                hw_batch=None,
                watts=watts, streams_ok='', preset=r.get('host_cpu', '')))
    return recs


def ladders(recs, override=None):
    """model -> the set of batches every arm measuring it is expected to cover.

    The ceiling is the bitstream's batchSize, read per model from hw_batch_max; the CPU arms
    inherit the FPGA's ladder so every FPGA row has a denominator. Never inferred from the
    batches present (a half-finished sweep always covers itself): no hw_batch_max, no ladder.

    Returns (ladder_by_model, problems).
    """
    problems = []
    seen = defaultdict(set)
    for r in recs:
        if r.get('hw_batch'):
            seen[r['model']].add(r['hw_batch'])
    out = {}
    for model, ceilings in sorted(seen.items()):
        if len(ceilings) > 1:
            # Different bitstreams under one model: take the smaller ceiling and say so.
            problems.append(f"{model}: mixed batch ceilings {sorted(ceilings)} in one data "
                            "set — these are different bitstreams; separate the runs")
        out[model] = min(ceilings)
    if override:
        out = {m: override for m in set(out) | set(MODEL_OF.values())}
    return {m: set(range(1, c + 1)) for m, c in out.items()}, problems


def profile_median(rs, med):
    """Median each op/phase over the same passes as the throughput -> long-format rows."""
    by = defaultdict(list)
    for r in rs:
        for e in r.get('profile') or []:
            by[(e['kind'], e['op'])].append(e)
    out = []
    for (kind, op), es in sorted(by.items()):
        def m(field):
            xs = [e[field] for e in es if e.get(field) is not None]
            return round(med(xs), 3) if xs else ''
        out.append(dict(kind=kind, op=op, ms_per_run=m('ms_per_run'),
                        pct=m('pct'), us_per_call=m('us_per_call')))
    return out


def collapse(recs, spread_limit):
    """Average passes. For the Xeon, first pick the winning thread count per cell."""
    by = defaultdict(list)
    for r in recs:
        by[(r['platform'], r['arm'], r['model'], r['weight_fmt'], r['batch'], r['threads'])].append(r)

    def med(xs):
        v = sorted(xs); n = len(v)
        return v[n // 2] if n % 2 else (v[n // 2 - 1] + v[n // 2]) / 2.0

    means = {}
    for k, rs in by.items():
        ds = [r['decode'] for r in rs]
        ws = [r['watts'] for r in rs if r['watts'] is not None]
        # Median, not mean: the observed failure mode is one transient pass. spread_pct still
        # reports the full range.
        m = med(ds)
        # Jackknife: the largest shift of the median when any single pass is dropped.
        jack = max(abs(med([d for j, d in enumerate(ds) if j != i]) - m) / m * 100
                   for i in range(len(ds))) if len(ds) > 2 else (
                   (max(ds) - min(ds)) / m * 100 if len(ds) > 1 else 0.0)
        # Every metric is medianed over the same passes, so a row describes one population.
        def med_of(field):
            xs = [r[field] for r in rs if r.get(field) is not None]
            return med(xs) if xs else None
        idles = [r['watts_idle'] for r in rs if r.get('watts_idle') is not None]
        means[k] = dict(mean=m, mean_arith=sum(ds) / len(ds),
                        spread=(max(ds) - min(ds)) / m * 100 if len(ds) > 1 else 0.0,
                        jack=jack,
                        n=len(ds), watts=(med(ws) if ws else None),
                        watts_idle=(med(idles) if idles else None),
                        power_domain=rs[0].get('power_domain', ''),
                        clk_mhz=rs[0].get('clk_mhz', ''), fclk_hz=rs[0].get('fclk_hz', ''),
                        decode_per_stream=med_of('decode_per_stream'),
                        ttft_ms=med_of('ttft_ms'), prefill_ms=med_of('prefill_ms'),
                        prefill_tok_s=med_of('prefill_tok_s'),
                        token_latency_ms=med_of('token_latency_ms'),
                        prompt_tokens=med_of('prompt_tokens'), gen_tokens=med_of('gen_tokens'),
                        profile=profile_median(rs, med),
                        streams_ok=min((r['streams_ok'] for r in rs if r['streams_ok'] != ''), default=''),
                        preset=rs[0]['preset'])

    # Xeon: pick the best thread count per (model, encoding) over the whole ladder -- not per
    # batch, which would splice configurations into one curve -- and carry it in the row.
    tot = defaultdict(float)
    for (plat, arm, model, fmt, batch, th), v in means.items():
        tot[(plat, arm, model, fmt, th)] += v['mean']
    win = {}
    for (plat, arm, model, fmt, th), s_ in tot.items():
        ck = (plat, arm, model, fmt)
        if ck not in win or s_ > win[ck][1]:
            win[ck] = (th, s_)
    best = {}
    for k in means:
        plat, arm, model, fmt, batch, th = k
        if th == win[(plat, arm, model, fmt)][0]:
            best[(plat, arm, model, fmt, batch)] = k

    rows, prof_rows = [], []
    for ck, k in sorted(best.items(), key=lambda kv: (kv[0][0], kv[0][2], kv[0][3], kv[0][4])):
        v = means[k]
        plat, arm, model, fmt, batch = ck
        flag = 'UNSTABLE' if v['jack'] > spread_limit else ''
        if v['watts'] is None:
            flag = (flag + ' NO_POWER').strip()
        if arm == 'fpga' and v['streams_ok'] not in ('1', ''):
            flag = (flag + ' STREAMS_BAD').strip()
        # tok/J uses watts_net (total minus own idle); with no idle sample it falls back to
        # total and is flagged NO_IDLE.
        tot = v['watts']
        idle = v['watts_idle']
        net = (tot - idle) if (tot is not None and idle is not None) else tot
        if tot is not None and idle is None:
            flag = (flag + ' NO_IDLE').strip()
        rows.append(dict(
            date=v.get('date', ''), platform=plat, arm=arm, model=model, weight_fmt=fmt,
            batch=batch, threads=k[5], preset=v['preset'],
            clk_mhz=v['clk_mhz'], fclk_hz=v['fclk_hz'],
            passes=v['n'],
            # token counts are counts: emit them as integers, not 64.0
            prompt_tokens=int(v['prompt_tokens']) if v['prompt_tokens'] is not None else '',
            gen_tokens=int(v['gen_tokens']) if v['gen_tokens'] is not None else '',
            ttft_ms=num(v['ttft_ms'], 3),
            decode_tok_s=round(v['mean'], 3),
            decode_tok_s_per_stream=num(v['decode_per_stream'], 3),
            prefill_ms=num(v['prefill_ms'], 3),
            prefill_tok_s=num(v['prefill_tok_s'], 3),
            token_latency_ms=num(v['token_latency_ms'], 4),
            watts_total=num(tot, 3), watts_idle=num(idle, 3), watts_net=num(net, 3),
            power_domain=v['power_domain'],
            tok_per_joule=round(v['mean'] / net, 4) if net else '',
            mean_tok_s=round(v['mean_arith'], 3),
            spread_pct=round(v['spread'], 2),
            median_shift_pct=round(v['jack'], 2),
            streams_ok=v['streams_ok'], flag=flag))
        prof_rows.extend(dict(platform=plat, arm=arm, model=model, weight_fmt=fmt,
                              batch=batch, threads=k[5], **e) for e in v['profile'])
    return rows, prof_rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--board', default=os.path.expanduser('~/results/final'))
    ap.add_argument('--xeon', default='reports/host_cpu/final')
    ap.add_argument('--out', default='reports/final')
    ap.add_argument('--date', default=datetime.date.today().isoformat(),
                    help='stamped on every row (default: today)')
    ap.add_argument('--spread-limit', type=float, default=3.0,
                    help='flag a cell whose passes disagree by more than this %% (default 3)')
    ap.add_argument('--max-batch', type=int, default=None,
                    help='force the expected batch ladder to 1..N for every model '
                         '(default: per model from the CSVs\' hw_batch_max)')
    ap.add_argument('--fpga-formats', default='1p6', metavar='1p6[,2bit]',
                    help='weight encodings the FPGA arm was swept at (default 1p6). Each '
                         'is a separate bitstream, so it is declared, not inferred; rows in an '
                         'undeclared format are reported as UNEXPECTED.')
    ap.add_argument('--cpu-ladder', default=None, metavar='"1 2"',
                    help='batch ladder the KRIA A53 CPU arms were deliberately run at, '
                         'when shorter than the FPGA\'s. Declares the truncation (printed in the '
                         'log) instead of reporting missing rows; it does not fabricate them.')
    ap.add_argument('--prompt-tokens', type=int, default=None, metavar='N',
                    help='assert every row was measured with an N-token prompt. Without it the '
                         'table must still carry ONE prompt length, whatever it is.')
    ap.add_argument('--no-a53', action='store_true',
                    help='declare the KRIA A53 CPU arms out of scope for this set: their rows '
                         'are dropped and not expected, instead of reported ENTIRELY ABSENT.')
    ap.add_argument('--idle-drop-sample', default=None, metavar='EPOCH[,EPOCH]',
                    help='exclude these samples (the integer seconds of their @<epoch> line in '
                         'power/idle/*.samples) from the board idle baseline, recomputed from '
                         'the raw samples; each must match exactly one sample. For a sample '
                         'with a known external cause (e.g. an ssh login), printed on every run.')
    ap.add_argument('--idle-max-w', type=float, default=3.8, metavar='W',
                    help='refuse a board idle baseline above this (default 3.8; fresh-boot idle '
                         'reads 3.46-3.52). Higher means a bitstream left resident in the fabric.')
    a = ap.parse_args()
    cpu_ladder = None
    # `is not None`: an empty --cpu-ladder (e.g. an unset $CPU_BATCHES) is an error.
    if a.cpu_ladder is not None:
        try:
            cpu_ladder = {int(x) for x in a.cpu_ladder.replace(',', ' ').split()}
        except ValueError:
            ap.error(f'--cpu-ladder wants a batch list like "1 2", got {a.cpu_ladder!r}')
        if not cpu_ladder:
            ap.error('--cpu-ladder is empty; omit it to expect the full ladder')

    idle_drop = ()
    if a.idle_drop_sample is not None:
        try:
            idle_drop = tuple(int(x) for x in a.idle_drop_sample.replace(',', ' ').split())
        except ValueError:
            ap.error(f'--idle-drop-sample wants integer epochs, got {a.idle_drop_sample!r}')
        if not idle_drop:
            ap.error('--idle-drop-sample is empty; omit it to keep every idle sample')

    recs, idle_problems = [], []
    if os.path.isdir(a.board):
        board_recs, idle_problems = read_board(a.board, idle_drop, a.idle_max_w)
        recs += board_recs
    else:
        print(f"WARN: board dir {a.board} missing", file=sys.stderr)
    if os.path.isdir(a.xeon):
        recs += read_xeon(a.xeon)
    else:
        print(f"WARN: xeon dir {a.xeon} missing", file=sys.stderr)
    if a.no_a53:
        n0 = len(recs)
        recs = [r for r in recs if not (r['platform'] == 'kria' and r['arm'] == 'cpu')]
        print(f"kria/cpu (A53) arms: DECLARED out of scope (--no-a53) — dropped "
              f"{n0 - len(recs)} per-pass rows", file=sys.stderr)
    if not recs:
        sys.exit("no input found — nothing to aggregate")

    # Prefill (ttft/prefill_ms) scales with prompt length, so a table mixing lengths compares
    # different workloads. Checked per PASS, before the median can hide a minority.
    prompt_problems = []
    by_arm = defaultdict(set)
    for r in recs:
        pt = r.get('prompt_tokens')
        by_arm[(r['platform'], r['arm'])].add(int(pt) if pt is not None else None)
    seen = set().union(*by_arm.values())
    if a.prompt_tokens is not None:
        for arm, v in sorted(by_arm.items()):
            if v != {a.prompt_tokens}:
                prompt_problems.append(f"{'/'.join(arm)}: prompt_tokens {sorted(v, key=str)}, "
                                       f"expected {a.prompt_tokens} (--prompt-tokens)")
    elif len(seen) > 1:
        prompt_problems.append("table mixes prompt lengths: " + ", ".join(
            f"{'/'.join(arm)}={sorted(v, key=str)}" for arm, v in sorted(by_arm.items())))
    print(f"prompt tokens: {sorted(seen, key=str)}"
          + ("" if prompt_problems else " on every row"), file=sys.stderr)

    rows, prof_rows = collapse(recs, a.spread_limit)
    for r in rows:
        r['date'] = a.date
    os.makedirs(a.out, exist_ok=True)
    dest = os.path.join(a.out, 'dataset.csv')
    with open(dest, 'w', newline='') as fh:
        w = csv.DictWriter(fh, fieldnames=DATASET_COLS)
        w.writeheader()
        w.writerows(rows)

    # ── per-model profiling breakdown, one file per model size ───────────────
    # Long format: the op set differs between platforms (bl_* phases are FPGA-only).
    written = []
    by_model = defaultdict(list)
    for r in prof_rows:
        by_model[r['model']].append(r)
    for model, prs in sorted(by_model.items()):
        slug = model.lower().replace('.', '_')
        pdest = os.path.join(a.out, f'ops_{slug}.csv')
        with open(pdest, 'w', newline='') as fh:
            w = csv.DictWriter(fh, fieldnames=OPS_COLS)
            w.writeheader()
            for r in sorted(prs, key=lambda r: (r['platform'], r['arm'], r['weight_fmt'],
                                                r['batch'], r['kind'], r['op'])):
                w.writerow({**r, 'date': a.date})
        written.append((pdest, len(prs)))

    # ── headline view ────────────────────────────────────────────────────────
    print(f"\nwrote {dest}  ({len(rows)} rows)")
    for pdest, n in written:
        print(f"wrote {pdest}  ({n} op rows)")
    print()
    order = {'370M': 0, '1.3B': 1, '2.7B': 2}
    print(f"{'platform':8s} {'arm':4s} {'model':6s} {'fmt':5s} {'thr':>3s} {'b':>2s} "
          f"{'ttft ms':>8s} {'tok/s':>8s} {'ms/tok':>7s} {'W net':>6s} {'tok/J':>7s}  flag")
    for r in sorted(rows, key=lambda r: (r['platform'], order.get(r['model'], 9),
                                         r['weight_fmt'], r['batch'])):
        print(f"{r['platform']:8s} {r['arm']:4s} {r['model']:6s} {r['weight_fmt']:5s} "
              f"{str(r['threads']):>3s} {r['batch']:>2d} {str(r['ttft_ms']):>8s} "
              f"{r['decode_tok_s']:8.2f} {str(r['token_latency_ms']):>7s} "
              f"{str(r['watts_net']):>6s} {str(r['tok_per_joule']):>7s}  {r['flag']}")

    # ── completeness / integrity ─────────────────────────────────────────────
    problems = [r for r in rows if r['flag']]
    want_models = ['370M', '1.3B', '2.7B']
    want_ladder, ladder_problems = ladders(recs, a.max_batch)
    for m in want_models:
        if m not in want_ladder:
            ladder_problems.append(
                f"{m}: no board row carries hw_batch_max, so the expected batch ladder is "
                "unknown — re-run the sweep with a bundle built after 2026-08-07, aggregate "
                "the board arm too, or pass --max-batch")
    if want_ladder:
        print("expected batch ladder (per model, from the bitstream's hw_batch_max): "
              + ", ".join(f"{m} 1..{max(b)}" for m, b in sorted(want_ladder.items())))
    # The expected matrix is asserted, not inferred from what showed up.
    fpga_formats = [f.strip() for f in a.fpga_formats.split(',') if f.strip()]
    bad = [f for f in fpga_formats if f not in ('1p6', '2bit', 'int8')]
    if bad:
        ap.error(f'--fpga-formats: unknown encoding(s) {bad}; expected 1p6 and/or 2bit')
    if len(fpga_formats) > 1:
        print(f"fpga arm: DECLARED encodings {fpga_formats} — one bitstream each")
    want_cells = ([('kria', 'fpga', m, f) for m in want_models for f in fpga_formats] +
                  ([] if a.no_a53 else
                   [('kria', 'cpu', m, f) for m in want_models for f in ('int8', '2bit', '1p6')]) +
                  [('xeon', 'cpu', m, f) for m in want_models for f in ('int8', '2bit', '1p6')])
    have = {(r['platform'], r['arm'], r['model'], r['weight_fmt']) for r in rows}
    missing = list(ladder_problems) + prompt_problems + idle_problems
    if cpu_ladder:
        print(f"kria/cpu arms: DECLARED short ladder b={sorted(cpu_ladder)} "
              f"(--cpu-ladder) — rows outside it were not measured, not lost")
    for cell in want_cells:
        want_batches = want_ladder.get(cell[2])
        # A declared-short A53 arm is judged only against its declared ladder.
        if cpu_ladder and cell[0] == 'kria' and cell[1] == 'cpu' and want_batches:
            want_batches = want_batches & cpu_ladder
        if cell not in have:
            missing.append(f"{'/'.join(cell)}: ENTIRELY ABSENT")
            continue
        if want_batches is None:
            continue                      # ladder unknown — already reported once, per model
        got = {r['batch'] for r in rows
               if (r['platform'], r['arm'], r['model'], r['weight_fmt']) == cell}
        if got - want_batches:
            missing.append(f"{'/'.join(cell)}: b={sorted(got - want_batches)} is above this "
                           f"model's ceiling of {max(want_batches)} — wrong bitstream?")
        if want_batches - got:
            missing.append(f"{'/'.join(cell)}: missing b={sorted(want_batches - got)}")
    for cell in sorted(have - set(want_cells)):
        missing.append(f"{'/'.join(str(c) for c in cell)}: UNEXPECTED (not in the report matrix)")
    # Every FPGA cell should carry the same pass count; fewer means a pass died.
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
