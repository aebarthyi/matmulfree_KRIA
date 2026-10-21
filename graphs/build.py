#!/usr/bin/env python3
"""Build every report figure into graphs/figures/, plus graphs/figure-notes.md.

    .venv-plot/bin/python graphs/build.py            # all figures + notes
    .venv-plot/bin/python graphs/build.py fig3 fig5  # just these (notes untouched)

Every number drawn or quoted here is computed from the data, so a re-aggregation
followed by a rebuild updates figures and notes together.
"""

import os
import sys

import matplotlib
import matplotlib.pyplot as plt
import matplotlib.ticker
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import data as D                                        # noqa: E402
import style as S                                       # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'figures')
BATCHES = list(range(1, 9))
PLATFORMS = ('kria_fpga', 'xeon', 'jetson')

PROV_KRIA = 'KRIA K26 @250 MHz, engine + A53 host, 1.6-bit weights'
PROV_XEON = 'Xeon W-2155, 20 threads, batched CPU kernels, 2-bit weights'
PROV_JET = 'Jetson Orin Nano, PyTorch, 2-bit weights, n=1'


def prov(*parts):
    return '  |  '.join(parts + (f'5-pass medians, {D.dataset_label()}',))


def _xy(rs, key='decode_tok_s'):
    return [r['batch'] for r in rs], [r[key] for r in rs]


def _rings(ax, rs, key='decode_tok_s', fn=None):
    for r in rs:
        if D.flagged(r):
            S.ring(ax, r['batch'], fn(r) if fn else r[key])


def _jet(model, upto=8, packed=True):
    pts = [d for d in D.load_jetson(model, packed) if d['batch'] <= upto]
    return [d['batch'] for d in pts], [d['decode_tok_s'] for d in pts]


def _plain_log(ax, axis='y'):
    """Log axis with plain-number tick labels (0.5, 1, 2, 5, ...) instead of 10^n."""
    fmt = plt.FuncFormatter(lambda v, _: f'{v:g}')
    target = ax.yaxis if axis == 'y' else ax.xaxis
    target.set_major_locator(matplotlib.ticker.LogLocator(subs=(1, 2, 5)))
    target.set_major_formatter(fmt)
    target.set_minor_formatter(matplotlib.ticker.NullFormatter())


def _sig(v):
    return f'{v:.3g}'


def _ladder_axes(ax, title):
    ax.set_title(title)
    ax.set_xlabel('batch (concurrent streams)')
    ax.set_xticks(BATCHES)
    ax.set_xlim(0.6, 8.6)
    S.despine(ax)


def _rate(rs):
    return {r['batch']: r['decode_tok_s'] for r in rs}


def _ratio(a, b):
    """Equal-batch ratio of two {batch: tok/s} maps, over the batches both have."""
    return {k: a[k] / b[k] for k in sorted(a) if k in b}


JETSON_REGIME = ('The Jetson is overhead-bound at these batches: its decode step is ~0.20 s '
                 'from b=1 to b=8 on both 370M and 1.3B, so its line rises linearly with '
                 'batch and is a floor, not a bound (see fig4).')


# ── fig1 — throughput, b=1..8, each platform at its best encoding ────────────
def fig1():
    fig, axes = plt.subplots(1, 3, figsize=(11.0, 3.6))
    ratios = {}
    for ax, m in zip(axes, S.MODELS):
        ends = []
        for key, rs in (('kria_fpga', D.kria(m)), ('xeon', D.xeon(m))):
            x, y = _xy(rs)
            S.line(ax, x, y, key, m)
            _rings(ax, rs)
            ends.append((x[-1], y[-1], f'{y[-1]:.0f}', S.COLOR[key]))
        jx, jy = _jet(m)
        S.line(ax, jx, jy, 'jetson', m)
        ends.append((jx[-1], jy[-1], f'{jy[-1]:.0f}', S.COLOR['jetson']))
        _ladder_axes(ax, m)
        ax.set_ylim(0, max(e[1] for e in ends) * 1.12)
        ratios[m] = (ends[0][1] / ends[1][1], ends[0][1] / ends[2][1])
        fig.canvas.draw()
        S.label_ends(ax, ends)
    axes[0].set_ylabel('decode throughput (tok/s, all streams)')
    S.legend_band(fig, PLATFORMS, extra=[S.flag_handle()])
    r = ' / '.join(f'{x:.2f}x' for x, _ in ratios.values())
    j = ' / '.join(f'{y:.1f}x' for _, y in ratios.values())
    S.headline(fig, 'At the batches the board can run, the engine sits between the GPU and '
                    'the workstation, and closes on the workstation as the model grows')
    S.note(fig, f'Per-panel y-axis.  Each platform at its fastest encoding.  KRIA over the '
                f'Xeon at b=8: {r} (370M / 1.3B / 2.7B); over the Jetson: {j}.  '
                f'{JETSON_REGIME}  ' + prov(PROV_KRIA, PROV_XEON, PROV_JET), y=-0.08)
    return S.save(fig, os.path.join(OUT, 'fig1_throughput_ladder'))


# ── fig2 — the same weights on every platform that runs them ─────────────────
def fig2():
    fig, axes = plt.subplots(2, 3, figsize=(11.0, 6.6))
    facts = []
    for row, fmt in enumerate(('1p6', '2bit')):
        for ax, m in zip(axes[row], S.MODELS):
            ends = []
            arms = [('kria_fpga', D.kria(m, fmt)), ('xeon', D.xeon(m, fmt))]
            for key, rs in arms:
                x, y = _xy(rs)
                S.line(ax, x, y, key, m)
                _rings(ax, rs)
                ends.append((x[-1], y[-1], f'{y[-1]:.0f}', S.COLOR[key]))
            if fmt == '2bit':
                jx, jy = _jet(m)
                S.line(ax, jx, jy, 'jetson', m)
                ends.append((jx[-1], jy[-1], f'{jy[-1]:.0f}', S.COLOR['jetson']))
            ratio = _ratio(_rate(arms[0][1]), _rate(arms[1][1]))
            over = [b for b in sorted(ratio) if ratio[b] >= 1.0]
            facts.append(f'{S.ENCODING_LABEL[fmt]} {m}: {ratio[8]:.2f}x' +
                         (f' (ahead from b={over[0]})' if over else ''))
            _ladder_axes(ax, m if row == 0 else '')
            ax.set_ylim(0, max(e[1] for e in ends) * 1.12)
            if row == 0:
                ax.set_xlabel('')
            fig.canvas.draw()
            S.label_ends(ax, ends)
        axes[row][0].set_ylabel(f'{S.ENCODING_LABEL[fmt]} weights\n'
                                'decode throughput (tok/s)')
    S.legend_band(fig, PLATFORMS, extra=[S.flag_handle()], y=0.99)
    ahead = {fmt: [m for m in S.MODELS if D.at(D.kria(m, fmt), 8)['decode_tok_s']
                   >= D.at(D.xeon(m, fmt), 8)['decode_tok_s']] for fmt in ('1p6', '2bit')}
    where = {f: (f'at {" and ".join(ahead[f])}' if ahead[f] else 'at no model size')
             for f in ahead}
    S.headline(fig, 'On identical weights the engine leads the Xeon at b=8 on 1.6-bit '
                    f'{where["1p6"]}, and on 2-bit {where["2bit"]}', y=1.04)
    S.note(fig, 'Top row: 1.6-bit, the engine\'s shipped format, run by the KRIA and the Xeon. '
                'Bottom row: 2-bit, the only format all three platforms run; the engine\'s '
                'arm there is a matched control bitstream (packedWeights=false, nothing else '
                'changed).  KRIA over Xeon at b=8, same encoding: ' + '; '.join(facts) + '.  '
                f'{JETSON_REGIME}  ' +
                prov('KRIA K26 @250 MHz, engine + A53 host', 'Xeon W-2155, 20 threads, '
                     'batched CPU kernels', PROV_JET), y=-0.04)
    return S.save(fig, os.path.join(OUT, 'fig2_shared_encoding_ladders'))


# ── fig3 — the ratio, against each competitor ────────────────────────────────
def fig3():
    fig, axes = plt.subplots(1, 2, figsize=(9.6, 3.9), sharey=True)
    ends = {}
    for ax, (key, title) in zip(axes, (('xeon', 'KRIA engine ÷ Xeon'),
                                       ('jetson', 'KRIA engine ÷ Jetson'))):
        ax.axhline(1.0, color=S.BASELINE, lw=1.0, zorder=2)
        items = []
        for m in S.MODELS:
            other = _rate(D.xeon(m)) if key == 'xeon' else dict(zip(*_jet(m)))
            ratio = _ratio(_rate(D.kria(m)), other)
            bs = sorted(ratio)
            ys = [ratio[b] for b in bs]
            ax.plot(bs, ys, marker=S.MARKER[m], color=S.MODEL_RAMP[m], zorder=4,
                    markeredgecolor=S.SURFACE, markeredgewidth=0.8)
            items.append((bs[-1], ys[-1], f'{m}  {ys[-1]:.2f}x', S.MODEL_RAMP[m]))
            ends[(key, m)] = ys[-1]
        ax.set_yscale('log', base=2)
        ax.set_yticks([0.125, 0.25, 0.5, 1, 2, 4, 8])
        ax.set_yticklabels(['⅛x', '¼x', '½x', '1x', '2x', '4x', '8x'])
        ax.set_ylim(0.17, 11)
        _ladder_axes(ax, title)
        ax.annotate('parity', xy=(0.7, 1.0), xytext=(0, 3), textcoords='offset points',
                    fontsize=7.5, color=S.MUTED, va='bottom')
        fig.canvas.draw()
        S.label_ends(ax, items)
    axes[0].set_ylabel('throughput ratio at equal batch (log)')
    jr = [ends[('jetson', m)] for m in S.MODELS]
    xr = [ends[('xeon', m)] for m in S.MODELS]
    S.headline(fig, f'At b=8 the engine is {min(jr):.1f}-{max(jr):.1f}x the Jetson and '
                    f'{min(xr):.2f}-{max(xr):.2f}x the Xeon, closing on the Xeon with batch '
                    'and model size')
    S.note(fig, 'Equal batch on both sides; each platform at its fastest encoding (engine '
                '1.6-bit, Xeon and Jetson 2-bit). Above 1x the KRIA engine is faster.  '
                f'{JETSON_REGIME}  ' + prov(PROV_KRIA, PROV_XEON, PROV_JET), y=-0.06)
    return S.save(fig, os.path.join(OUT, 'fig3_throughput_ratio'))


# ── fig4 — each platform over its own reachable batch ────────────────────────
def fig4():
    fig, axes = plt.subplots(1, 3, figsize=(11.0, 3.8), sharey=True)
    peaks = []
    for ax, m in zip(axes, S.MODELS):
        jet = D.load_jetson(m)
        jx, jy = [d['batch'] for d in jet], [d['decode_tok_s'] for d in jet]
        ax.plot(jx, jy, color=S.COLOR['jetson'], zorder=4)
        for key, rs in (('kria_fpga', D.kria(m)), ('xeon', D.xeon(m))):
            ax.plot(*_xy(rs), color=S.COLOR[key], zorder=4)
        k8 = D.at(D.kria(m), 8)['decode_tok_s']
        cross = next((d for d in jet if d['decode_tok_s'] > k8), None)
        peaks.append(f'{m}: {jy[-1]:.0f} tok/s at b={jx[-1]}' +
                     (f', passing the engine\'s b=8 rate by b={cross["batch"]}' if cross else ''))
        ax.axvline(8, color=S.BASELINE, lw=1, ls=(0, (2, 2)), zorder=1)
        ax.set_xscale('log')
        ax.set_yscale('log')
        ax.set_xticks([1, 8, 64, 512])
        ax.get_xaxis().set_major_formatter(plt.ScalarFormatter())
        ax.get_yaxis().set_major_formatter(plt.ScalarFormatter())
        ax.set_xlim(0.8, 2500)
        ax.set_ylim(1.8, 12000)
        ax.set_title(m)
        ax.set_xlabel('batch (concurrent streams, log)')
        S.despine(ax)
        S.direct_label(ax, jx[-1], jy[-1], f'{jy[-1]:.0f} @ b={jx[-1]}', S.COLOR['jetson'],
                       dx=-4, dy=9, ha='right', va='bottom')
    axes[0].annotate('b=8: the engine\'s\nbatchSize, fixed\nin the bitstream', xy=(8, 2.2),
                     xytext=(10, 2.2), fontsize=7.2, color=S.MUTED, va='bottom')
    axes[0].set_ylabel('decode throughput (tok/s, all streams, log)')
    S.legend_band(fig, PLATFORMS, y=1.06)
    S.headline(fig, 'Allowed its own batch, the Jetson outruns both — the board stops at '
                    'b=8 by construction, the Xeon was only measured to b=8')
    S.note(fig, 'Log-log.  Jetson decode-only rate (batch / decode-step time) over its full '
                'measured range: ' + '; '.join(peaks) + '.  The Xeon was not measured above '
                'b=8, so its line ends there by omission, not by limit.  ' +
                prov(PROV_KRIA, PROV_XEON, PROV_JET), y=-0.08)
    return S.save(fig, os.path.join(OUT, 'fig4_throughput_full_range'))


# ── fig5 — energy efficiency, net of idle ────────────────────────────────────
def fig5():
    fig, axes = plt.subplots(1, 3, figsize=(11.0, 3.6), sharey=True)
    ratios = []
    for ax, m in zip(axes, S.MODELS):
        for key, rs in (('kria_fpga', D.kria(m)), ('xeon', D.xeon(m))):
            x, y = _xy(rs, 'tok_per_joule')
            S.line(ax, x, y, key, m)
            _rings(ax, rs, 'tok_per_joule')
            S.direct_label(ax, x[-1], y[-1], _sig(y[-1]), S.COLOR[key], dx=7)
        k8 = D.at(D.kria(m), 8)['tok_per_joule']
        x8 = D.at(D.xeon(m), 8)['tok_per_joule']
        ratios.append(k8 / x8)
        _ladder_axes(ax, f'{m}   —   {k8 / x8:.0f}x at b=8')
        ax.set_yscale('log')
        _plain_log(ax)
    axes[0].set_ylabel('energy efficiency (tok/J, net of idle)')
    S.legend_band(fig, ('kria_fpga', 'xeon'), extra=[S.flag_handle()])
    rows = D.load_dataset()
    k_idle = {r['watts_idle'] for r in rows if r['platform'] == 'kria' and r['watts_idle']}
    x_idle = {r['watts_idle'] for r in rows if r['platform'] == 'xeon' and r['watts_idle']}
    S.headline(fig, f'Energy efficiency is the engine\'s win: {min(ratios):.0f}-'
                    f'{max(ratios):.0f}x the Xeon per joule at b=8')
    S.note(fig, f'Log y.  NET OF IDLE on both (KRIA idle {min(k_idle):.3f} W, board rails; '
                f'Xeon idle {min(x_idle):.1f}-{max(x_idle):.1f} W, RAPL pkg+dram).  The two '
                'instruments draw different boundaries: the KRIA figure includes board '
                'regulators, the Xeon excludes PSU losses.  The Jetson has no idle sample, so '
                'it appears only on the total-power basis (fig6, fig7).  ' +
                prov(PROV_KRIA, PROV_XEON), y=-0.08)
    return S.save(fig, os.path.join(OUT, 'fig5_efficiency_net'))


# ── fig6 — three-platform energy efficiency, 2.7B, total power ───────────────
def fig6():
    M = '2.7B'
    fig, (axL, axR) = plt.subplots(1, 2, figsize=(11.6, 4.3),
                                   gridspec_kw={'width_ratios': [1, 1.25]})
    kria, xeon, jet = D.kria(M, '2bit'), D.xeon(M, '2bit'), D.load_jetson_energy(M)
    jet8 = [d for d in jet if d['batch'] <= 8]
    lanes = (('kria_fpga', *_xy(kria, 'decode_tok_s'), [D.total_basis(r) for r in kria]),
             ('jetson', [d['batch'] for d in jet8], None, [d['tok_per_joule'] for d in jet8]),
             ('xeon', *_xy(xeon, 'decode_tok_s'), [D.total_basis(r) for r in xeon]))
    for key, x, _, y in lanes:
        S.line(axL, x, y, key, M)
        S.direct_label(axL, x[-1], y[-1], f'{y[-1]:.2f}', S.COLOR[key], dx=7)
    k8 = D.total_basis(kria[-1])
    j8 = jet8[-1]['tok_per_joule']
    x8 = D.total_basis(xeon[-1])
    axL.set_yscale('log')
    _plain_log(axL)
    _ladder_axes(axL, '')
    axL.set_xlim(0.6, 9.4)
    axL.set_ylabel('energy efficiency (tok/J, total power)')
    axL.set_title('Matched batch, b=1..8', loc='left', fontsize=9, pad=10)
    axL.text(0.97, 0.06, f'at b=8 the board is\n{k8 / j8:.1f}x the Jetson\n'
                         f'{k8 / x8:.0f}x the Xeon',
             transform=axL.transAxes, fontsize=8, color=S.INK_2, ha='right', va='bottom',
             linespacing=1.5)

    # Jetson as a band: decode-only (upper) vs prefill-inclusive (lower) differ ~7x at
    # the top of its range, and whether it overtakes the board depends on that choice.
    jb = [d['batch'] for d in jet]
    jhi = [d['tok_per_joule'] for d in jet]
    jlo = [d['tok_per_joule_e2e'] for d in jet]
    axR.fill_between(jb, jlo, jhi, color=S.COLOR['jetson'], alpha=0.16, linewidth=0, zorder=2)
    axR.plot(jb, jhi, color=S.COLOR['jetson'], lw=2, zorder=4)
    axR.plot(jb, jlo, color=S.COLOR['jetson'], lw=1.1, ls=(0, (3, 2)), zorder=4)
    for key, rs in (('kria_fpga', kria), ('xeon', xeon)):
        S.line(axR, [r['batch'] for r in rs], [D.total_basis(r) for r in rs], key, M,
               markersize=4.5, zorder=5)
    axR.axvline(8, color=S.BASELINE, lw=1, ls=(0, (2, 2)), zorder=1)
    axR.axhline(k8, color=S.COLOR['kria_fpga'], lw=0.9, ls=(0, (1, 2)), zorder=1)
    below = [d for d in jet if d['tok_per_joule'] <= k8][-1]
    above = next(d for d in jet if d['tok_per_joule'] > k8)
    axR.set_xscale('log')
    axR.set_yscale('log')
    _plain_log(axR)
    axR.set_xlim(0.8, 900)
    axR.set_xticks([1, 2, 4, 8, 20, 50, 130, 400])
    axR.get_xaxis().set_major_formatter(plt.ScalarFormatter())
    axR.set_xlabel('batch (concurrent streams, log)')
    axR.set_title('Each platform at its own reachable batch', loc='left', fontsize=9, pad=10)
    xc = (below['batch'] * above['batch']) ** 0.5
    axR.annotate(f'Jetson decode-only edge\npasses the board between\n'
                 f'b={below["batch"]} and b={above["batch"]}',
                 xy=(xc, k8), xytext=(xc / 3.2, k8 * 2.2), fontsize=7.2, color=S.INK_2,
                 ha='center', va='bottom',
                 arrowprops=dict(arrowstyle='-', lw=0.8, color=S.MUTED, shrinkA=2, shrinkB=3))
    S.despine(axR)
    S.direct_label(axR, jb[-1], jhi[-1], f'{jhi[-1]:.0f}', S.COLOR['jetson'])
    S.direct_label(axR, jb[-1], jlo[-1], f'{jlo[-1]:.1f}', S.COLOR['jetson'])
    S.direct_label(axR, 8, k8, f'{k8:.1f}', S.COLOR['kria_fpga'], dx=7)
    S.direct_label(axR, 8, x8, f'{x8:.2f}', S.COLOR['xeon'], dx=7)

    S.legend_band(fig, ('kria_fpga', 'jetson', 'xeon'), y=1.075, extra=[
        plt.Line2D([], [], color=S.COLOR['jetson'], lw=1.1, ls=(0, (3, 2)),
                   label='Jetson, prefill included')])
    k16 = D.total_basis(D.kria(M)[-1])
    S.headline(fig, f'At matched batch the board is {k8 / j8:.0f}x the Jetson per joule; '
                    f'allowed b>{below["batch"]}, the Jetson passes it')
    S.note(fig, 'TOTAL power on all three (the Jetson has no idle sample, so this is the '
                'only shared basis, and the one least favourable to the board).  All three '
                f'on 2-bit weights; the board\'s shipped 1.6-bit arm reads {k16:.2f} tok/J at '
                f'b=8 against {k8:.2f} for its 2-bit control.  Power boundaries differ: KRIA '
                'board rails, Xeon RAPL pkg+dram, Jetson tegrastats module rails.  Jetson '
                'caveats: n=1; power is a run average over prefill+decode, so the decode-only '
                f'edge is an estimate once prefill dominates (prefill is '
                f'{jet[-1]["prefill_frac"] * 100:.0f}% of the b={jet[-1]["batch"]} window); '
                'and this power run is slower per step than the throughput run in fig1-4 '
                f'({jet[0]["step_s"]:.3f} vs {D.load_jetson(M)[0]["step_s"]:.3f} s at b=1), '
                'so its throughput does not reconcile with those figures.  ' +
                prov('KRIA K26 @250 MHz, engine + A53 host, 2-bit control bitstream',
                     'Xeon W-2155, 20 threads, batched CPU kernels, 2-bit',
                     'Jetson Orin Nano, reports/jetson/jetson_energy.csv'), y=-0.07)
    return S.save(fig, os.path.join(OUT, 'fig6_efficiency_total_2p7b'))


# ── fig7 — throughput vs power ───────────────────────────────────────────────
def fig7():
    fig, ax = plt.subplots(figsize=(7.4, 5.0))
    (x0, x1), (y0, y1) = (3, 250), (8, 900)
    xs = np.array([x0, x1])
    for eff in (0.3, 1, 3, 10, 30):
        ax.plot(xs, xs * eff, color=S.GRID, lw=0.9, zorder=1)
        # label where the diagonal leaves the frame: top edge, else right edge
        xl, yl = (y1 / eff, y1) if y1 / eff < x1 else (x1, x1 * eff)
        ax.annotate(f'{eff:g} tok/J', xy=(xl, yl), xytext=(-3, -3),
                    textcoords='offset points', fontsize=7, color=S.MUTED, ha='right',
                    va='top')
    pts = []
    for key, getter in (('kria_fpga', D.kria), ('xeon', D.xeon)):
        for m in S.MODELS:
            r = D.at(getter(m), 8)
            pts.append((key, m, r['watts_total'], r['decode_tok_s'], m))
    jet = D.load_jetson_energy('2.7B')
    j8, jmax = D.at(jet, 8), jet[-1]
    pts.append(('jetson', '2.7B', j8['watts'], j8['decode_tok_s'], '2.7B'))
    for key, m, w, t, lab in pts:
        ax.scatter(w, t, s=90, marker=S.MARKER[m], color=S.COLOR[key], edgecolor=S.SURFACE,
                   linewidth=1.2, zorder=5)
        ax.annotate(lab, xy=(w, t), xytext=(8, -3), textcoords='offset points', fontsize=8,
                    color=S.INK_2)
    ax.scatter(jmax['watts'], jmax['decode_tok_s'], s=90, marker='^', facecolor='none',
               edgecolor=S.COLOR['jetson'], linewidth=1.4, zorder=5)
    ax.annotate(f'2.7B at b={jmax["batch"]}', xy=(jmax['watts'], jmax['decode_tok_s']),
                xytext=(8, -3), textcoords='offset points', fontsize=8, color=S.INK_2)
    ax.set_xscale('log')
    ax.set_yscale('log')
    ax.set_xlim(x0, x1)
    ax.set_ylim(y0, y1)
    ax.set_xticks([4, 10, 20, 50, 100, 200])
    ax.set_yticks([10, 20, 50, 100, 200, 500])
    ax.get_xaxis().set_major_formatter(plt.ScalarFormatter())
    ax.get_yaxis().set_major_formatter(plt.ScalarFormatter())
    ax.set_xlabel('total power (W, log)')
    ax.set_ylabel('decode throughput at b=8 (tok/s, log)')
    S.despine(ax)
    handles = [plt.Line2D([], [], color=S.COLOR[k], lw=0, marker='o', markersize=8,
                          markeredgecolor=S.SURFACE, label=S.LABEL[k]) for k in PLATFORMS]
    handles.append(plt.Line2D([], [], color=S.COLOR['jetson'], lw=0, marker='^',
                              markersize=8, markerfacecolor='none', markeredgewidth=1.4,
                              label='Jetson at its largest measured batch'))
    ax.legend(handles=handles, loc='lower right')
    kw = [w for k, _, w, _, _ in pts if k == 'kria_fpga']
    xw = [w for k, _, w, _, _ in pts if k == 'xeon']
    S.headline(ax, f'The Xeon draws {min(xw) / max(kw):.0f}-{max(xw) / min(kw):.0f}x the '
                   'board\'s power for its throughput; the Jetson only overtakes the board per '
                   'joule far past b=8', pad=12)
    S.note(fig, 'Log-log; diagonals are constant tokens per joule on TOTAL power (the only '
                'basis the Jetson has).  Filled markers are b=8; the Jetson has power for '
                '2.7B only, from its slower power run (fig6 caveats apply).  ' +
                prov(PROV_KRIA, PROV_XEON, 'Jetson Orin Nano, jetson_energy.csv'), y=-0.04)
    return S.save(fig, os.path.join(OUT, 'fig7_throughput_vs_power'))


# ── fig8 — where the engine arm's time goes ──────────────────────────────────
def fig8():
    fig, axes = plt.subplots(1, 3, figsize=(11.0, 3.9), sharey=True)
    host8 = []
    for ax, m in zip(axes, S.MODELS):
        ph = D.load_phases(m)
        bs = sorted(ph)
        totals = {b: sum(ph[b].values()) for b in bs}
        bottom = np.zeros(len(bs))
        for i, phase in enumerate(D.PHASE_ORDER):
            vals = np.array([ph[b].get(phase, 0.0) / totals[b] * 100 for b in bs])
            ax.bar(bs, vals, bottom=bottom, width=0.74, color=S.SLOT[i],
                   edgecolor=S.SURFACE, linewidth=1.4, zorder=3)
            bottom += vals
        host = [100 - ph[b]['compute'] / totals[b] * 100 for b in bs]
        host8.append(host[-1])
        ax.set_title(f'{m}   —   host {host[0]:.0f}% → {host[-1]:.0f}%')
        ax.set_xlabel('batch (concurrent streams)')
        ax.set_xticks(BATCHES)
        ax.set_ylim(0, 100)
        ax.set_xlim(0.4, 8.6)
        ax.grid(axis='x', visible=False)
        S.despine(ax)
    axes[0].set_ylabel('share of BitLinear time (%)')
    handles = [plt.Line2D([], [], color=S.SLOT[i], lw=7,
                          label=('engine compute' if p == 'compute' else f'host {p}'))
               for i, p in enumerate(D.PHASE_ORDER)]
    fig.legend(handles=handles, loc='upper left', bbox_to_anchor=(0.008, 1.05), ncol=5,
               frameon=False)
    S.headline(fig, 'Host work around the engine grows with batch, to '
                    f'{min(host8):.0f}-{max(host8):.0f}% of BitLinear time at b=8')
    S.note(fig, 'Engine compute is the only phase the fabric owns; pack/store/readback/load '
                'are A53 work around it.  ' + prov(PROV_KRIA), y=-0.07)
    return S.save(fig, os.path.join(OUT, 'fig8_phase_breakdown'))


# ── fig9 — the engine against its own host CPU ───────────────────────────────
def fig9():
    fig, ax = plt.subplots(figsize=(7.6, 4.4))
    x = np.arange(3)
    w = 0.36
    bar_color = {2: S.MODEL_RAMP['370M'], 8: S.MODEL_RAMP['1.3B']}
    a53 = {m: D.at(D.a53(m), 2)['decode_tok_s'] for m in S.MODELS}
    series = {b: [D.at(D.kria(m), b)['decode_tok_s'] / a53[m] for m in S.MODELS]
              for b in (2, 8)}
    for i, b in enumerate((2, 8)):
        bars = ax.bar(x + (i - 0.5) * w, series[b], width=w * 0.92, color=bar_color[b],
                      zorder=3, label=('b=2  (both measured)' if b == 2 else
                                       'b=8  (A53 held at its b=2 rate)'))
        for rect, v in zip(bars, series[b]):
            ax.annotate(f'{v:.1f}x', xy=(rect.get_x() + rect.get_width() / 2, v),
                        xytext=(0, 4), textcoords='offset points', ha='center',
                        fontsize=9.5, fontweight='bold', color=S.INK)
    ax.set_xticks(x)
    ax.set_xticklabels([f'{m}\nengine {D.at(D.kria(m), 8)["decode_tok_s"]:.1f}  vs  '
                        f'A53 {a53[m]:.2f} tok/s' for m in S.MODELS])
    ax.set_ylabel('engine speedup over the same board\'s A53s')
    ax.set_ylim(0, max(series[8]) * 1.18)
    ax.grid(axis='x', visible=False)
    ax.legend(loc='upper left')
    S.despine(ax)
    S.headline(ax, 'The bigger the model, the more the engine is worth', pad=12)
    S.note(fig, 'Same board, same host process; only the ternary matmul moves.  Engine '
                '1.6-bit, A53 2-bit (each its fastest).  The A53 arms were measured at b=1..2 '
                'with the UNBATCHED runner, so their rate is flat in batch and the b=8 bars '
                'hold it at b=2; a batched A53 arm would shrink the b=8 bars.  ' +
                prov(PROV_KRIA), y=-0.13)
    return S.save(fig, os.path.join(OUT, 'fig9_offload_speedup'))


# ── fig10 — engine-only headroom against the batched Xeon ────────────────────
def fig10():
    fig, axes = plt.subplots(1, 3, figsize=(11.6, 3.9), sharey=True)
    floors = []
    for ax, m in zip(axes, S.MODELS):
        bb, bv = _xy(D.kria(m))
        xb, xv = _xy(D.xeon(m))
        head = D.engine_headroom(m)
        hb = [b for b in bb if b in head]
        lo = [bv[bb.index(b)] * head[b][0] for b in hb]
        hi = [bv[bb.index(b)] * head[b][1] for b in hb]
        ax.fill_between(hb, lo, hi, color=S.COLOR['kria_fpga'], alpha=0.13, linewidth=0,
                        zorder=1)
        for edge in (lo, hi):
            ax.plot(hb, edge, color=S.COLOR['kria_fpga'], lw=1.1, ls=(0, (4, 2.5)),
                    alpha=0.55, zorder=2)
        S.line(ax, xb, xv, 'xeon', m)
        S.line(ax, bb, bv, 'kria_fpga', m, zorder=5)
        ax.set_yscale('log')
        _plain_log(ax)
        _ladder_axes(ax, m)
        ax.set_xlim(0.6, 8.4)
        ax.grid(axis='x', visible=False)
        fig.canvas.draw()
        S.label_ends(ax, [(hb[-1], hi[-1], f'{hi[-1]:.0f}', S.COLOR['kria_fpga']),
                          (hb[-1], lo[-1], f'{lo[-1]:.0f}', S.COLOR['kria_fpga']),
                          (xb[-1], xv[-1], f'{xv[-1]:.0f}', S.COLOR['xeon']),
                          (bb[-1], bv[-1], f'{bv[-1]:.0f}', S.COLOR['kria_fpga'])])
        floors.append((m, lo[-1], xv[-1]))
    axes[0].set_ylabel('decode throughput (tok/s, log)')
    S.legend_band(fig, ('kria_fpga', 'xeon'), extra=[
        plt.Line2D([], [], color=S.COLOR['kria_fpga'], lw=6, alpha=0.2,
                   label='engine-only headroom (projection)')], y=1.05)
    level = [m for m, lo, xv in floors if lo >= xv]
    S.headline(fig, 'Removing every measured host phase would bring the engine level with the '
                    f'Xeon at {", ".join(level) or "no model size"} only')
    S.note(fig, 'Log y.  The band is a PROJECTION from the measured phase breakdown, not a '
                'run.  Lower edge: the four instrumented host phases (pack/store/readback/load) '
                'removed, all other host work kept.  Upper edge: the fabric\'s busy time alone '
                '(unreachable; assumes RMSNorm and the vector ops are free).  At b=8: ' +
                '; '.join(f'{m}: floor {lo:.0f} vs Xeon {xv:.0f}' for m, lo, xv in floors) +
                '.  ' + prov(PROV_KRIA, PROV_XEON), y=-0.08)
    return S.save(fig, os.path.join(OUT, 'fig10_engine_headroom'))


# ── fig11 — the Jetson's own quantisation trade ──────────────────────────────
def fig11():
    have = [m for m in S.MODELS if D.load_jetson(m, True) and D.load_jetson(m, False)]
    fig, axes = plt.subplots(1, 2, figsize=(8.8, 3.9))
    x = np.arange(len(have))
    w = 0.36
    vals = {(arm, k): [] for arm in ('bf16', '2bit') for k in ('mem', 'tps')}
    for arm in ('bf16', '2bit'):
        for m in have:
            d = D.at(D.load_jetson(m, packed=(arm == '2bit')), 8)
            vals[(arm, 'mem')].append(d['mem_gb'])
            vals[(arm, 'tps')].append(d['decode_tok_s'])
    for ax, (k, ylabel) in zip(axes, (('mem', 'resident weight memory (GB)'),
                                      ('tps', 'decode throughput at b=8 (tok/s)'))):
        for i, arm in enumerate(('bf16', '2bit')):
            bars = ax.bar(x + (i - 0.5) * w, vals[(arm, k)], width=w * 0.92,
                          color=S.ENCODING_RAMP[arm], zorder=3, label=S.ENCODING_LABEL[arm])
            for rect, v in zip(bars, vals[(arm, k)]):
                ax.annotate(f'{v:.2f}' if k == 'mem' else f'{v:.1f}',
                            xy=(rect.get_x() + rect.get_width() / 2, v), xytext=(0, 3),
                            textcoords='offset points', ha='center', fontsize=8.5, color=S.INK)
        ax.set_xticks(x)
        ax.set_xticklabels(have)
        ax.set_ylabel(ylabel)
        ax.grid(axis='x', visible=False)
        S.despine(ax)
    axes[0].legend(loc='upper left')
    cost = [1 - t2 / tb for t2, tb in zip(vals[('2bit', 'tps')], vals[('bf16', 'tps')])]
    save = [mb / m2 for m2, mb in zip(vals[('2bit', 'mem')], vals[('bf16', 'mem')])]
    S.headline(fig, 'On the GPU, going ternary is a memory play that costs throughput')
    S.note(fig, f'b=8.  2-bit costs {min(cost) * 100:.0f}-{max(cost) * 100:.0f}% of decode '
                f'throughput and cuts resident memory {min(save):.1f}-{max(save):.1f}x '
                f'({", ".join(have)}).  bf16 is the unquantised float reference, not a ternary '
                'packing, so this is not the board\'s 1.6-bit-vs-2-bit comparison.  2.7B has '
                'no bf16 run.  ' + PROV_JET, y=-0.10)
    return S.save(fig, os.path.join(OUT, 'fig11_jetson_quantisation'))


# ── summary table (figure-notes.md preamble) ─────────────────────────────────
def summary():
    j_e = D.load_jetson_energy('2.7B')
    out = ['## Summary at b=8', '',
           'Decode tok/s (all streams). Ratios are KRIA engine ÷ the other platform.', '',
           '| model | KRIA 1.6-bit | KRIA 2-bit | Xeon 2-bit | Xeon 1.6-bit | Jetson 2-bit '
           '| KRIA ÷ Xeon (best) | KRIA ÷ Xeon (1.6-bit) | KRIA ÷ Jetson | Jetson peak |',
           '|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|']
    for m in S.MODELS:
        k, k2 = D.at(D.kria(m), 8), D.at(D.kria(m, '2bit'), 8)
        x, x16 = D.at(D.xeon(m), 8), D.at(D.xeon(m, '1p6'), 8)
        j = D.at(D.load_jetson(m), 8)
        jp = max(D.load_jetson(m), key=lambda d: d['decode_tok_s'])
        out.append(f'| {m} | {k["decode_tok_s"]:.1f}{" ⚠" if D.flagged(k) else ""} '
                   f'| {k2["decode_tok_s"]:.1f} | {x["decode_tok_s"]:.1f} '
                   f'| {x16["decode_tok_s"]:.1f} | {j["decode_tok_s"]:.1f} '
                   f'| {k["decode_tok_s"] / x["decode_tok_s"]:.2f}x '
                   f'| {k["decode_tok_s"] / x16["decode_tok_s"]:.2f}x '
                   f'| {k["decode_tok_s"] / j["decode_tok_s"]:.2f}x '
                   f'| {jp["decode_tok_s"]:.0f} @ b={jp["batch"]} |')
    out += ['', 'Energy efficiency (tok/J). Net = minus each platform\'s measured idle; '
            'total = wall of its power domain. The Jetson has total only, 2.7B only.', '',
            '| model | KRIA net | Xeon net | KRIA ÷ Xeon (net) | KRIA total | Xeon total '
            '| Jetson total | KRIA watts | Xeon watts | Jetson watts |',
            '|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|']
    for m in S.MODELS:
        k, x = D.at(D.kria(m), 8), D.at(D.xeon(m), 8)
        j = D.at(j_e, 8) if m == '2.7B' else None
        out.append(f'| {m} | {k["tok_per_joule"]:.2f} | {x["tok_per_joule"]:.2f} '
                   f'| {k["tok_per_joule"] / x["tok_per_joule"]:.0f}x '
                   f'| {D.total_basis(k):.2f} | {D.total_basis(x):.3f} '
                   f'| {"%.2f" % j["tok_per_joule"] if j else "—"} '
                   f'| {k["watts_total"]:.2f} | {x["watts_total"]:.1f} '
                   f'| {"%.1f" % j["watts"] if j else "—"} |')
    j8, jt8 = D.at(j_e, 8), D.at(D.load_jetson('2.7B'), 8)
    out += ['', f'The Jetson\'s tok/J comes from its separate power run, which is slower per '
            f'step ({j8["decode_tok_s"]:.1f} tok/s at b=8 against {jt8["decode_tok_s"]:.1f} in '
            'the throughput table above).', '',
            f'Source: {D.dataset_label()}; reports/jetson/ (throughput 2026-08-15, '
            'power 2026-09-17). ⚠ = flagged unstable by the aggregator.', '']
    return out


FIGS = {f.__name__: f for f in (fig1, fig2, fig3, fig4, fig5, fig6, fig7, fig8, fig9,
                                fig10, fig11)}


def main():
    os.makedirs(OUT, exist_ok=True)
    S.apply_rc()
    want = sys.argv[1:] or list(FIGS)
    for name in want:
        print(f'  {name}  ->  {os.path.relpath(FIGS[name](), os.getcwd())}')
    if not sys.argv[1:]:        # only a full build sees every figure's notes
        p = S.dump_notes(os.path.join(os.path.dirname(OUT), 'figure-notes.md'), summary())
        print(f'  notes ->  {os.path.relpath(p, os.getcwd())}')


if __name__ == '__main__':
    main()
