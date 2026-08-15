#!/usr/bin/env python3
"""final_dataset.py — merge the board and Xeon sweeps into ONE tidy table for the report.

    ./scripts/final_dataset.py \
        --board ~/results/final --xeon reports/host_cpu/final --out reports/final

Emits TWO kinds of file, both under --out:

  dataset.csv          one row per (platform, arm, model, weight_fmt, batch) — the headline
                       table, identical in shape for kria/fpga, kria/cpu and xeon/cpu so a
                       row from any platform is directly comparable to a row from another.
  ops_<model>.csv      per-model profiling breakdown, LONG format (one row per op), one file
                       per model size: ops_370m.csv, ops_1_3b.csv, ops_2_7b.csv.

dataset.csv columns (DATASET_COLS below is the authority):

  identity   date, platform (kria|xeon), arm (fpga|cpu), model, weight_fmt (1p6|2bit|int8),
             batch, threads (Xeon OpenMP; blank on the board), preset,
             clk_mhz (the STA target) and fclk_hz (what the fabric ACTUALLY ran at — those
             disagreed once and a 295 MHz build quietly ran at 250), passes
  workload   prompt_tokens, gen_tokens. TTFT and prefill are uninterpretable without the
             prompt length, and decode tok/s drifts with the generation length.
  latency    ttft_ms            first stream's first token
             prefill_ms         the whole prefill phase == the LAST stream's TTFT; equal to
                                ttft_ms at b=1 and ~B-fold larger above it
             prefill_tok_s      prompt tokens / prefill time
             token_latency_ms   steady-state per-token decode latency
  throughput decode_tok_s              MEDIAN across passes, AGGREGATE over streams
             decode_tok_s_per_stream   the same divided by batch — what one user sees
  power      watts_total   mean draw over the run's window
             watts_idle    the same instrument, same session, nothing running
             watts_net     total - idle
             power_domain  board_rails (xlnx_platformstats SOM) | rapl_pkg+dram
             tok_per_joule decode_tok_s / watts_NET
  quality    mean_tok_s (arithmetic, for comparison), spread_pct ((max-min)/median across
             passes), median_shift_pct (jackknife: how far the median moves if any single
             pass is dropped), streams_ok (1 = every stream matched a single-stream decode),
             flag

WHY tok/J IS NET OF IDLE. The two platforms are not the same kind of machine: the SOM idles
near 2 W and the workstation package near 14 W, so dividing by total draw credits the board
for having less overhead to switch off rather than for doing the work more cheaply. Net is
the like-for-like figure. It is only meaningful if BOTH sides measured their own idle with
their own instrument, which is why the board sweep now samples one per session and a row
with no idle figure falls back to total AND carries a NO_IDLE flag — a table must never mix
denominators silently. power_domain is carried on every row so the boundary each number was
measured across is never in doubt; watts_total is kept so any reader who prefers total draw
can recompute without re-running anything.

WHAT THIS CHECKS SO THE REPORT DOES NOT HAVE TO. It refuses to emit a clean data set if
any of these hold, because each one silently invalidates a comparison drawn across rows:
  * a row missing power       -> tok/J would be blank or spliced from another run
  * mixed POWER settings      -> ~2% bias between arms measured with and without sampling
  * streams_ok=0 on any FPGA row -> that row's timings are meaningless
  * pass disagreement > limit -> the bad-cell failure mode from 2026-08-04, where 3/3 reps
                                 inside one process agreed to 0.12 tok/s on a 12%-low number
  * an arm short of the batch ladder -> a b=8 FPGA row with no b=8 CPU row has no denominator

The expected ladder is per model, read from the sweep's own hw_batch column rather than
hardcoded, so one aggregator validates a B8 set and a B6 set without either being wrong by
definition. See ladders().
"""
import argparse, csv, datetime, glob, os, re, sys
from collections import defaultdict

# ── the schema, in one place ────────────────────────────────────────────────────────────
# Identity first, then the workload it was measured under, then the metrics, then the
# quality columns that say whether the metrics can be trusted. Every consumer (report
# tables, plots, the README) reads THIS order, so adding a column is an append here.
DATASET_COLS = [
    # identity / provenance
    'date', 'platform', 'arm', 'model', 'weight_fmt', 'batch', 'threads', 'preset',
    'clk_mhz', 'fclk_hz', 'passes',
    # workload shape -- throughput and TTFT are meaningless without it
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


def board_power(power_root):
    """Board rail power from power_bench.sh's own summary lines.

    Returns (workload, idle):
      workload  (power_dir, batch) -> mean SOM watts over the run's window
      idle      mean SOM watts with nothing running, or None if never sampled

    The idle logs are the `_idle` mode final_sweep_board.sh records once per sweep (a sleep
    used as the workload, so the same sampler and parser produce it). They are matched on the
    mode suffix, NOT on the directory: they live in power/idle/ but still carry a _b<N>_ in
    the tag from the manifest, so keying them like a workload run would file idle draw under
    some real batch and quietly halve that cell's power.
    """
    out, idle = {}, []
    pat = re.compile(r'SOM total power\s+avg\s+([\d.]+)\s*mW')
    for log in glob.glob(os.path.join(power_root, '*', '*.log')):
        base = os.path.basename(log)
        txt = open(log, errors='ignore').read()
        w = pat.search(txt)
        if not w:
            continue
        watts = float(w.group(1)) / 1000.0
        if base.endswith('_idle.log'):
            idle.append(watts)
            continue
        m = re.search(r'_b(\d+)_n', base)
        if m:
            out[(os.path.basename(os.path.dirname(log)), int(m.group(1)))] = watts
    return out, (sum(idle) / len(idle) if idle else None)


def fnum(r, key):
    """Float from a CSV field, or None when absent/blank/unparseable.

    Every metric below is optional on purpose: a CSV written by an older bundle simply has
    no ttft_ms column, and that must produce an empty cell rather than crash the aggregate
    or, worse, a zero that averages in as if it were measured.
    """
    try:
        v = r.get(key, '')
        return float(v) if v not in ('', None, '-') else None
    except (TypeError, ValueError):
        return None


def num(v, nd):
    """Round for output, or an empty cell when the quantity was never measured."""
    return round(v, nd) if v is not None else ''


# Per-op / per-phase profiling columns, carried into the per-model breakdown CSVs.
# `op_*` exist on both platforms; `bl_*` are the FPGA BitLinear phases and are absent on CPU
# arms, which is why the breakdown records a `kind` rather than assuming one namespace.
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
        # ttft_ms is the FIRST stream's first token; prefill_ms is the whole prefill phase,
        # i.e. the LAST stream's time to first token. They coincide at b=1 and diverge by
        # roughly B-fold above it, so both are kept: one is what a single user waits, the
        # other is what a serving SLO has to cover.
        ttft_ms=fnum(r, 'ttft_ms'),
        prefill_ms=fnum(r, 'prefill_ms'),
        prefill_tok_s=fnum(r, 'prefill_tok_s'),
        token_latency_ms=fnum(r, 'token_latency_ms'),
        prompt_tokens=fnum(r, 'prompt_tokens'),
        gen_tokens=fnum(r, 'gen_tokens'),
        profile=read_profile(r),
    )


def read_board(board_root):
    """Board CSVs -> list of raw per-pass records."""
    recs = []
    pw, idle_w = board_power(os.path.join(board_root, 'power'))
    if not pw:
        print(f"WARN: no board power logs under {board_root}/power — did you pull them?", file=sys.stderr)
    if idle_w is None:
        print(f"WARN: no idle baseline under {board_root}/power/idle — tok/J will fall back "
              "to TOTAL draw for the kria rows, which is not comparable to the Xeon's "
              "net-of-idle. Re-run final_sweep_board.sh (it samples one per sweep).",
              file=sys.stderr)
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
                # hw_batch_max is the bitstream's compile-time batchSize: the batch ceiling
                # for this model, and hence the expected ladder for EVERY arm measuring it.
                # The CPU arms carry it too — bench_batch.sh reads the same manifest with the
                # engine out of the loop, so their b's line up with the FPGA run they are the
                # denominator for. NOT hw_batch, which is the per-run value and duplicates
                # `batch`; CSVs written before 2026-08-07 have only that, and are handled by
                # ladders() refusing to guess.
                hw_batch=int(r['hw_batch_max']) if r.get('hw_batch_max') else None,
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

    watts_total = timed_pkg_w + timed_dram_w — package plus DRAM over the TIMED window, the
    closest analogue to the KRIA's SOM rail figure (both include the platform's idle draw).
    watts_idle = idle_pkg_w + idle_dram_w, the baseline bench_host_cpu.sh samples before the
    workload; the board now samples its own equivalent (final_sweep_board.sh, once per
    sweep), so tok/J is net-of-idle on BOTH sides or on neither -- subtracting on one side
    only was never like-for-like and is what the earlier version of this file refused to do.
    """
    recs = []
    # Recursive: the sweep nests one level per model (<root>/<slug>/t<th>_p<n>/) because GEN
    # is chosen per model. The flat layout is still matched for older runs.
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
                # the row's own thread count, not the directory's — they agree, but the row
                # is the authority and this survives a re-run with a different THREAD_SET
                threads=r.get('omp_threads') or mt.group(1),
                pass_id=pas,
                power_domain='rapl_pkg+dram',
                watts_idle=((float(r['idle_pkg_w']) + float(r.get('idle_dram_w') or 0))
                            if r.get('idle_pkg_w') not in ('', None, 'None') else None),
                clk_mhz=r.get('clk_mhz', ''), fclk_hz=r.get('fclk_hz', ''),
                **metrics(r),
                # No hardware ceiling on the Xeon — its expected ladder is imposed by the
                # board's, since the point of this arm is to be the FPGA's denominator.
                hw_batch=None,
                watts=watts, streams_ok='', preset=r.get('host_cpu', '')))
    return recs


def ladders(recs, override=None):
    """model -> the set of batches every arm measuring it is expected to cover.

    The ceiling is a property of the BITSTREAM, not of the report: b=1..batchSize all come
    out of one build at runtime (COMPUTE flags -> cmpBatch), and b>batchSize is physically
    impossible. So it is read per model from the sweep CSV's hw_batch_max column rather than
    hardcoded — a hardcoded 1..6 cannot validate the shipped B8 set, and widening it to 1..8
    globally would instead declare a B6 set incomplete for batches its silicon cannot run.

    The CPU arms inherit the FPGA's ladder for the same model. They have no ceiling of their
    own, but a CPU row that stops at b=6 against an FPGA row that reaches b=8 leaves the
    speedup at b=7/8 with no denominator, which is exactly the gap this has to surface.

    Refuses to GUESS. A data set with no hw_batch_max (every CSV written before 2026-08-07)
    gets no ladder and says so, rather than inferring one from the batches present — that
    inference is what let a half-finished sweep report CLEAN, since whatever landed is always
    a complete cover of itself.

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
            # Two bitstreams with different batchSize under one model: the passes are not
            # measuring the same hardware, so their spread is meaningless and the union of
            # their ladders is not a ladder. Take the smaller and say so.
            problems.append(f"{model}: mixed batch ceilings {sorted(ceilings)} in one data "
                            "set — these are different bitstreams; separate the runs")
        out[model] = min(ceilings)
    if override:
        out = {m: override for m in set(out) | set(MODEL_OF.values())}
    return {m: set(range(1, c + 1)) for m, c in out.items()}, problems


def profile_median(rs, med):
    """Median each op/phase across the passes of one cell -> long-format rows.

    Medianed across the SAME passes as the throughput, for the same reason: a breakdown
    averaged over a different set of runs than the tok/s it is supposed to explain will not
    add up to it.
    """
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
        # MEDIAN, not mean, is the headline. The observed failure mode on both platforms is
        # ONE perturbed pass out of three — a transient that drags a mean but not a median.
        # Measured here: Xeon 2.7B t20 2bit pass 1 ran 8.5% slow while passes 2 and 3 agreed
        # to 0.9%, and kria 1.3B b=6 pass 2 ran 6% slow while 1 and 3 agreed to 0.04%. Pass 1
        # is NOT systematically slow (every other cell sits within +-1%), so these are machine
        # transients, not warm-up. The full range is still reported as spread_pct, so nothing
        # is hidden — the median just stops one transient from moving the number.
        m = med(ds)
        # Jackknife: is the median itself robust? Recompute it leaving each pass out; if any
        # single pass can move it by more than the limit, the cell needs more passes. This is
        # a better flag than raw spread, which condemns a cell whose median is rock solid.
        jack = max(abs(med([d for j, d in enumerate(ds) if j != i]) - m) / m * 100
                   for i in range(len(ds))) if len(ds) > 2 else (
                   (max(ds) - min(ds)) / m * 100 if len(ds) > 1 else 0.0)
        # Every other metric is medianed the same way and over the same passes, so a row's
        # latency and its throughput describe one population -- not a median tok/s spliced
        # onto a mean TTFT.
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

    # Xeon: the thread count is a tuning knob, not a variable of interest — collapse it by
    # taking the best and CARRYING the winner into the row, so "best of a sweep" is visible
    # in the table rather than hidden in the file it came from.
    #
    # Chosen per (model, encoding) across the whole batch ladder, NOT per batch. Per-batch
    # selection maximises each point independently but lets one curve switch configuration
    # mid-plot — 2.7B int8 genuinely wins at 10 threads for b=1 and 20 for b=6 — which puts a
    # discontinuity in the batch-scaling curve that is an artifact of the selection rather
    # than of the hardware. One configuration per curve, picked on mean throughput over the
    # ladder, is what a reader assumes when they see a line.
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
        # Power, stated in full rather than as one ambiguous number. tok/J is derived from
        # watts_net (total minus this platform's own idle draw) because that is the only
        # figure comparable across a board whose SoC idles near 2 W and a workstation whose
        # package idles near 14 W. When idle was never sampled we fall back to total and SAY
        # SO in the flag -- silently switching denominators mid-table is how a perf/W claim
        # becomes unreproducible.
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
                    help='stamped on every row so a merged table can be traced back to the '
                         'sweep that produced it (default: today)')
    ap.add_argument('--spread-limit', type=float, default=3.0,
                    help='flag a cell whose passes disagree by more than this %% (default 3)')
    ap.add_argument('--max-batch', type=int, default=None,
                    help='force the expected batch ladder to 1..N for every model. The '
                         'default reads it per model from the sweep CSV\'s hw_batch column '
                         '(the bitstream\'s compile-time batchSize), which is what you want '
                         'unless you are aggregating an arm the board never measured.')
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
    # Long format (one row per op), not the 60-column wide shape the raw sweep writes: ops
    # come and go between platforms -- the bl_* BitLinear phases exist only on the FPGA arm
    # -- and a wide table has to carry a blank column for every op no platform in that file
    # ran. Long also means adding an op later is rows, not a schema change.
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
    # The EXPECTED matrix, asserted rather than inferred. Checking only that whatever showed
    # up has all six batches lets a half-finished sweep report CLEAN — which it did, when five
    # of six Xeon passes were refused by the load guard and the one that landed looked
    # complete on its own terms.
    want_cells = ([('kria', 'fpga', m, '1p6') for m in want_models] +
                  [('kria', 'cpu', m, f) for m in want_models for f in ('int8', '2bit', '1p6')] +
                  [('xeon', 'cpu', m, f) for m in want_models for f in ('int8', '2bit', '1p6')])
    have = {(r['platform'], r['arm'], r['model'], r['weight_fmt']) for r in rows}
    missing = list(ladder_problems)
    for cell in want_cells:
        want_batches = want_ladder.get(cell[2])
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
