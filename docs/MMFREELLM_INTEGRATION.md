# Integrating the ternary matmul engine into matmulfreellmCPU

Goal: run real **MMfreeLM-370M** inference with the BitLinear projections executed
on the KRIA systolic engine instead of triton-cpu, while RMSNorm, the HGRN
recurrence, gating, and sampling stay on the A53 in PyTorch.

Target runtime: <https://github.com/aebarthyi/matmulfreellmCPU> (PyTorch + Transformers
+ triton-cpu). The single swap point is **`FusedBitLinear`** in
`mmfreelm/ops/fusedbitnet.py`.

---

## 1. What the engine computes vs. what BitLinear needs

`FusedBitLinear.forward → layer_norm_linear_quant_fn` does, per projection:
1. RMSNorm(x)  →  `x_norm`
2. per-token activation quant: `a_scale = 127/max|x_norm|`, `x_int = round(x_norm·a_scale)` (int8)
3. per-tensor weight quant: `s = 1/mean|W|`, `w_t = clamp(round(W·s), -1, 1)` (ternary)
4. `y = F.linear(x_int/a_scale, w_t/s)`

Expand step 4:

```
y[m] = Σ_n (x_int[n]/a_scale) · (w_t[n,m]/s)
     = (1/(a_scale·s)) · Σ_n x_int[n]·w_t[n,m]
       └────────────────┘   └──────────────────┘
         scalar dequant       EXACTLY the engine's int32 output
```

**The engine produces `acc[m] = Σ_n x_int[n]·w_t[n,m]`; the runtime just divides by
`a_scale·s`.** `a_scale` is per-token (per row), `s` is per-projection (precompute once).
This is the whole numeric contract — no other change to model math.

**int16 activations (signed engine):** identical, with `a_scale = 32767/max|x|`,
`x_int` int16. Free on the engine (weights stay 2-bit), higher precision, skips the
int8 requant. Matches the `k26_mmfree370m_a16` preset (signedAct=true).

## 2. Projection inventory (per token, single-token decode)

| projection | (N in, M out) | input | count | note |
|---|---|---|---|---|
| i_proj, f_proj, g_proj | 1024 × 1024 | `hidden_states` (SHARED) | 3×24 | **fuse → one 1024×3072** |
| o_proj | 1024 × 1024 | gated recurrence out | 1×24 | separate input |
| gate_proj (gate+up) | 1024 × 5632 | MLP input | 1×24 | already fused in the model |
| down_proj | 2816 × 1024 | swiglu out | 1×24 | only non-pow2 N |
| lm_head | 1024 × 32000 | final norm out | 1 | |

i/f/g share `hidden_states` → one LOAD + one 1024×3072 COMPUTE replaces three ops
(96→ fewer ops/token; the biggest single perf lever). o_proj can't fuse (different input).
The recurrence, sigmoid, swiglu, g_norm are elementwise → stay on CPU.

## 3. Phases

### Phase A — Signed engine bitstream  ✅ (this repo)
`K26_MMFree370M_A16` preset (aWidth=16, xDim=32, signedAct=true), PE sign-extension,
Scala specs green. Build + deploy:
`PRESET=k26_mmfree370m_a16 REPACKAGE=1 ./scripts/build_all.sh`.

### Phase B — Offline weight packer (Python, one-time)
For each BitLinear: `s = 1/mean|W|`, `w_t = clamp(round(W·s),-1,1)`; reorder into the
engine's col-tile-major 2-bit layout across 4 port slices (mirror `bench.c` pack_weights /
`BenchMirrorSpec.packBenchWeightBuffer`). Emit 4 per-port blobs + a manifest
`(proj_name, byte_offset, N, M, s)`. For i/f/g, concatenate their weight matrices
along M into one 1024×3072 projection at pack time.

### Phase C — C runtime extension → `libmmfree.so`
Build `mmfree_runtime.c` as a shared lib plus:
- `mmfree_weights_load(manifest, blobs)` → load all packed weights into **resident**
  udmabufs once (4× ~24 MiB; 85 MB total). Never re-copied per op.
- `mmfree_bitlinear(proj_id, const int16_t *x, int32_t *y)` → LOAD_ACT(x),
  COMPUTE_MM at the projection's resident offset, STORE_OUT. Weights never move;
  only activations + offset change.
- **Polling completion** (`MMFREE_POLL=1`: spin on the status reg) to cut the
  ~30–60 µs/op UIO IRQ round-trip — significant when ~97 ops/token.
- Check the CMA pool first (`grep -i cma /proc/meminfo`); ~100 MB resident may need a
  `cma=256M` bootarg.

### Phase D — Python bridge
- ctypes wrapper over `libmmfree.so`.
- `prepare_for_inference()`: pack + register each projection with the lib (get a
  `proj_id`), store `s`.
- `forward(x)` (inference mode): `x_norm = RMSNorm(x)` (torch); `a_scale, x_int =
  quant16(x_norm)`; `acc = mmfree_bitlinear(proj_id, x_int)`; `y = acc / (a_scale·s)`.
- RMSNorm+quant for single-token is tiny — do it in plain torch/numpy at first
  rather than porting the fused triton kernel (which fake-quants to float; we need
  the integer codes + scale kept separate).

### Phase E — Correctness track (Python-first)
Monkeypatch `FusedBitLinear.forward` to call the engine; keep everything else in
PyTorch. Validate logits layer-by-layer vs the float reference within quant
tolerance. Slow (Python overhead) but proves the full numeric chain before any C
perf work. `generate.py` is the harness.

### Phase F — Performance track
i/f/g fusion (Phase B already packs it); overlap store(n)/load(n+1) via the cmd
FIFO; minimize per-op Python overhead (batch the per-layer calls). Target ~70–90
tok/s @ 250 MHz (vs the 15.4 tok/s packed-NEON CPU baseline).

## 4. Open questions / risks
- **CMA budget** for 85 MB resident weights (bootarg may be needed).
- **RMSNorm+quant kernel**: the triton fused kernel returns fake-quant floats; the
  bridge needs `(x_int, a_scale)`. Start with torch; optimize later if it shows up
  in the profile.
- **Per-op overhead** dominates small projections (measured ~30–60 µs/op); polling +
  i/f/g fusion are the mitigations, instruction-FIFO pipelining is the next step.
- **Accuracy**: int16 activations should match the fp16 reference closely; the int8
  path is the model's native quant and the fallback if int16 shows no accuracy win.
