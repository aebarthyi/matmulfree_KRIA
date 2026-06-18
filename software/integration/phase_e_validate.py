"""Phase E — on-board correctness validation for the MMfreeLM engine bridge.

Runs the real MMfreeLM-370M model on the KRIA with its BitLinear projections
executed on the systolic engine, and validates the result two ways:

  1. HW engine (int16) vs. the model's native forward (int8 fake-quant)
     -> top-1 token agreement + logit deltas. Expected close but NOT exact:
     int16 is higher precision by design (the point of the a16 build). This is
     the default, memory-frugal check.
  2. (--ref-check) HW engine vs. a numpy RefBackend twin (same int16 math, only
     the backend differs) -> must match to floating-point round-off. The
     rigorous hardware-correctness pass/fail. Holds the full ternary weight set
     in RAM (int8, ~370 MB for 370M) on top of the model, so it is opt-in.

Board-only: needs torch + transformers + triton-cpu + libmmfree.so + the
udmabufs. The numeric chain is the one Phase D verified board-free with
RefBackend; this proves it on real hardware over the full model.

Memory (4 GB board, ~1 GB reserved for udmabuf CMA -> ~3 GB usable): model is
loaded fp32 (~1.5 GB) so the CPU forward uses real mkldnn kernels instead of the
heavy fp16-upcast fallback (that fallback OOM'd the board). The fp32 weights dict
aliases the model's storage (numpy() shares the buffer when no dtype change is
needed, so ~0 extra) and is freed before any forward; HW weights live in CMA
off-heap. --ref-check adds ~0.37 GB of int8 codes. Peak ~1.9 GB.

Usage (on the KRIA):
    python phase_e_validate.py \
        --checkpoint ridger/MMfreeLM-370M \
        --blob-dir /tmp/mmfree_blobs \
        --so-path /path/to/libmmfree.so \
        --prompt "In a shocking finding," --max-new-tokens 16

    python phase_e_validate.py --no-hw --ref-check   # board-free numeric twin only
"""

from __future__ import annotations

import argparse
import gc
import sys
import threading
import time
from pathlib import Path

import numpy as np


def _meminfo():
    """Return (rss, avail, swap_free) in GiB, read from /proc. No deps (psutil-free)."""
    def _kb(path, key):
        try:
            with open(path) as f:
                for line in f:
                    if line.startswith(key):
                        return int(line.split()[1])  # value is in kB
        except OSError:
            pass
        return 0
    g = 1024.0 * 1024.0  # kB -> GiB
    return (_kb("/proc/self/status", "VmRSS:") / g,
            _kb("/proc/meminfo", "MemAvailable:") / g,
            _kb("/proc/meminfo", "SwapFree:") / g)


def _mem(label):
    rss, avail, swap = _meminfo()
    print(f"    [mem] {label}: RSS={rss:.2f}GiB  MemAvail={avail:.2f}GiB  SwapFree={swap:.2f}GiB",
          flush=True)


def _start_mem_monitor(interval=2.0):
    """Daemon thread: print a line each time RSS sets a new high-water mark.

    The kill in --no-hw --ref-check lands *inside* a single forward call, so stage
    markers alone can't show the peak. This logs the climb so a killed run still
    reveals how high RSS got and whether swap was actually exhausted.
    """
    state = {"peak": 0.0, "stop": False}

    def _run():
        while not state["stop"]:
            rss, avail, swap = _meminfo()
            if rss > state["peak"] + 0.05:  # only log meaningful (>50 MiB) jumps
                state["peak"] = rss
                print(f"    [mem-hi] RSS={rss:.2f}GiB  MemAvail={avail:.2f}GiB  "
                      f"SwapFree={swap:.2f}GiB", flush=True)
            time.sleep(interval)

    threading.Thread(target=_run, daemon=True).start()
    return state

# bridge + packer live alongside this script.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from mmfree_pack import Geometry  # noqa: E402
from mmfree_bridge.backend import RefBackend  # noqa: E402
from mmfree_bridge.ctypes_lib import LibConfig  # noqa: E402
from mmfree_bridge.engine import MmfreeEngine  # noqa: E402
from mmfree_bridge.patch import prepare_for_inference  # noqa: E402
from mmfree_bridge.runner import open_real_engine  # noqa: E402


class Int8RefBackend(RefBackend):
    """RefBackend that stores ternary codes as int8 (1/4 the RAM of int32).

    Exact same arithmetic — codes are in {-1,0,1}, and the (int8 @ int32) matmul
    is promoted to int64 by numpy so the accumulator never overflows.
    """

    def note_codes(self, proj_id: int, codes_MN: np.ndarray) -> None:
        self._codes[proj_id] = np.asarray(codes_MN, dtype=np.int8)

    def bitlinear(self, proj_id: int, x_int16: np.ndarray) -> np.ndarray:
        codes = self._codes[proj_id]
        if codes is None:
            raise RuntimeError(f"Int8RefBackend: no codes for proj {proj_id}")
        x = np.asarray(x_int16, dtype=np.int64)
        return (codes.astype(np.int64) @ x).astype(np.int32)


def _load_model(checkpoint: str):
    """Load tokenizer + model fp32. `import mmfreelm` registers the classes.

    fp32 (not .half()) on purpose: CPU has no native fp16 matmul, so .half() forces
    fusedbitnet onto a fp32-upcasting fallback (`mkldnn_matmul failed`) that is both
    slower and far heavier — it OOM'd the 4 GB board. fp32 uses real mkldnn kernels.
    Bonus: the fp32 weights dict then aliases the model's storage (numpy() shares the
    buffer when no dtype change is needed), so it costs ~0 extra RAM, not ~1.5 GB.
    """
    import mmfreelm  # noqa: F401  (registers MMfreeLM with AutoModel)  # noqa: PLC0415
    import torch  # noqa: PLC0415
    from transformers import AutoModelForCausalLM, AutoTokenizer  # noqa: PLC0415

    tok = AutoTokenizer.from_pretrained(checkpoint)
    model = AutoModelForCausalLM.from_pretrained(checkpoint, device_map="cpu").float()
    model.eval()
    return tok, model, torch


def _weights_dict(model, torch):
    """state_dict -> {name: float32 np.ndarray}, the form iter_projections wants."""
    return {k: v.detach().to(torch.float32).cpu().numpy()
            for k, v in model.state_dict().items()}


def _forward_logits(model, input_ids, torch):
    """Deterministic single forward pass; return logits as float32 numpy (1,T,V)."""
    with torch.no_grad():
        out = model(input_ids)
    return out.logits.detach().to(torch.float32).cpu().numpy()


def _compare(label, a, b, *, atol, rtol):
    """Print logit-delta metrics between two (1,T,V) logit tensors. Returns (allclose, agreement)."""
    a = a.reshape(-1, a.shape[-1])
    b = b.reshape(-1, b.shape[-1])
    abs_err = np.abs(a - b)
    rel_err = abs_err / np.maximum(np.abs(b), 1e-6)
    agree = float((a.argmax(-1) == b.argmax(-1)).mean())
    close = bool(np.allclose(a, b, atol=atol, rtol=rtol))
    print(f"  [{label}]")
    print(f"    max|Δ|={abs_err.max():.4e}  mean|Δ|={abs_err.mean():.4e}  max rel={rel_err.max():.4e}")
    print(f"    argmax agreement (all positions): {agree*100:.2f}%"
          + (f"  allclose(atol={atol},rtol={rtol})={close}" if np.isfinite(atol) else ""))
    return close, agree


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--checkpoint", default="ridger/MMfreeLM-370M", help="HF model id / dir")
    ap.add_argument("--blob-dir", default="/tmp/mmfree_blobs",
                    help="writable dir for packed per-port weight blobs")
    ap.add_argument("--so-path", default="libmmfree.so", help="path to libmmfree.so")
    ap.add_argument("--prompt", default="In a shocking finding, scientists discovered")
    ap.add_argument("--awidth", type=int, default=16)
    ap.add_argument("--xdim", type=int, default=32)
    ap.add_argument("--max-new-tokens", type=int, default=16)
    ap.add_argument("--atol", type=float, default=1e-2,
                    help="abs tol for HW-vs-Ref allclose (fp32 round-off margin)")
    ap.add_argument("--rtol", type=float, default=1e-3)
    ap.add_argument("--ref-check", action="store_true",
                    help="also build the RefBackend twin and assert HW==Ref (uses ~0.37 GB extra)")
    ap.add_argument("--no-hw", action="store_true", help="skip the hardware engine")
    ap.add_argument("--no-generate", action="store_true", help="skip the text sample")
    args = ap.parse_args(argv)

    if args.no_hw and not args.ref_check:
        ap.error("--no-hw needs --ref-check (otherwise there is nothing to run)")

    print(f"== Phase E validation ==\ncheckpoint={args.checkpoint}  "
          f"geom: aWidth={args.awidth} xDim={args.xdim}")
    _start_mem_monitor()
    _mem("startup")
    print("loading checkpoint (tokenizer + fp16 model)...", flush=True)
    t0 = time.time()
    tok, model, torch = _load_model(args.checkpoint)
    print(f"model loaded: {time.time()-t0:.1f}s", flush=True)
    _mem("after model load")
    geom = Geometry.derive(args.awidth, args.xdim)
    input_ids = tok(args.prompt, return_tensors="pt").input_ids
    print(f"prompt={args.prompt!r}  tokens={input_ids.shape[1]}")

    # native (int8 fake-quant) reference, model untouched — capture FIRST.
    print("native reference forward (fp32, prompt only)...", flush=True)
    t0 = time.time()
    ref_native = _forward_logits(model, input_ids, torch)
    print(f"native reference forward: {time.time()-t0:.1f}s", flush=True)
    _mem("after native forward")

    # weights dict feeds both engine builders; freed right after.
    print("building fp32 weights dict (370M params)...", flush=True)
    t0 = time.time()
    weights = _weights_dict(model, torch)
    print(f"weights dict built: {time.time()-t0:.1f}s", flush=True)
    _mem("after weights dict")

    # Build the engine(s) from the weights dict FIRST, then free the dict before any
    # forward runs — codes (RefBackend) and blobs (HW, in CMA) are already extracted, so
    # the dict's arrays must not coexist with the forward's working set.
    ref_eng = None
    if args.ref_check:
        print("quantizing weights -> Int8RefBackend engine (97 projections)...", flush=True)
        t0 = time.time()
        ref_eng = MmfreeEngine.from_weights(weights, geom, Int8RefBackend(), fuse_ifg=False)
        print(f"RefBackend engine built: {time.time()-t0:.1f}s", flush=True)
        _mem("after RefBackend engine built")

    hw_eng = None
    lib = None
    if not args.no_hw:
        libcfg = LibConfig(aWidth=args.awidth, xDim=args.xdim)
        print("packing blobs + opening HW engine (libmmfree.so, udmabufs)...", flush=True)
        t0 = time.time()
        hw_eng, lib = open_real_engine(weights, geom, libcfg, args.blob_dir,
                                       so_path=args.so_path, fuse_ifg=False)
        print(f"HW engine opened: {time.time()-t0:.1f}s", flush=True)

    del weights; gc.collect()                          # reclaim the dict before the forwards
    _mem("after freeing weights dict")

    # Forwards run sequentially; each prepare_for_inference re-patches the projections.
    ref_logits = None
    if ref_eng is not None:
        prepare_for_inference(model, ref_eng)
        print("RefBackend engine forward (numpy int matmul, prompt only)...", flush=True)
        t0 = time.time()
        ref_logits = _forward_logits(model, input_ids, torch)
        print(f"RefBackend engine forward: {time.time()-t0:.1f}s ({len(ref_eng.proj)} projections)", flush=True)
        _mem("after RefBackend forward")

    hw_logits = None
    if hw_eng is not None:
        prepare_for_inference(model, hw_eng)
        print("HW engine forward (systolic array, prompt only)...", flush=True)
        t0 = time.time()
        hw_logits = _forward_logits(model, input_ids, torch)
        print(f"HW engine forward: {time.time()-t0:.1f}s ({len(hw_eng.proj)} projections)", flush=True)
        _mem("after HW forward")

    print("\n-- accuracy: int16 engine vs native int8 reference --")
    probe = hw_logits if hw_logits is not None else ref_logits
    _compare("int16 vs native(int8)", probe, ref_native, atol=np.inf, rtol=np.inf)

    if hw_logits is not None and ref_logits is not None:
        print("\n-- HARDWARE CORRECTNESS: HW engine vs RefBackend twin --")
        ok, _ = _compare("HW vs Ref (both int16)", hw_logits, ref_logits,
                         atol=args.atol, rtol=args.rtol)
        print(f"\n  ==> hardware correctness: {'PASS' if ok else 'FAIL'}")

    if not args.no_generate and not args.no_hw:
        print("\n-- generation sample (engine-backed, greedy) --")
        with torch.no_grad():
            out = model.generate(input_ids,
                                 max_length=input_ids.shape[1] + args.max_new_tokens,
                                 do_sample=False, pad_token_id=tok.eos_token_id)
        print("  " + tok.batch_decode(out, skip_special_tokens=True)[0])

    if lib is not None:
        lib.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
