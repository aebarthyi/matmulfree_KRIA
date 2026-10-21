"""Loading layer for the report figures.

KRIA and Xeon rows come from the aggregator's output (`scripts/final_dataset.py`:
dataset.csv + ops_<model>.csv), never the raw sweep CSVs, so every value is a
median across passes with tok/J against the right denominator and `flag` set.
The Jetson arm is a separate harness and is read from reports/jetson/ directly.
"""

import csv
import os

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The batched-CPU re-sweep. Its KRIA rows are identical to reports/final/ (2026-08-16);
# its Xeon rows are the fixed arm whose CPU backends batch (+32-43% at b=8 on 370M).
DATASET_DIR = os.environ.get(
    'GRAPHS_DATASET', os.path.join(REPO, 'reports', 'final_batched_20260825'))
JETSON_DIR = os.path.join(REPO, 'reports', 'jetson')

# Each platform's best encoding: 2-bit on both CPUs (int8 is NOT faster on the Xeon),
# 1.6-bit on the engine, whose bottleneck is weight-stream bandwidth.
CPU_FMT = '2bit'
FPGA_FMT = '1p6'

_NUM = ('decode_tok_s', 'decode_tok_s_per_stream', 'ttft_ms', 'prefill_ms',
        'token_latency_ms', 'watts_total', 'watts_idle', 'watts_net', 'tok_per_joule',
        'spread_pct', 'median_shift_pct')


def _f(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def dataset_label():
    """Short provenance string: directory name and the dates its rows carry."""
    rows = load_dataset()
    dates = sorted({r['date'] for r in rows})
    return f'{os.path.relpath(DATASET_DIR, REPO)}/dataset.csv ({", ".join(dates)})'


_cache = {}


def load_dataset():
    if 'rows' not in _cache:
        with open(os.path.join(DATASET_DIR, 'dataset.csv')) as fh:
            rows = list(csv.DictReader(fh))
        for r in rows:
            r['batch'] = int(r['batch'])
            r['passes'] = int(r['passes'] or 0)
            for k in _NUM:
                r[k] = _f(r[k])
        _cache['rows'] = rows
    return _cache['rows']


def series(platform, arm, model, fmt):
    """Rows for one arm/model/encoding, sorted by batch."""
    return sorted((r for r in load_dataset()
                   if (r['platform'], r['arm'], r['model'], r['weight_fmt'])
                   == (platform, arm, model, fmt)), key=lambda r: r['batch'])


def kria(model, fmt=FPGA_FMT):
    return series('kria', 'fpga', model, fmt)


def xeon(model, fmt=CPU_FMT):
    return series('xeon', 'cpu', model, fmt)


def a53(model, fmt=CPU_FMT):
    return series('kria', 'cpu', model, fmt)


def at(rs, b):
    return next((r for r in rs if r['batch'] == b), None)


def flagged(r):
    """True when the aggregator refused to call this cell quotable."""
    return bool(r.get('flag'))


def total_basis(r):
    """tok/J on TOTAL power. dataset.csv's tok_per_joule is net of idle."""
    return r['decode_tok_s'] / r['watts_total']


# ── Jetson Orin Nano (reports/jetson/) ───────────────────────────────────────
# Same three checkpoints, different stack (PyTorch), n=1, no idle sample. The harness
# emits two tokens per stream (one prefill, one decode step), so its own
# tokens_per_second is prefill-inclusive; decode_tok_s here is batch / decode-step,
# the quantity dataset.csv reports for the other platforms.
# `Weight Packing=True` is 2-bit ternary; False is the unquantised bf16 reference.
JETSON_FILE = {'370M': 'mmfree370.csv', '1.3B': 'mmfree1B.csv', '2.7B': 'mmfree2B.csv'}
JETSON_ENERGY = 'jetson_energy.csv'
_STEP = 'Avg Single Deocde Time (s)'          # sic, in the source


def _jetson_row(r):
    b = int(r['batch size'])
    step = float(r[_STEP])
    return dict(batch=b, step_s=step, decode_tok_s=b / step,
                e2e_tok_s=float(r['tokens_per_second']), mem_gb=float(r['Memory Usage']),
                prefill_s=float(r['Avg Prefill Time (s)']))


def load_jetson(model, packed=True):
    """Throughput run (2026-08-15), no power. [dict] sorted by batch."""
    want = 'True' if packed else 'False'
    with open(os.path.join(JETSON_DIR, JETSON_FILE[model])) as fh:
        out = [_jetson_row(r) for r in csv.DictReader(fh) if r['Weight Packing'] == want]
    return sorted(out, key=lambda d: d['batch'])


def load_jetson_energy(model='2.7B'):
    """Power run (2026-09-17, tegrastats): 2.7B 2-bit only, TOTAL-power basis.

    Power is a run average over prefill+decode, so the decode-only tok/J is an
    estimate once prefill dominates the window (high batch). This run is also slower
    per step than the throughput run (0.547 vs 0.391 s at b=1) for reasons the data
    does not record, so it is used only as a self-consistent power/throughput pair.
    """
    out = []
    with open(os.path.join(JETSON_DIR, JETSON_ENERGY)) as fh:
        for r in csv.DictReader(fh):
            if not r['model'].endswith(model) or r['Weight Packing'] != 'True':
                continue
            d = _jetson_row(r)
            d['watts'] = float(r['average_power_watts'])
            d['tok_per_joule'] = d['decode_tok_s'] / d['watts']
            d['tok_per_joule_e2e'] = d['e2e_tok_s'] / d['watts']
            d['prefill_frac'] = d['prefill_s'] / float(r['run_time_seconds'])
            out.append(d)
    if not out:
        raise ValueError(f'{model} is not in {JETSON_ENERGY} (it carries 2.7B only)')
    return sorted(out, key=lambda d: d['batch'])


# ── Engine phase breakdown (ops_<model>.csv) ─────────────────────────────────
OPS_FILE = {'370M': 'ops_370m.csv', '1.3B': 'ops_1_3b.csv', '2.7B': 'ops_2_7b.csv'}
HOST_PHASES = ['pack', 'store', 'readback', 'load']   # A53 work around the engine
PHASE_ORDER = ['compute'] + HOST_PHASES               # compute = the fabric's own time


def _ops(model, fmt=FPGA_FMT):
    ops, ph = {}, {}
    with open(os.path.join(DATASET_DIR, OPS_FILE[model])) as fh:
        for r in csv.DictReader(fh):
            if (r['platform'], r['arm'], r['weight_fmt']) != ('kria', 'fpga', fmt):
                continue
            dst = ops if r['kind'] == 'op' else ph
            dst.setdefault(int(r['batch']), {})[r['op']] = float(r['ms_per_run'])
    return ops, ph


def load_phases(model):
    """batch -> {phase: ms_per_run} for the engine arm's BitLinear instrumentation."""
    return dict(sorted(_ops(model)[1].items()))


def engine_headroom(model):
    """batch -> (floor, ceiling) multipliers on measured tok/s. PROJECTIONS, not runs.

    floor    remove only the four instrumented host phases from the step; all other
             host work (RMSNorm, quant/dequant, vector ops) stays.
    ceiling  the fabric's busy time alone (op_total / compute) -- unreachable, since
             some host must still run the non-matmul ops.
    """
    ops, ph = _ops(model)
    out = {}
    for b in sorted(ops):
        total = sum(ops[b].values())
        compute = ph.get(b, {}).get('compute', 0.0)
        host = sum(v for k, v in ph.get(b, {}).items() if k in HOST_PHASES)
        if total and compute and total > host:
            out[b] = (total / (total - host), total / compute)
    return out
