# mmfree-cli-fpga — CPU vs CPU+FPGA decode runner

Runs **matmulfreellmCPU** (the C++ HGRN-Bit / MMfreeLM forward) with the ternary
BitLinear projections executed on the KRIA systolic engine, and compares it head-to-head
against the pure-CPU path. Same model, same `FixedQ510` numerics, same `run_bench` timing —
only the `TernaryBackend` differs — so the measurement isolates exactly *ternary matmul on
the CPU vs on the engine*.

## Pieces

| file | role |
|---|---|
| `fpga_backend.{hpp,cpp}` | `FpgaBackend` (drives the engine via libmmfree) + `CompareBackend` (the `both` gate) |
| `manifest.{hpp,cpp}` | parse the packer's `<prefix>.manifest.tsv` → `tag → (byte_offset, N, M)` |
| `main_fpga.cpp` | the runner: open engine, load resident weights, register, `set_backend`, bench / serve / gate |
| `compare_backends.sh` | gate → CPU bench → FPGA bench, rendered as one table |
| `Makefile` | builds `libmmfreecpu.a` (submodule CMake) + the C engine driver + the runner |

The submodule stays FPGA-agnostic (dependency injection): it exposes only the abstract
`TernaryBackend` + `Model::set_backend`; all engine code lives here.

## Build

On the board, `make build` in `~/transfer` compiles this from the bundled `src/`. By hand:

```bash
make -C software/integration/fpga_runner     # -> build/mmfree-cli-fpga
```

SIMD is auto-selected from the host (`MMFREE_SIMD=ARM|X86|X86_512|SCALAR` overrides). Requires
the `matmulfreellmCPU` submodule and CMake. On x86 only the CPU backends can run — that is how
the Xeon baseline is measured (`scripts/bench_host_cpu.sh`).

## Run

Use the board Makefile: it exports the preset's geometry from the manifest, passes the device
paths and the model/packed-blob paths, and appends `ARGS="…"` last (the runner keeps the last
occurrence of a repeated flag). From `~/transfer`:

```bash
make gate    PRESET=mmfree_2_7b                       # --backend both: every projection's int32 acc, CPU vs engine
make run     PRESET=mmfree_2_7b                       # --backend fpga --bench --profile
make run     PRESET=mmfree_2_7b ARGS="--backend cpu"  # CPU baseline, same harness
make streams PRESET=mmfree_2_7b                       # --serve B --distinct-prompts (row-order check)
make sweep   PRESET=mmfree_2_7b [BACKEND=cpu]         # --serve b for b = 1..B, one CSV row each
```

Run the **gate first** — it must print `GATE PASS (CPU == FPGA)` before any timing means
anything.

### Backends

| `--backend` | ternary matmul runs on |
|---|---|
| `fpga` (default) | the engine |
| `both` | CPU and engine per projection, asserting bit-identical int32 accumulators (the gate) |
| `cpu` | the CPU, one int8 per ternary weight |
| `cpu-2bit` / `cpu-1p6` | the CPU with weights held at the FPGA's encodings (2.0 / 1.625 bits/weight); bit-identical to `cpu`, only speed differs (`cpu-tq1` is an alias of `cpu-1p6`) |

The engine runs only the integer path (`--mode fixed`, the default); `--mode float` is CPU-only.

### Batched decode (`--serve N`)

`--serve N` decodes N concurrent streams with batched projections and reports aggregate
tok/s. On the engine the N rows share one weight stream (N ≤ the bitstream's batchSize). The
CPU backends are constructed with width N, so their `matmul_batch` also sweeps each weight
matrix once per N streams; `MMFREE_CPU_BATCH=n` overrides that width (`1` decodes the streams
one after another, for an A/B). Results are bit-identical at any width.

Every `--serve` run checks each stream against a single-stream decode (`streams_ok`). With the
default shared prompt and greedy sampling the streams are identical by construction, so that
check cannot see a permutation — a drain that wrote row 2 into stream 5 passes it.
`--distinct-prompts` gives stream *b* its own prompt (a left-rotation of the ids, so all
prompts have the same length) and compares it against its OWN reference; a swap is reported
as `ROW PERMUTATION`. It costs N single-stream generations up front, so it is a per-bitstream
check (`make streams`), not a per-sweep one. The prompt must tokenize to at least N tokens.
The `--csv` row carries `distinct_prompts` and `distinct_refs` so a saved row says which check
it was.

### Options and knobs

- Input: `--ids a,b,c` (raw token ids, deterministic) or `--prompt TEXT` (needs a tokenizer:
  `--tokenizer`, default `tokenizer.mmtok` beside `--blob`); with neither, a fixed
  pseudo-prompt is used.
- Timing: `--bench`, `--gen N`, `--warmup N`, `--reps N`, `--profile`, `--csv` (one
  `#CSVH`/`#CSVR` line pair for `bench_batch.sh`).
- Sampling: `--temp F`, `--top-k K`, `--seed N`, `--no-eos`.
- Environment (via `ENV="K=V …"` on the board): `MMFREE_SPLIT_CHUNKS` (column-tile split
  count; `0` disables, `n` forces n), `MMFREE_RB_THREADS` (readback threads; `1` = serial),
  `MMFREE_PACK` (activation pack: `neon` default on aarch64, `tiled`, or `legacy`, whose cost
  depends on page placement — A/B only), `MMFREE_NO_PIPELINE=1` (serial projection
  sequencing), `MMFREE_CPU_BATCH`, plus the runtime
  knobs in `software/README.md`.

### Direct invocation

Without the board Makefile, export the manifest geometry yourself — the built-in defaults
(aWidth 16, xDim 32, maxN 4096, batch 1, 2-bit) match none of the shipped bitstreams:

```bash
cd ~/transfer
GEOM=$(grep -E '^(MMFREE_|NUM_DMA)' presets/mmfree_2_7b/preset.env | tr '\n' ' ')
M=models/ridger/MMfreeLM-2.7B
sudo env $GEOM ./src/software/integration/fpga_runner/build/mmfree-cli-fpga \
     0xA0010000 0xA0000000 /dev/uio4 /dev/udmabuf-act /dev/udmabuf-wt /dev/udmabuf-out \
     --blob $M/model.mmfree --packed-dir $M/packed-1p6 --backend both
```

The six positionals are the Core and DMA0 register bases, the UIO node and the udmabuf base
paths; on multi-port presets the port index is appended to the act/wt paths
(`/dev/udmabuf-wt0..3`). `compare_backends.sh` takes the same common args (it adds
`--backend` and `--bench --profile` itself), refuses to report timing unless the gate passes,
and writes logs to `./compare_out/` (`OUT_DIR=`; `PARSE_ONLY=1` re-renders from saved logs).
Run it under the same `sudo env $GEOM`.

### Weights and udmabuf sizing

The packed blobs come from `make model` / `make pack` on the build server (root README); the
blob's encoding must match the bitstream (`MMFREE_PACKED_WEIGHTS`) or the runner exits. The
runner holds **every** projection resident, so the weight udmabuf per port must be at least
the manifest's `total_bytes_per_port` — printed at startup as `resident=… B/port`. The overlay
sizes udmabuf-wt for the preset's own model and `deploy-hw` verifies it, so a short buffer
usually means a stale or borrowed overlay (or a larger `MODEL=` than the preset's); redeploy
the preset's own bundle, or rebuild with `UDMABUF_WT_SZ=<bytes>` (`PACKAGE_ONLY=1` re-runs only
the overlay steps). Large buffers need CMA headroom: check `grep -i cma /proc/meminfo`, and see
`cma_reclaim.sh` for the 2-bit 2.7B preset.
