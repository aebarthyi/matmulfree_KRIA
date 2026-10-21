# mmfree integration — engine-side tooling

What turns the ternary matmul engine into a BitLinear backend for MMfreeLM: an offline
weight packer, the C engine API, and the runner that drives a real decode loop against it.

All three deployable presets are covered (370M / 1.3B / 2.7B — see `CoreConfig.scala`).
The activation path is **signed int16** (Q5.10).

> **Branch note:** `main` carries the production tooling below. The pytest suites and the
> board smoke tests live on the **`validation`** branch. The CPU reference model is the
> `matmulfreellmCPU` submodule (its own repo, checked out at the repo root).

## `mmfree_pack` — offline weight packer

Converts each BitLinear weight into the engine's col-tile-major per-port byte layout —
2-bit, or 1.6-bit (5 trits/byte) for a `packedWeights` bitstream — plus a manifest. Pure
numpy; torch is imported lazily only to load a checkpoint. Normally run through the root
Makefile (`make pack` / `make model`); by hand, from this directory:

```bash
# From a provisioned model blob (what `make pack` does, and what the fpga_runner needs):
python3 -m mmfree_pack.cli --blob <model.mmfree> --out-dir packed/ \
    --manifest ../../generated/<preset>/preset.env

# ...or straight from a checkpoint (needs torch; see below):
python3 -m mmfree_pack.cli --checkpoint ridger/MMfreeLM-370M --out-dir packed/

# Outputs (4 ports):
#   packed/weights.port{0..3}.bin        resident weight slices
#   packed/weights.manifest.{json,tsv}   per projection: name, byte_offset, N, M, s, ...
```

Geometry and encoding come from the preset manifest (`--manifest`, or `$MMFREE_MANIFEST`), so
the blob always matches the bitstream; with neither, the packer assumes the a16 geometry
(`--awidth 16 --xdim 32`), 2-bit unless `--packed`. The `--blob` path emits one projection per
BitLinear — 145 for 370M (six per layer plus `lm_head`) — and is the only form the
`fpga_runner` consumes. `--checkpoint` re-quantizes from float and fuses i/f/g into one
projection (`layers.<L>.ifg`), whose tags the runner does not use. For 370M the resident
weights total 85.3 MB at 2 bits and 68.2 MB at 1.6 bits — streamed once per decode step.

### Numeric contract

```
s        = 1 / mean(|W|)                      (per projection, in the manifest)
w_t[m,n] = clamp(round(W[m,n] * s), -1, 1)    (ternary, == weight_quant)
engine:    acc[m] = Σ_n x_int[n] · w_t[n,m]
runtime:   y[m]   = acc[m] / (a_scale · s)    (the caller dequantizes; a_scale per-token)
```

The geometry math mirrors `mmfree_geom_init` in `software/include/mmfree_runtime.h`.
`tests/test_packed_roundtrip.py` checks the 1.6-bit layout against an independent emulation
of the RTL's `WeightUnpackGearbox`; on the board, `make gate` is the bit-exact end-to-end check.

## `libmmfree` — the engine API (C)

`libmmfree/mmfree_lib.c` — BitLinear over `mmfree_runtime`. Weights load into **resident**
udmabufs once; each COMPUTE just points the weight DMA at the projection's packed offset
(`mmfree_compute_off` — the Core addresses the stream via the DMA source register, not the
instruction ptr).

```c
mmfree_lib_t *h = mmfree_lib_open(&cfg);          // HW + resident wt/act/out bufs
for (p = 0; p < numPorts; p++)
    mmfree_lib_load_weights_file(h, p, "weights.portN.bin");   // once
int id = mmfree_register(h, byte_offset, N, M);   // per projection (from manifest)
mmfree_bitlinear(h, id, x_int16, acc_int32);      // LOAD_ACT -> COMPUTE@off -> STORE
```

`mmfree_bitlinear_batch` runs `b ≤ batchSize` activation rows through one weight stream, and
the `_split` variants run a wide projection as several column-tile COMPUTEs so each chunk's
readback overlaps the next chunk's compute (`MMFREE_SPLIT_CHUNKS`, see
`fpga_runner/README.md`). The library is compiled directly into `fpga_runner`; it does not
parse the manifest or handle scales — the caller reads the manifest (offsets, N, M, s),
registers each projection for a `proj_id`, and applies `y = acc / (a_scale·s)`.

Completion is polled by default (userspace spin on the Core status word), which cuts the
IRQ round-trip out of the short decode ops. Set `MMFREE_POLL=0` for the UIO IRQ path.

## `fpga_runner` — the decode runner

The C++ driver that runs a real decode loop with the engine as the ternary backend, and
the correctness gate for it. See `fpga_runner/README.md`; from the board bundle it is
`make gate` (bit-exact CPU vs FPGA), `make run`, `make streams`, `make sweep` and `make gen`.

## Tests

Board-free host checks (`tests/`):

```bash
make -C tests            # split-chunk plan, threaded readback scatter, 1.6-bit pack round-trip
```

The packed-ternary CPU kernels (1.6-bit / 2-bit) are tested in the submodule instead —
`make -C ../../matmulfreellmCPU test`, since that is where the CPU engine lives.
