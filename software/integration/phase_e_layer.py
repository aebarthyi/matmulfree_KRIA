"""Phase E (single-layer) — FPGA vs CPU per-projection check.

A focused, fast full-model-free check: take ONE decoder layer, run each of its
BitLinear projections on both backends with identical inputs, and report

  1. functional correctness — the CPU RefBackend (numpy integer matmul) is the
     golden model; the FPGA accumulators must match it EXACTLY (both compute the
     same int32 dot product Σ_n x_int[n]·codes[m,n], so any nonzero delta is a
     real hardware discrepancy), and
  2. speed — per-projection latency on the FPGA vs the CPU, plus throughput.

Why one layer: every projection SHAPE in the model recurs each layer, so a
single layer exercises all of them. The full-model forward re-streams every
weight matrix once per token (×T) and adds a 16-step generation loop — neither
is needed to compare the backends. This builds engines from just that layer's
6 projections (re-indexed to layer 0), so setup is a few small blobs, not 370M
params.

We bypass the torch forward entirely and feed synthetic post-RMSNorm-shaped
activations straight into engine.project()'s inner contract (quantize_act_int16
-> backend.bitlinear -> compare/​time the raw int32 accumulator). The integer
matmul the FPGA performs does not depend on activation realism, so this is a
faithful correctness test; --normal vs --uniform just changes the int16 spread.

Usage (on the KRIA):
    python phase_e_layer.py --layer 0 --reps 50 \
        --so-path /path/to/libmmfree.so --blob-dir /tmp/mmfree_layer_blobs
"""

from __future__ import annotations

import argparse
import re
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mmfree_pack import Geometry, manifest_path  # noqa: E402
from mmfree_bridge.backend import RefBackend  # noqa: E402
from mmfree_bridge.ctypes_lib import LibConfig  # noqa: E402
from mmfree_bridge.engine import MmfreeEngine  # noqa: E402
from mmfree_bridge.quant import quantize_act_int16  # noqa: E402
from mmfree_bridge.runner import open_real_engine  # noqa: E402
from phase_e_common import Int8RefBackend, _mem, _start_mem_monitor  # noqa: E402


def _load_single_layer_weights(checkpoint: str, layer: int):
    """name -> fp32 np.ndarray for ONLY decoder layer `layer`, re-indexed to 0.

    Pulls just the `layers.{layer}.` tensors straight from the cached safetensors
    via safe_open (mmap'd — only the keys we touch are materialized), instead of
    instantiating the whole 370M model in fp32 and copying every tensor to numpy
    just to throw all but one layer away. RAM drops from whole-model to ~6 small
    matrices, which is what kept this from finishing on the 4 GB A53.

    framework="pt" + .float() handles weights stored as bf16/fp16 (numpy can't
    read those dtypes directly)."""
    import json  # noqa: PLC0415
    from huggingface_hub import hf_hub_download  # noqa: PLC0415
    from safetensors import safe_open  # noqa: PLC0415

    tag = f"layers.{layer}."
    try:  # sharded checkpoint?
        idx = hf_hub_download(checkpoint, "model.safetensors.index.json")
        with open(idx) as fh:
            files = sorted(set(json.load(fh)["weight_map"].values()))
    except Exception:
        files = ["model.safetensors"]

    out = {}
    for f in files:
        with safe_open(hf_hub_download(checkpoint, f), framework="pt") as st:
            for k in st.keys():
                if tag in k:
                    out[k.replace(tag, "layers.0.")] = (
                        st.get_tensor(k).to("cpu").float().numpy())
    if not out:
        raise SystemExit(f"no weights found for layer {layer} in {checkpoint}")
    return out


def _single_layer_weights(weights, layer: int):
    """Select one decoder layer's keys and re-index it to layer 0.

    iter_projections() loops range(num_layers) and KeyErrors on any gap, so we
    can't just hand it layer L in isolation — rename `layers.{L}.` -> `layers.0.`
    so it sees a clean one-layer model (lm_head and all other layers dropped)."""
    tag = f"layers.{layer}."
    out = {k.replace(tag, "layers.0."): v for k, v in weights.items() if tag in k}
    if not out:
        raise SystemExit(f"no weights found for layer {layer} "
                         f"(keys look like {next(iter(weights), '???')!r})")
    return out


def _make_acts(eng, names, rng, dist: str):
    """One activation vector per projection, sized to that projection's N."""
    acts = {}
    for name in names:
        N = eng.proj[name].N
        if dist == "uniform":
            x = rng.uniform(-1.0, 1.0, size=N)
        else:  # normal — post-RMSNorm activations are roughly unit-variance
            x = rng.standard_normal(N)
        acts[name] = quantize_act_int16(x)[0]  # int16 (N,); scale irrelevant here
    return acts


def _time_backend(backend, pid, x_int, reps):
    """Median per-op latency (s) of backend.bitlinear over `reps`, 1 warmup."""
    backend.bitlinear(pid, x_int)  # warmup (first FPGA op may set up DMA)
    samples = []
    for _ in range(reps):
        t0 = time.perf_counter()
        backend.bitlinear(pid, x_int)
        samples.append(time.perf_counter() - t0)
    return float(np.median(samples)), float(np.min(samples))


def _fmt_t(s: float) -> str:
    return f"{s*1e6:7.1f}us" if s < 1e-3 else f"{s*1e3:7.2f}ms"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--checkpoint", default="ridger/MMfreeLM-370M")
    ap.add_argument("--blob-dir", default="/tmp/mmfree_layer_blobs")
    ap.add_argument("--so-path", default="libmmfree.so")
    ap.add_argument("--awidth", type=int, default=16)
    ap.add_argument("--xdim", type=int, default=32)
    ap.add_argument("--manifest", default=None,
                    help="preset.json/.env (control.EmitCore) — geometry is read from "
                         "it, overriding --awidth/--xdim. Defaults to $MMFREE_MANIFEST.")
    ap.add_argument("--layer", type=int, default=0, help="which decoder layer to probe")
    ap.add_argument("--reps", type=int, default=50, help="timing repetitions per projection")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--dist", choices=("normal", "uniform"), default="normal",
                    help="synthetic activation distribution before int16 quant")
    ap.add_argument("--ref-only", action="store_true",
                    help="skip the FPGA (dev/debug; nothing to compare against)")
    ap.add_argument("--fuse", action="store_true",
                    help="fuse i/f/g into one (3*hidden, N) projection (gate+up already "
                         "fused by the model) — one LOAD+COMPUTE replaces three, paying "
                         "the per-call overhead once. HW-vs-CPU stays exact (same input "
                         "to both); only end-to-end model match needs norm folding.")
    args = ap.parse_args(argv)

    print(f"== Phase E single-layer FPGA-vs-CPU ==\n"
          f"checkpoint={args.checkpoint}  layer={args.layer}  fuse_ifg={args.fuse}  "
          f"geom: aWidth={args.awidth} xDim={args.xdim}  reps={args.reps}")
    _start_mem_monitor()
    _mem("startup")

    print(f"loading layer {args.layer} weights (lazy, single-layer)...", flush=True)
    t0 = time.time()
    wl = _load_single_layer_weights(args.checkpoint, args.layer)
    print(f"layer {args.layer} weights loaded: {time.time()-t0:.1f}s", flush=True)
    _mem("after load")

    _mpath = manifest_path(args.manifest)
    geom = Geometry.from_manifest(_mpath) if _mpath else Geometry.derive(args.awidth, args.xdim)

    # CPU golden model (numpy integer matmul, int8 codes to keep it light).
    print("building CPU RefBackend engine...", flush=True)
    t0 = time.time()
    ref_eng = MmfreeEngine.from_weights(wl, geom, Int8RefBackend(), fuse_ifg=args.fuse)
    print(f"CPU engine built: {time.time()-t0:.1f}s "
          f"({len(ref_eng.proj)} projections)", flush=True)

    # FPGA engine — same projections, same pack order, so proj_ids align.
    hw_eng = lib = None
    if not args.ref_only:
        libcfg = (LibConfig.from_manifest(_mpath) if _mpath
                  else LibConfig(aWidth=args.awidth, xDim=args.xdim))
        print("packing blobs + opening FPGA engine...", flush=True)
        t0 = time.time()
        hw_eng, lib = open_real_engine(wl, geom, libcfg, args.blob_dir,
                                       so_path=args.so_path, fuse_ifg=args.fuse)
        print(f"FPGA engine opened: {time.time()-t0:.1f}s", flush=True)
    _mem("after engines built")

    names = list(ref_eng.proj.keys())  # compute order
    rng = np.random.default_rng(args.seed)
    acts = _make_acts(ref_eng, names, rng, args.dist)

    hdr = (f"\n{'projection':<24}{'N':>6}{'M':>7}  | "
           f"{'CPU lat':>10}{'FPGA lat':>11}{'speedup':>9}{'FPGA GMAC/s':>13}  | "
           f"{'max|Δacc|':>10}{'mism':>6}")
    print(hdr)
    print("-" * len(hdr))

    all_exact = True
    tot_cpu = tot_hw = tot_macs = 0.0
    for name in names:
        p = ref_eng.proj[name]
        x = acts[name]
        macs = float(p.N) * float(p.M)

        acc_ref = np.asarray(ref_eng.backend.bitlinear(p.proj_id, x), dtype=np.int64)
        cpu_med, _ = _time_backend(ref_eng.backend, p.proj_id, x, args.reps)
        tot_cpu += cpu_med

        if hw_eng is not None:
            pid_hw = hw_eng.proj[name].proj_id
            acc_hw = np.asarray(hw_eng.backend.bitlinear(pid_hw, x), dtype=np.int64)
            hw_med, _ = _time_backend(hw_eng.backend, pid_hw, x, args.reps)
            tot_hw += hw_med
            tot_macs += macs
            dmax = int(np.abs(acc_ref - acc_hw).max()) if acc_ref.shape == acc_hw.shape else -1
            nmis = int((acc_ref != acc_hw).sum()) if acc_ref.shape == acc_hw.shape else acc_ref.size
            all_exact &= (dmax == 0)
            gmac = macs / hw_med / 1e9
            speed = f"{cpu_med/hw_med:6.2f}x"
            print(f"{name:<24}{p.N:>6}{p.M:>7}  | {_fmt_t(cpu_med)}{_fmt_t(hw_med)}"
                  f"{speed:>9}{gmac:>13.2f}  | {dmax:>10}{nmis:>6}")
        else:
            print(f"{name:<24}{p.N:>6}{p.M:>7}  | {_fmt_t(cpu_med)}"
                  f"{'(no FPGA)':>11}{'-':>9}{'-':>13}  | {'-':>10}{'-':>6}")

    if hw_eng is not None:
        print("-" * len(hdr))
        agg = tot_cpu / tot_hw if tot_hw else float("nan")
        print(f"\nlayer total: CPU {_fmt_t(tot_cpu)}  FPGA {_fmt_t(tot_hw)}  "
              f"({agg:.2f}x)  FPGA {tot_macs/tot_hw/1e9:.2f} GMAC/s aggregate")
        print(f"\n  ==> functional correctness (FPGA == CPU golden, integer-exact): "
              f"{'PASS' if all_exact else 'FAIL'}")

    if lib is not None:
        lib.close()
    return 0 if all_exact else 1


if __name__ == "__main__":
    raise SystemExit(main())
