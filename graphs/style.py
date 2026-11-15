"""Shared chart style for the report figures.

Colors are the dataviz reference palette's pre-validated categorical slots, used in
slot order: slots 1-3 clear the all-pairs colourblind gates, so the three platforms
(which share scatter plots) take exactly those. Re-run the palette validator
(needs node) before substituting any other values.

Figures are light-only (print report). Headlines and footnotes are not drawn by
default -- the report supplies captions -- but every one is written to
graphs/figure-notes.md on a full build. FIG_TITLES=1 FIG_NOTES=1 draws them.
"""

import os
import textwrap

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt                          # noqa: E402

SHOW_TITLES = os.environ.get('FIG_TITLES', '0') == '1'
SHOW_NOTES = os.environ.get('FIG_NOTES', '0') == '1'

NOTES = {}                      # figure basename -> {'title':…, 'note':…}
_pending = {'title': None, 'note': None}

SLOT = ['#2a78d6',  # 1 blue
        '#eb6834',  # 2 orange
        '#1baf7a',  # 3 aqua
        '#eda100',  # 4 yellow
        '#e87ba4',  # 5 magenta
        '#008300',  # 6 green
        '#4a3aa7',  # 7 violet
        '#e34948']  # 8 red

SURFACE = '#fcfcfb'
INK = '#0b0b0b'
INK_2 = '#52514e'
MUTED = '#898781'
GRID = '#e1e0d9'
BASELINE = '#c3c2b7'
CRITICAL = '#d03b3b'    # status colour: only the unquotable-cell ring

# Colour follows the entity in every figure. The A53 arm never shares a scatter with
# the three platforms, so it can sit at slot 5.
COLOR = {'kria_fpga': SLOT[0], 'xeon': SLOT[1], 'jetson': SLOT[2], 'kria_a53': SLOT[4]}
LABEL = {
    'kria_fpga': 'KRIA K26 — FPGA engine',
    'xeon':      'Xeon W-2155 — 20 threads',
    'jetson':    'Jetson Orin Nano — GPU',
    'kria_a53':  'KRIA K26 — A53 CPU only',
}

# Ordered dimensions with no platform to distinguish get the ordinal blue ramp, not
# categorical hues (which would make "blue" mean both KRIA and 370M).
MODEL_RAMP = {'370M': '#86b6ef', '1.3B': '#2a78d6', '2.7B': '#104281'}
ENCODING_RAMP = {'1p6': '#86b6ef', '2bit': '#3987e5', 'int8': '#1c5cab', 'bf16': '#0d366b'}
ENCODING_LABEL = {'1p6': '1.6-bit', '2bit': '2-bit', 'int8': 'int8', 'bf16': 'bf16'}

MODELS = ['370M', '1.3B', '2.7B']
MARKER = {'370M': 'o', '1.3B': 's', '2.7B': '^'}       # model -> shape, never a hue


def apply_rc():
    plt.rcParams.update({
        'figure.facecolor':  SURFACE,
        'axes.facecolor':    SURFACE,
        'savefig.facecolor': SURFACE,
        'font.family':       'sans-serif',
        'font.sans-serif':   ['DejaVu Sans'],
        'font.size':          9,
        'axes.titlesize':     10,
        'axes.titleweight':  'bold',
        'axes.titlecolor':    INK,
        'axes.labelsize':     9,
        'axes.labelcolor':    INK_2,
        'axes.edgecolor':     BASELINE,
        'axes.linewidth':     0.8,
        'axes.grid':          True,
        'axes.axisbelow':     True,
        'grid.color':         GRID,
        'grid.linewidth':     0.6,
        'grid.linestyle':     '-',
        'xtick.color':        MUTED,
        'ytick.color':        MUTED,
        'xtick.labelcolor':   INK_2,
        'ytick.labelcolor':   INK_2,
        'xtick.labelsize':    8,
        'ytick.labelsize':    8,
        'legend.frameon':     False,
        'legend.fontsize':    8.5,
        'legend.labelcolor':  INK_2,
        'lines.linewidth':    2.0,
        'lines.markersize':   5.0,
        'lines.solid_capstyle': 'round',
    })


def despine(ax, keep=('left', 'bottom')):
    for side in ('top', 'right', 'left', 'bottom'):
        ax.spines[side].set_visible(side in keep)


def line(ax, x, y, key, model, **kw):
    """A platform series: 2px line, model marker, surface ring on the marker."""
    kw.setdefault('zorder', 4)
    return ax.plot(x, y, marker=MARKER[model], color=COLOR[key], markeredgecolor=SURFACE,
                   markeredgewidth=0.8, **kw)


def legend_band(fig, keys, extra=(), y=1.05, ncol=None):
    """Shared legend in a band above the panels, never on the data."""
    handles = [plt.Line2D([], [], color=COLOR[k], lw=2, marker='o', markersize=5,
                          markeredgecolor=SURFACE, label=LABEL[k]) for k in keys]
    # Keep the ring's key only if ring() drew one, so a clean data set shows no flag entry.
    handles += [h for h in extra
                if h.get_label() != FLAG_LABEL or getattr(fig, '_has_rings', False)]
    fig.legend(handles=handles, loc='upper left', bbox_to_anchor=(0.008, y),
               ncol=ncol or len(handles), frameon=False)


FLAG_LABEL = 'not quotable (passes disagree >3%)'


def flag_handle():
    return plt.Line2D([], [], color=CRITICAL, lw=0, marker='o', markerfacecolor='none',
                      markeredgewidth=1.4, markersize=9, label=FLAG_LABEL)


def ring(ax, x, y):
    """Ring an unquotable cell in the reserved status colour."""
    ax.figure._has_rings = True
    ax.plot([x], [y], marker='o', markersize=11, markerfacecolor='none',
            markeredgecolor=CRITICAL, markeredgewidth=1.4, zorder=6, clip_on=False)


def direct_label(ax, x, y, text, color, dx=6, dy=0, va='center', ha='left', size=8.5):
    ax.annotate(text, xy=(x, y), xytext=(dx, dy), textcoords='offset points',
                va=va, ha=ha, fontsize=size, color=color, fontweight='bold',
                clip_on=False)


def label_ends(ax, items, dx=6, size=8.5, gap_lines=1.25):
    """Direct-label series endpoints, staggering any that would overprint.

    items: [(x, y, text, color)]. Collisions are resolved in display space (so log
    axes behave), with the gap derived from the text height; transData gives pixels
    while the offset is in points, hence the dpi conversion.
    """
    dpi = ax.figure.dpi
    min_gap_px = size * dpi / 72.0 * gap_lines
    placed = []
    for x, y, text, color in sorted(items, key=lambda it: ax.transData.transform(
            (it[0], it[1]))[1]):
        py = ax.transData.transform((x, y))[1]
        off_px = 0.0
        for prev in placed:
            if abs((py + off_px) - prev) < min_gap_px:
                off_px = prev + min_gap_px - py
        placed.append(py + off_px)
        direct_label(ax, x, y, text, color, dx=dx, dy=off_px * 72.0 / dpi, size=size)


def headline(target, text, **kw):
    """Register the figure's argumentative title; draw it only if FIG_TITLES=1."""
    _pending['title'] = text
    if not SHOW_TITLES:
        return
    if isinstance(target, plt.Figure):
        target.suptitle(text, fontsize=kw.pop('fontsize', 11.5), fontweight='bold',
                        color=INK, x=0.008, ha='left', **kw)
    else:
        target.set_title(text, loc='left', fontsize=kw.pop('fontsize', 11.5), **kw)


def note(fig, text, y=0.005):
    """Register the footnote; draw it (wrapped to figure width) only if FIG_NOTES=1."""
    _pending['note'] = text
    if not SHOW_NOTES:
        return
    width = max(60, int(fig.get_size_inches()[0] * 16))   # ~16 chars/inch at 7.2pt
    fig.text(0.008, y, textwrap.fill(text, width), fontsize=7.2, color=MUTED,
             va='top', ha='left', linespacing=1.5)


def dump_notes(path, preamble=()):
    lines = ['# Figure notes', '',
             'Generated by `graphs/build.py` — edit that, not this file. The figures carry',
             'no title or caption; these are the headline and caveats each caption should',
             'reproduce.', ''] + list(preamble)
    for name in sorted(NOTES, key=lambda n: int(n[3:].split('_')[0])):
        n = NOTES[name]
        lines += [f'## `{name}`', '']
        if n.get('title'):
            lines += [f'**Headline:** {n["title"]}', '']
        if n.get('note'):
            lines += [n['note'], '']
    with open(path, 'w') as fh:
        fh.write('\n'.join(lines))
    return path


def save(fig, path_noext, dpi=200):
    """PNG for review, PDF (vector) for the report."""
    NOTES[os.path.basename(path_noext)] = dict(_pending)
    _pending['title'] = _pending['note'] = None
    for ext in ('png', 'pdf'):
        fig.savefig(f'{path_noext}.{ext}', dpi=dpi, bbox_inches='tight', pad_inches=0.18)
    plt.close(fig)
    return f'{path_noext}.png'
