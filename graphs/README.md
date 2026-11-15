# graphs/ — report figures

    .venv-plot/bin/python graphs/build.py            # all figures + figure-notes.md
    .venv-plot/bin/python graphs/build.py fig3 fig5  # just these

`matplotlib` lives in `.venv-plot/` at the repo root (gitignored):
`python3 -m venv .venv-plot && .venv-plot/bin/pip install matplotlib`.
PNG (200 dpi, review) and PDF (vector, report) land in `graphs/figures/`.

## Data

| platform | source | notes |
|---|---|---|
| KRIA K26 engine + A53 | `reports/final_neonpack_20261021/{dataset,ops_*}.csv` | aggregator output (`scripts/final_dataset.py`): 5-pass medians, `flag`, tok/J net of idle. Engine rows re-measured 2026-10-21 with the NEON activation pack; A53 rows carried over from 2026-08-16 |
| Xeon W-2155 | same files | the **batched** CPU arm (2026-08-25); `reports/final/` holds the older unbatched one |
| Jetson Orin Nano | `reports/jetson/mmfree*.csv` (throughput), `jetson_energy.csv` (power) | separate PyTorch harness, n=1, read directly |

`GRAPHS_DATASET=<dir>` points the KRIA/Xeon side at another aggregation. Every number
in a figure or in `figure-notes.md` is computed from these files, so re-aggregate and
rebuild and everything follows.

## The figures

| | shows |
|---|---|
| `fig1_throughput_ladder` | Decode tok/s, b=1..8, three platforms at their fastest encodings. |
| `fig2_shared_encoding_ladders` | Same weights only: 1.6-bit (KRIA vs Xeon) and 2-bit (all three). |
| `fig3_throughput_ratio` | KRIA ÷ Xeon and KRIA ÷ Jetson vs batch, per model. |
| `fig4_throughput_full_range` | Each platform over its own measured batch range (Jetson to b≈1000-1750). |
| `fig5_efficiency_net` | tok/J net of idle, KRIA vs Xeon (the Jetson has no idle sample). |
| `fig6_efficiency_total_2p7b` | tok/J on total power, all three, 2.7B 2-bit: matched batch and reachable batch. |
| `fig7_throughput_vs_power` | Throughput vs total power at b=8, iso-tok/J diagonals. |
| `fig8_phase_breakdown` | Where the engine arm's BitLinear time goes (fabric vs host phases). |
| `fig9_offload_speedup` | Engine over the same board's A53 cores. |
| `fig10_engine_headroom` | Projected engine-only throughput (band) against the batched Xeon. |
| `fig11_jetson_quantisation` | Jetson bf16 vs 2-bit: memory saved, throughput lost. |

`figure-notes.md` (generated) opens with a b=8 summary table and then gives each
figure's headline and caveats; the figures themselves carry no title or caption.
`FIG_TITLES=1 FIG_NOTES=1` draws them in for review copies.

## Comparability rules

**Encodings.** No single encoding gives a fair comparison across everything:

| encoding | KRIA engine | Xeon | Jetson |
|---|---|---|---|
| 1.6-bit | ✅ shipped | ✅ | ❌ |
| 2-bit | ✅ control bitstream | ✅ fastest | ✅ |
| int8 | ❌ | ✅ | ❌ |
| bf16 | ❌ | ❌ | ✅ float reference |

fig1/3/4/5/7 put each platform on its fastest encoding; fig2 and fig6 are same-encoding.

**Power.** KRIA and Xeon tok/J are net of a measured idle (board rails; RAPL pkg+dram).
The Jetson has no idle sample, so it appears only on the total-power basis (fig6, fig7),
which is the basis least favourable to the board. The three instruments draw different
boundaries; captions must name them.

**Jetson.** Its harness emits two tokens per stream, so the figures use the
decode-only rate (batch / decode-step time), not its prefill-inclusive
`tokens_per_second`. Below b≈8 its step time is a fixed ~0.20 s overhead (370M and 1.3B
are identical there), so those points are a floor, not its capability — fig4 shows the
rest of its range. Its power run (2.7B only) is slower per step than its throughput run,
so the two Jetson files never share a figure axis.

## Style conventions

Platform → hue (KRIA blue, Xeon orange, Jetson aqua, A53 magenta: the palette's first
three slots are the ones that pass the all-pairs colourblind checks, which scatter
plots need). Model → marker shape. An ordered dimension with no platform in play
(model size, encoding) → the blue ordinal ramp. Unquotable cells are ringed in the
reserved status red. Legends sit in a band above the panels, never on the data.
Figures are light-only (print).
