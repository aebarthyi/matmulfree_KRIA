# mmfree integration — engine-side tooling

What turns the ternary matmul engine into a BitLinear backend for MMfreeLM: an offline
weight packer, the C engine API, and the runner that drives a real decode loop against it.

All three deployable presets are covered (370M / 1.3B / 2.7B — see `CoreConfig.scala`).
The activation path is **signed int16** (Q5.10).

> **Branch note:** `main` carries the production tooling below. The pytest suites and the
> board smoke tests live on the **`validation`** branch. The CPU reference model is the
> `matmulfreellmCPU` submodule (its own repo, checked out at the repo root).

## `mmfree_pack` — offline weight packer

Converts each BitLinear weight into the engine's col-tile-major per-port byte layout
(2-bit, or 1.6-bit with `--packed`) plus a manifest. Pure numpy — runs board-free and
torch-free; torch is imported lazily only to load a checkpoint.

```bash
# From a packed model blob (the usual path — provision.py already quantized it):
python -m mmfree_pack.cli --blob <model.mmfree> --out-dir packed/ \
    --manifest ../../generated/<preset>/preset.env

# ...or straight from a checkpoint (needs torch for loading):
python -m mmfree_pack.cli --checkpoint ridger/MMfreeLM-370M --out-dir packed/

# Outputs (a16 geometry -> 4 ports):
#   packed/weights.port{0..3}.bin   resident weight slices
#   packed/weights.manifest.json    {proj_name, byte_offset, N, M, s, ...}
```

Geometry comes from the preset manifest, so the packer never guesses the bitstream's
shape. For 370M: 97 projections, 85.3 MB resident per token at 2 bits (~68 MB packed).
i/f/g are fused into one 1024×3072 projection at pack time; `gate_up` is already fused in
the model.

### Numeric contract

```
s        = 1 / mean(|W|)                      (per projection, in the manifest)
w_t[m,n] = clamp(round(W[m,n] * s), -1, 1)    (ternary, == weight_quant)
engine:    acc[m] = Σ_n x_int[n] · w_t[n,m]
runtime:   y[m]   = acc[m] / (a_scale · s)    (the caller dequantizes; a_scale per-token)
```

The packer's byte layout is asserted **byte-identical** to `bench.c`'s pack loop by
`tests/test_packed_roundtrip.py`, which is verified on-board (errs=0). The geometry math
mirrors `mmfree_geom_init` in `software/include/mmfree_runtime.h`.

## `libmmfree` — the engine API (C)

`libmmfree/mmfree_lib.c` — BitLinear over the proven `mmfree_runtime`. Weights load into
**resident** udmabufs once; each COMPUTE just points the weight DMA at the projection's
packed offset (`mmfree_compute_off` — the Core addresses the stream via the DMA source
register, not the instruction ptr).

```c
mmfree_lib_t *h = mmfree_lib_open(&cfg);          // HW + resident wt/act/out bufs
for (p = 0; p < numPorts; p++)
    mmfree_lib_load_weights_file(h, p, "weights.portN.bin");   // once
int id = mmfree_register(h, byte_offset, N, M);   // per projection (from manifest)
mmfree_bitlinear(h, id, x_int16, acc_int32);      // LOAD_ACT -> COMPUTE@off -> STORE
```

It is compiled directly into `fpga_runner` (no shared library any more), and it does not
parse the manifest or handle scales — the caller reads the JSON manifest (offsets, N, M,
s), registers each projection for a `proj_id`, and applies `y = acc / (a_scale·s)`.

Completion is polled by default (userspace spin on the Core status word), which cuts the
IRQ round-trip out of the short decode ops. Set `MMFREE_POLL=0` for the UIO IRQ path.

## `fpga_runner` — the decode runner

The C++ driver that runs a real decode loop with the engine as the ternary backend, and
the correctness gate for it. See `fpga_runner/README.md`; from the board bundle it is
`make gate` (bit-exact CPU vs FPGA), `make run`, and `make gen`.

## Tests

Board-free host checks (`tests/`):

```bash
make -C tests            # split-chunk plan, threaded readback scatter, packed round-trip
```

The packed-ternary CPU kernels these blobs mirror (1.6-bit / 2-bit) are tested in the
submodule instead — `make -C ../../matmulfreellmCPU test`, since that is where the CPU
engine lives.
