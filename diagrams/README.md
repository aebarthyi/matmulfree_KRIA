# Architecture Diagrams

Architecture diagrams for the matmulfree KRIA design, at three zoom levels, in
two complementary text-based tools:

- **[D2](https://d2lang.com)** (`d2/`) — block, container, and state-machine
  diagrams. Deliberate layout with nested containers; renders far cleaner than a
  graph auto-layout engine for architecture blocks.
- **[WaveDrom](https://wavedrom.com)** (`wavedrom/`) — the hardware-native forms:
  the instruction **bit-field** register layout and a **cycle-accurate timing**
  waveform (handshake + drain overlap) that a box-and-arrow tool cannot express.

The original **Graphviz** sources are kept under `graphviz/` for reference.

## Toolchain

| Tool | Install | Render |
|------|---------|--------|
| D2 (v0.7.1) | prebuilt binary in `~/.local/bin/d2` | `d2 --layout elk --pad 60 <f>.d2 <out>.svg` |
| WaveDrom (bit-field) | `pip install --user bit-field` | `bit_field --lanes 4 --bits 128 <f>.json > <out>.svg` |
| WaveDrom (timing) | `pip install --user wavedrom` | `python3 -c "import wavedrom; wavedrom.render(open('<f>.json').read()).saveas('<out>.svg')"` |
| SVG → PNG | `pip install --user cairosvg` | `python3 -c "import cairosvg; cairosvg.svg2png(url='x.svg', write_to='x.png', background_color='white')"` |

`elk` layout suits the container-heavy block diagrams; `dagre` suits the FSM
graphs. SVG is the primary artifact (scalable); PNGs are for quick preview.

## Diagrams

### `d2/` — structure (levels 1, 2, 3-structural)

| Source | Renders | Scope |
|--------|---------|-------|
| `01_system_top.d2` | `system_top.*` | **Level 1 — System top.** ARM CPU (PS) + FPGA (PL) + DDR4, and where each part of a MMfreeLM decoder layer runs. The FPGA does only the ternary BitLinear matmul *projections*; RMSNorm, quantize, the MLGRU recurrence, SiLU/sigmoid gating, residuals and sampling stay on the CPU. |
| `02_core_blocks.d2` | `core_blocks.*` | **Level 2 — Core blocks.** CoreTop wrapper (4→1 s_axis join) → Core: instruction handler, top FSM, actBram, inQ/armSlice, systolic array, tileAccums + drain engine, outBram (URAM), outQ. Control (blue) / data (green) / storage (amber) planes. |
| `03_fsms.d2` | `fsms.*` | **Level 3a — Control structure.** Instruction dispatch + the top-level FSM (sIdle/sLoad/sCompute/sStore) and the inner COMPUTE FSM (cInit/cStream/cReset/cDone). |
| `04_systolic_pe.d2` | `systolic_pe.*` | **Level 3b — Compute plane.** The yDim×xDim PE grid (weight broadcast, per-row activations) and one PE's datapath: 2-bit ternary decode (01→add, 11→sub, else hold), extend, ± adder (no multiplier), per-lane `accumReg`, counter, and the idle→running→done FSM. |

### `wavedrom/` — hardware-native detail (level 3, word/cycle)

| Source | Renders | Scope |
|--------|---------|-------|
| `instruction_word.json` | `instruction_word.*` | **128-bit instruction word** register layout: `opcode[7:0]`, `ptr[47:8]` (40-bit addr), `dim0[79:48]` (rows N / len), `dim1[111:80]` (cols M), `flags[127:112]`. |
| `compute_mm_timing.json` | `compute_mm_timing.*` | **COMPUTE_MM cycle-accurate timing** — stream N weight+act beats → array accumulates (0..N-1) → `output.valid` → capture (`valid && !drainBusy`) → drain to outBram *overlapping* tile t+1's stream. Shows the `tready`-drop-on-capture and `drainBusy` overlap that hide the store latency. |

Numeric widths in the compute-plane diagram use the **2.7B a16 B6** preset
(`aWidth=16`, `nLanes=8`, `xDim=32`, `yDim=batch=6`, `maxAcc=8192`, headroom 11 →
`accWidth=27`). Other presets scale these parameters.
