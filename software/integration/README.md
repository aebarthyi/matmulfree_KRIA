# mmfree integration — engine-side tooling for MMfreeLM-370M

Implements the phases in [`docs/MMFREELLM_INTEGRATION.md`](../../docs/MMFREELLM_INTEGRATION.md)
that turn the ternary matmul engine into a drop-in BitLinear backend.

The PyTorch runtime ([matmulfreellmCPU](https://github.com/aebarthyi/matmulfreellmCPU))
is cloned at the repo root as `runtime/` (gitignored — it carries its own git
history). Target activation path: **signed int16** (`k26_mmfree370m_a16` preset).

## Phase B — `mmfree_pack` (done)

Offline weight packer. Converts each BitLinear weight into the engine's
col-tile-major 2-bit per-port byte layout + a manifest. Pure numpy — runs
board-free and torch-free; torch is imported lazily only to load a checkpoint.

```bash
# Pack a checkpoint into per-port blobs + manifest (needs torch for loading):
python -m mmfree_pack.cli --checkpoint ridger/MMfreeLM-370M --out-dir packed/

# Outputs (a16 geometry -> 4 ports):
#   packed/weights.port{0..3}.bin   resident weight slices (~21.3 MB each)
#   packed/weights.manifest.json    {proj_name, byte_offset, N, M, s, ...}
```

Per token: 97 projections, 85.3 MB resident. i/f/g are fused into one
1024×3072 projection at pack time; `gate_up` is already fused in the model.

### Numeric contract

```
s        = 1 / mean(|W|)                     (per projection, in the manifest)
w_t[m,n] = clamp(round(W[m,n] * s), -1, 1)    (ternary, == weight_quant)
engine:    acc[m] = Σ_n x_int[n] · w_t[n,m]
runtime:   y[m]   = acc[m] / (a_scale · s)    (Phase D divides; a_scale per-token)
```

The packer's byte layout is asserted **byte-identical** to `bench.c`'s pack loop
(`tests/test_pack_roundtrip.py`), which is verified on-board (errs=0). The
geometry math mirrors `mmfree_geom_init` in `software/include/mmfree_runtime.h`.

## Tests

```bash
cd software/integration && python -m pytest tests/ -q
```

## Phase C — `libmmfree.so` (done)

`libmmfree/` — BitLinear backend over the proven `mmfree_runtime`. Weights load
into **resident** udmabufs once; each COMPUTE just points the weight DMA at the
projection's packed offset (`mmfree_compute_off`, new in the runtime — the Core
addresses the stream via the DMA source register, not the instruction ptr).

```c
mmfree_lib_t *h = mmfree_lib_open(&cfg);          // HW + resident wt/act/out bufs
for (p = 0; p < numPorts; p++)
    mmfree_lib_load_weights_file(h, p, "weights.portN.bin");   // once
int id = mmfree_register(h, byte_offset, N, M);   // per projection (from manifest)
mmfree_bitlinear(h, id, x_int16, acc_int32);      // LOAD_ACT -> COMPUTE@off -> STORE
```

The lib does **not** parse the manifest or handle scales — the Python bridge
reads the JSON manifest (offsets, N, M, s), registers each projection for a
`proj_id`, and applies `y = acc / (a_scale·s)`. Build + test:

```bash
cd software && make lib        # build/libmmfree.so  (build natively on KRIA to deploy)
cd software && make test-lib   # board-free unit test: act pack + acc sign-expand
```

Not yet wired: `MMFREE_POLL=1` polling completion (deferred to Phase F perf —
correctness uses the proven UIO IRQ path).

## Phase D — `mmfree_bridge` (done, board-free tested)

Python bridge that runs MMfreeLM BitLinear projections on the engine.

- `quant.py` — int16 per-token quant (`a_scale = 32767/max|x|`) + dequant (`acc/(a_scale·s)`).
- `backend.py` — `RefBackend` (numpy `codes @ x_int`, a faithful board-free twin of
  `mmfree_bitlinear`) and `CtypesBackend` (the real lib).
- `ctypes_lib.py` — binding to `libmmfree.so`; asserts its struct against the C
  lib's `mmfree_lib_cfg_sizeof()` (ABI guard).
- `engine.py` — `MmfreeEngine.from_weights(...)` packs + registers; `project(name, x_norm)`
  does quant → backend → dequant.
- `runner.py` — `open_real_engine(weights, geom, libcfg, blob_dir)` sequences
  pack → size → open → load → register on the board; returns `(engine, lib)`.
- `patch.py` — `prepare_for_inference(model, engine)` monkeypatches every
  `FusedBitLinear.forward` to RMSNorm-in-torch → `engine.project`.

```bash
cd software/integration && python -m pytest tests/ -q   # 24 tests, board-free
```

**Unfused, correctness-first.** Each `i_proj/f_proj/g_proj` has its OWN RMSNorm,
so they do NOT share a normalized input — the bridge runs them as separate
projections (`fuse_ifg=False`), exactly mirroring the model's per-projection math.
i/f/g fusion needs the norm weights folded into the projection weights at pack
time; that's a **Phase F** perf step, not done here.

**int16 vs the model's int8.** The bridge feeds real-valued `x_norm` to the int16
engine (skipping the model's native int8 fake-quant) — higher precision, the point
of the a16 build. The RMSNorm uses `eps=1e-6` (the `layer_norm_linear_quant_fn`
default the model actually runs with, not the norm module's construction eps).

## Phase E — `phase_e_validate.py` (on-board driver)

Ties the bridge together on the KRIA and validates two ways:

1. **HW(int16) vs native(int8) reference** — top-1 token agreement + logit
   deltas (default; memory-frugal). Close, not exact — int16 is higher precision.
2. **`--ref-check`: HW vs RefBackend twin** — same int16 math, only the backend
   differs → must match to fp32 round-off. The rigorous hardware pass/fail. Uses
   a compact `Int8RefBackend` (codes as int8, ~0.37 GB) so the full 370M twin
   fits alongside the fp16 model on a 4 GB board.

```bash
# on the KRIA (needs torch + transformers + triton-cpu + libmmfree.so + udmabufs)
python phase_e_validate.py --checkpoint ridger/MMfreeLM-370M \
    --blob-dir /tmp/mmfree_blobs --so-path build/libmmfree.so --ref-check
```

Memory: model loaded fp16 (~0.74 GB); the fp32 weights dict (~1.5 GB) is freed
once the engine is built; HW weights live in CMA off-heap.

## Next phases
- **F** — perf: polling completion (`MMFREE_POLL`), i/f/g fusion (norm-folded),
  store(n)/load(n+1) overlap, minimize per-op Python overhead.
