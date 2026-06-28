# mmfree-cli-fpga — CPU vs CPU+FPGA comparison runner (Phase D)

Runs **matmulfreellmCPU** (the C++ HGRN-Bit / MMfreeLM forward) with the ternary
BitLinear projections executed on the KRIA systolic engine, and compares it head-to-head
against the pure-CPU path. Same model, same `FixedQ510` numerics, same `run_bench` timing —
only the `TernaryBackend` differs — so the measurement isolates exactly *ternary matmul on
the A53 vs on the engine*.

## Pieces

| file | role |
|---|---|
| `fpga_backend.{hpp,cpp}` | `FpgaBackend` (drives the engine via libmmfree) + `CompareBackend` (the `both` gate) |
| `manifest.{hpp,cpp}` | parse the packer's `<prefix>.manifest.tsv` → `tag → (byte_offset, N, M)` |
| `main_fpga.cpp` | the runner: open engine, load resident weights, register, `set_backend`, bench / gate |
| `Makefile` | builds `libmmfreecpu.a` (submodule CMake) + the C engine driver + the runner |

The submodule stays FPGA-agnostic (dependency injection): it exposes only the abstract
`TernaryBackend` + `Model::set_backend`; all engine code lives here.

## 1. Pack the weights (offline, build host — no torch, no board)

Pack directly from the C++ runtime's `model.mmfree` so the engine's ternary codes are
**identical** to the CPU path's `wq` (the exact-match invariant):

```bash
cd software/integration
python -m mmfree_pack.cli --blob ../../matmulfreellmCPU/cpp/model.mmfree --out-dir packed
# -> packed/weights.port{0..3}.bin  +  packed/weights.manifest.{json,tsv}
```

Copy `packed/` and `model.mmfree` (+ `tokenizer.mmtok`) to the board.

### udmabuf sizing (one-time, on the board)

Unlike the bench (which streams one projection at a time), this runner holds **all**
projections **resident** — the weight udmabuf per port must be ≥ the manifest's
`total_bytes_per_port` (the full a16 370M model is **~21.3 MiB/port**, vs the bench overlay's
default 8 MiB). The runner prints the exact requirement on startup (`resident=… B/port`) and
on an open failure. Regenerate + redeploy the DT overlay with a bigger `wt` buffer:

```bash
# on the build host — reuse the packaged IP + bitstream, only the overlay changes:
UDMABUF_WT_SZ=0x01800000 REPACKAGE=0 PRESET=k26_mmfree370m_a16 ./scripts/build_all.sh
# -> transfer/<preset>/{...dtbo}; redeploy that folder and reload it on the board.
```

`act` (64 KiB) and `out` (128 KiB) from the a16 preset already suffice. Check CMA headroom
first — 4 ports × 24 MiB ≈ 96 MiB: `grep -i cma /proc/meminfo` (add a `cma=256M` bootarg if
`CmaTotal` is short).

## 2. Build (natively on the KRIA, aarch64)

```bash
cd software/integration/fpga_runner
make                      # -> build/mmfree-cli-fpga   (SIMD auto-selected: ARM on aarch64)
```

Requires the `matmulfreellmCPU` submodule checked out (`git submodule update --init`) and
CMake. An x86 host build is a compile-check only — the engine driver needs the PL.

## 3. Run

Board config args mirror `bench.c` / `smoke_test.c`; geometry defaults to the a16 engine
(override with `MMFREE_AWIDTH/XDIM/MAXACC/MAXN/MAXM`). Use the real board device nodes.

```bash
ARGS="0xA0010000 0xA0000000 /dev/uio4 /dev/udmabuf-act /dev/udmabuf-wt /dev/udmabuf-out \
      --blob model.mmfree --packed-dir packed"

# (a) exact-match gate — every projection's int32 acc, CPU vs engine, must be identical
sudo ./build/mmfree-cli-fpga $ARGS --backend both --ids 1,415,310,29871,13

# (b) CPU baseline                          (headline: decode tok/s)
sudo ./build/mmfree-cli-fpga $ARGS --backend cpu  --bench --gen 128 --reps 5 --profile

# (c) CPU + FPGA                            (same harness, projections on the engine)
sudo ./build/mmfree-cli-fpga $ARGS --backend fpga --bench --gen 128 --reps 5 --profile
```

Run **(a) first** — it must print `GATE PASS (CPU == FPGA)` before the timing numbers in
(b)/(c) mean anything.

### One-shot comparison

`compare_backends.sh` does all three in order (gate → CPU bench → FPGA bench), refuses to
report timing unless the gate passes, and prints one decode-tok/s + profile table:

```bash
sudo ./compare_backends.sh $ARGS --gen 128 --reps 5 --ids 1,415,310,29871,13
```

Pass the runner's COMMON args (board config + `--blob`/`--packed-dir` + `--gen`/`--reps`/
`--ids`); the script adds `--backend {both,cpu,fpga}` and `--bench --profile` itself. Logs
land in `./compare_out/` (override `OUT_DIR=`); `PARSE_ONLY=1` re-renders the table from
saved logs without re-running. Example output:

```
================ CPU vs CPU+FPGA  (decode steady-state) ================
metric                                      CPU       CPU+FPGA
decode tok/s  [headline]                  15.40          58.90   (3.82x)
prefill ms                                120.0           44.2
overall tok/s                             14.80          52.30
matmul+lm_head % of profile               78.5%          31.2%
========================================================================
parity gate: PASS
```

(numbers above are illustrative.)

## Notes

- The engine only runs the integer path — use `--mode fixed` (the default). `--mode float`
  is CPU-only (the triton golden reference).
- `--prompt TEXT` needs `--tokenizer`; `--ids a,b,c` bypasses the tokenizer (deterministic,
  preferred for the gate). With no input a fixed pseudo-prompt is used.
- Per-op engine round-trip (~145 ops/token) is the expected post-offload bottleneck.
  Phase-F levers: `MMFREE_POLL=1` (default for dedicated
  decode, +24%) and b-aware pack (+8%) are landed; the i/f/g cluster pipeline (overlap
  pack/readback under the engine) is next. NOTE: i/f/g *weight* fusion is a dead end —
  each BitLinear has its own inner RMSNorm so the three quantized activations differ.
