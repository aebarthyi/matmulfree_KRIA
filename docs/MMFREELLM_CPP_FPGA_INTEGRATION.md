# Integrating the KRIA ternary engine into matmulfreellmCPU (C++)

Goal: run **matmulfreellmCPU** (the C++ HGRN-Bit / MMfreeLM forward) with the ternary
BitLinear projections executed on the KRIA systolic engine instead of the CPU, and produce
a head-to-head **CPU-only vs CPU+FPGA** performance comparison from the *same* binary.

This supersedes `docs/MMFREELLM_INTEGRATION.md` (which targeted the PyTorch+triton-cpu
runtime) for the C++ target. The driver layer (`software/integration/libmmfree`) is reused
unchanged.

---

## 0. Why this is clean

`cpp/src/kernels/bitlinear.cpp` already has an `ActQuant::FixedQ510` mode (the default in
`app/main.cpp`) whose own comment calls it "the HW datapath":

```
RMSNorm(x) -> y (fp32)                         [stays on CPU]
yq = sat_int16(round(y * 2^frac))              [stays on CPU; these ARE the int16 acts]
acc[o] = sum_n yq[n] * wq[o,n]   (int32)       <-- THE FPGA op (ternary_dot_i32 today)
out[o] = acc[o] / (2^frac * scale_w)           [stays on CPU]
```

The engine computes exactly `acc[o] = Σ_n x_int[n]·w_t[o,n]` (int32) from int16 acts +
ternary weights. So the FPGA is a **drop-in replacement for step 3 only**. Consequences:

- **Numerically exact**, not approximate: same int16 `yq`, same ternary `wq` → bit-identical
  int32 `acc` → identical dequant → **identical greedy token stream**. The comparison is
  apples-to-apples; any token-stream divergence is a bug, not quantization noise.
- `FixedQ510` uses a **static** Q5.10 scale (not per-token `a_scale`), so there is no
  per-row scale to carry into the engine — dequant is a per-projection constant
  `inv_fixed = 1/(2^frac · scale_w)`, already computed in `bitlinear.cpp`.
- The driver (`libmmfree.so`) is already board-verified (resident weights, multi-port,
  errs=0). Its `mmfree_bitlinear(proj_id, const int16_t* x, int32_t* acc)` is precisely the
  signature step 3 needs.

The matching engine bitstream is the existing **`k26_mmfree370m_a16`** preset
(aWidth=16, signed, xDim=32) — Phase A in the old doc, already built.

---

## 1. Projection inventory (what gets offloaded)

Every `proj()` call in `cpp/src/model/hgrn_model.cpp` is a ternary BitLinear and a candidate
for offload. Per token (decode, T=1):

| projection (tag) | N in | M out | input | count/token |
|---|---|---|---|---|
| `…i_proj`, `…f_proj`, `…g_proj` | 1024 | 1024 | `hs_` (SHARED) | 3 × 24 |
| `…o_proj` | 1024 | 1024 | g-norm out | 1 × 24 |
| `…gate_proj` | 1024 | 5632 | mlp-norm out | 1 × 24 |
| `…down_proj` | 2816 | 1024 | swiglu out | 1 × 24 |
| `lm_head` | 1024 | 32000 | final norm | 1 |

≈ 145 ternary projections/token. `--profile` already reports `matmul` vs `lm_head` vs other;
that breakdown is the lever and the scoreboard.

**lm_head is ternary here, and is offloaded.** In the actual C++ code `forward()` routes
lm_head through `proj()` → `bitlinear()` with `lm_head.wq` (int8 ternary), `lm_head.scale_w`,
`lm_head.normw` — i.e. it is a BitLinear like every other projection. (The stale note in
`matmulfreellmCPU/matmulfreellmCPU_PLAN.md §2.1` calling lm_head "dense fp32, the one true
GEMM" predates the port ternarizing it — disregard it.) We offload lm_head along with the
per-layer projections: it is the single largest matmul (M=32000), so it is the biggest
compute win; the cost is ~128 KB of int32 output streamed back per token and a hard
dependency on `maxM ≥ 32000` in the deployed bitstream.

> Orthogonal C++-port detail: `forward()` runs `model.norm` and then `bitlinear` re-norms
> with `lm_head.normw`; this is only correct if `lm_head.normw` is identity. It does not
> affect offload (the engine replaces only the int accumulate), but flag it to the C++
> author.

**Engine geometry must cover:** `maxN ≥ 2816` (down_proj contraction), `maxAcc ≥ 2816`,
`maxM ≥ 32000` (lm_head — now load-bearing since lm_head is offloaded). Verify the *deployed*
`k26_mmfree370m_a16` bitstream against these before bring-up (the 370m preset source sets
maxN=4096, maxM=32000).

---

## 2. Architecture: pluggable backend via dependency injection

The C++ model is a **separate git submodule** (`matmulfreellmCPU`, its own repo); libmmfree
lives here in the KRIA repo. To keep the submodule portable and committable on its own (no
`/dev/mem`/udmabuf/KRIA dependency), the FPGA code does **not** go into the submodule. Instead
the submodule exposes an abstract seam and the KRIA repo injects the FPGA implementation.

**In the submodule (FPGA-agnostic):**

```cpp
// cpp/include/mmfree/ternary_backend.hpp
namespace mmfree {
struct TernaryBackend {
  // acc[o] = sum_n x[n]*wq[o,n], o in [0,M), n in [0,N). x is int16 (Q-frac), wq ternary.
  virtual void matmul(int proj_id, const std::int16_t* x, std::int32_t* acc,
                      std::size_t N, std::size_t M) = 0;
  virtual ~TernaryBackend() = default;
};
}
```

- **CpuBackend** lives in the submodule (wraps the existing `simd::ternary_dot_i32` loop,
  proj_id ignored). It is the default — submodule builds and runs CPU-only, x86 included.
- `bitlinear()` gains a `TernaryBackend& be` + `int proj_id` param. In the `FixedQ510`
  branch, replace the per-`o` `ternary_dot_i32` loop with `be.matmul(proj_id, yq, acc, …)`,
  then keep the existing dequant. `rows > 1` (prefill) loops rows, one `matmul`/row (decode
  is T=1, the hot path). The `Float` branch stays CPU-only (golden ref).
- `Model` holds a `TernaryBackend*` (defaults to an internal CpuBackend) and exposes
  `set_backend(TernaryBackend*, const ProjRegistry&)`. `proj()` looks up `tag → proj_id`
  from a registry the caller supplies; CPU mode uses a trivial registry (all -1).
- **Factor the bench/profile loop out of `app/main.cpp` into a library function**
  (`run_bench(Model&, BenchOpts)` in the submodule) so both the CPU CLI and the KRIA-side
  FPGA runner time generation with *identical* code — the comparison stays apples-to-apples.

**In the KRIA repo (FPGA side):**
- `FpgaBackend` — owns a `mmfree_lib_t*`; `matmul()` calls `mmfree_bitlinear(h, proj_id, x,
  acc)`. Built against `software/integration/libmmfree`.
- A thin runner `mmfree-cli-fpga` that links the submodule's `libmmfreecpu` + libmmfree,
  builds the `tag → proj_id` registry from the pack manifest (§3), constructs `FpgaBackend`,
  calls `model.set_backend(...)`, and invokes the shared `run_bench`. This is where the
  `--backend fpga` path and `--manifest` flag live — not in the submodule.

Net: the submodule never references libmmfree; the KRIA repo owns the wiring. The submodule
change is a small, portable refactor (interface + setter + bench extraction).

---

## 3. Weight pack + projection registry (offline, one-time)

The engine needs weights in col-tile-major 2-bit per-port blobs + a manifest of
`(tag, byte_offset, N, M)`. **Pack directly from the C++ `model.mmfree` blob's already-ternary
`wq` and `scale_w`** (not by re-quantizing float weights) so the engine's ternary codes are
*identical* to what the CPU path uses — this is what guarantees the exact-match property.

- Extend `software/integration/mmfree_pack/packer.py` (or a small new tool) to read
  `model.mmfree` (mirror `cpp/src/io/weights.cpp`'s layout), and for each projection tag emit
  its packed ternary lanes into the per-port blobs, recording `(tag, byte_offset, N, M, scale_w)`
  in `weights.manifest.json`. Keep the existing col-tile-major / 4-lanes-per-byte layout
  (`pack_weights` in `bench.c`, mirrored in `packer.py`).
- At `Model` construction with the FPGA backend: `mmfree_lib_load_weights_file()` once per
  port (resident), then for each manifest entry `mmfree_register(byte_offset, N, M) → proj_id`
  and store `tag → proj_id`. Weights never move after load; per-op only acts + offset change.
- **CMA budget**: ~85 MB resident weights across 4 ports — check `cma=` bootarg
  (`grep -i cma /proc/meminfo`), same caveat as the old doc.

---

## 4. Phases

**P-A — Bitstream sanity (no C++).** Confirm `k26_mmfree370m_a16` is deployed and the
geometry covers down_proj/gate_proj/lm_head (§1). `make smoke` green on board.

**P-B — Offline packer + manifest.** ✅ *Done.* `mmfree_pack/blob.py` reads `model.mmfree`;
`iter_blob_projections` (model.py) yields each projection's already-ternary `wq` keyed by the
C++ tag; `python -m mmfree_pack.cli --blob model.mmfree --out-dir packed` emits per-port blobs
+ `weights.manifest.{json,tsv}`. The `.tsv` sidecar (`tag, byte_offset, N, M, n_outputs, s`) is
what the C++ runner parses (no JSON dep). Verified on-host: packed bytes are byte-identical to
a direct `pack_projection` (itself proven == bench.c), so engine codes == CPU `wq`
(`tests/test_blob_pack.py`).

**P-C — Backend seam (submodule refactor, no FPGA yet).** ✅ *Done, committed to submodule.*
`TernaryBackend` + `CpuBackend`, `bitlinear()`/`proj()`/`Model` routed through it,
`set_backend()`, `run_bench()` extracted into the library. Pure refactor — bit-identical to
the prior CPU path (self-check: 0 mismatches; `ctest` is the formal gate on the asset host).

**P-D — FpgaBackend + exact-match gate (KRIA repo).** ✅ *Done (board run pending).*
`software/integration/fpga_runner/`: `FpgaBackend` over libmmfree, `CompareBackend` for the
gate, a TSV `manifest` parser, and one `mmfree-cli-fpga` runner supporting
`--backend cpu|fpga|both` (so the whole comparison is one binary, one flag). `both` runs a
forward pass with `CompareBackend` and asserts every projection's int32 `acc` — incl. lm_head
(M=32000) — is **bit-identical** CPU vs engine. Host-verified: full compile+link, and the
Python↔C++ manifest TSV contract round-trips. The engine run + gate require the board.

**P-E — Comparison harness (the deliverable).** ✅ *Done (board run pending).*
`fpga_runner/compare_backends.sh`: runs the gate → CPU bench → FPGA bench, refuses to report
timing unless `GATE PASS`, and prints one decode-tok/s + profile table (§5). Log-parsing
verified on-host against synthetic bench output (`PARSE_ONLY=1` re-renders from saved logs).

**P-F — Performance (optional, after parity).** i/f/g fusion (shared `hs_` → one 1024×3072
register+compute, packed concatenated at §3); overlap store(n)/load(n+1) via the cmd FIFO;
polling completion (`MMFREE_POLL=1`) to cut the per-op IRQ round-trip (~145 ops/token makes
per-op overhead matter). These are pure speedups — parity already locked in P-D.

---

## 5. The comparison deliverable

Same model, same `FixedQ510` numerics, same shared `run_bench` timing — only the backend
differs — so the comparison isolates exactly "ternary matmul on A53 vs on the engine."

```bash
# CPU-only baseline (submodule CLI)
mmfree-cli       --bench --profile --mode fixed --gen 128 --reps 5

# CPU + FPGA (ternary projections incl. lm_head on the engine; KRIA runner)
mmfree-cli-fpga  --bench --profile --mode fixed --gen 128 --reps 5 --manifest weights.manifest.json
```

**Headline metric: decode tok/s (steady-state, T=1 per step)** — the dominant real-world cost
and the cleanest apples-to-apples number. Report, side by side:
- **decode tok/s** (headline), with prefill ms and overall tok/s as secondary context.
- **per-op profile** (`--profile`): `matmul`+`lm_head` % vs other ops — shows how much of the
  CPU time was the offloaded work and what now dominates (RMSNorm/scan/sample + per-op engine
  round-trip).
- **parity check** (the credibility guard): assert identical greedy token stream between the
  two backends on a fixed prompt (`--ids … --gen N --print-ids`) — proves the speedup isn't
  bought with drift. Exact by construction (§0), so this must pass.

Wrap both runs + the diff in a small script (`fpga_runner/compare_backends.sh`) emitting one
table; that table is the end artifact.

Expectation: matmul (incl. lm_head) is the dominant profiled op on CPU, so offload should move
decode tok/s up materially; the new ceiling becomes the elementwise CPU ops + per-op engine
round-trip (~145 ops/token), which P-F (i/f/g fusion + polling + store/load overlap) attacks.

---

## 6. Risks / open questions

- **scale_w / ternary-code identity.** Must pack from the blob's `wq`, not re-quantize float
  W — otherwise codes drift and the exact-match gate (P-D) fails. This is the one numeric
  invariant to protect.
- **frac_bits vs int16 saturation.** Q5.10 caps |y|≈32 before saturating; RMSNorm·weight is
  O(1) so headroom is large, but the exact-match gate will catch any saturation mismatch
  between CPU and engine (both saturate identically by construction — verify once).
- **Engine geometry coverage** for down_proj N=2816 and lm_head M=32000 (§1) — verify the
  deployed bitstream, not just the preset source.
- **Per-op overhead at ~145 ops/token.** UIO IRQ round-trip (~30–60 µs) × 145 can eat the
  win at small projections; polling + i/f/g fusion (P-F) are the mitigations. Measure in P-E
  before optimizing.
- **Prefill rows>1.** First cut loops rows through single-vector engine calls; fine for the
  decode-dominated tok/s metric, revisit if prefill dominates a chosen workload.
- **CMA budget** for ~85 MB resident weights (bootarg).
```
