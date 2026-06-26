"""Phase E (single-layer, end-to-end) — real decoder-layer wall-clock.

Where phase_e_layer.py isolates the projection matmuls, this runs a WHOLE
HGRNBitBlock (one decoder layer) — attn_norm, the i/f/g/o projections, the
short-conv + gated-linear-attention recurrence, mlp_norm, gate/up + SiLU + down,
and the residuals — and times it two ways:

  * CPU-only       : the model's native forward (everything in torch fp32)
  * FPGA proj      : the same layer with its BitLinear projections offloaded to
                     the engine, while all the glue (norms, recurrence, SwiGLU,
                     residuals) still runs in torch on the A53.

This is the measurement that shows where the full-model time actually goes: the
projection-only harness reports ~4 ms/layer of engine compute, but the real
per-layer wall-clock includes the CPU glue this script does NOT skip. Each mode
is also broken into projection-time vs glue-time, and the layer's output tensor
is compared between modes (int16 engine vs int8-native — close, not exact).

Method: run model(input_ids) just far enough to capture the real (args, kwargs)
the model hands layer L (a forward_pre_hook short-circuits there), then replay
that captured input through the layer module directly — so the glue executes
with valid state and both modes see the identical input.

Usage (on the KRIA, needs root for the core mmap):
    sudo -E $(which python) phase_e_layer_e2e.py --layer 0 --reps 20 \
        --so-path ../build/libmmfree.so --blob-dir /tmp/mmfree_layer_blobs
"""

from __future__ import annotations

import argparse
import sys
import time
import types
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mmfree_pack import Geometry  # noqa: E402
from mmfree_bridge.ctypes_lib import LibConfig  # noqa: E402
from mmfree_bridge.patch import DEFAULT_EPS, _make_forward  # noqa: E402
from mmfree_bridge.runner import open_real_engine  # noqa: E402
from phase_e_layer import _single_layer_weights, _fmt_t  # noqa: E402
from phase_e_validate import (_load_model, _mem, _meminfo,  # noqa: E402
                              _start_mem_monitor, _weights_dict)


class _StopForward(Exception):
    """Raised inside the capture hook to abort the forward once we have layer L's input."""


def _find_block(model, layer: int):
    """Return the HGRNBitBlock module for `layer` (tolerate the model. prefix)."""
    for qn, mod in model.named_modules():
        if qn.endswith(f"layers.{layer}") and mod.__class__.__name__ == "HGRNBitBlock":
            return mod
    raise SystemExit(f"could not locate HGRNBitBlock for layer {layer}")


def _clone(v, torch):
    if torch.is_tensor(v):
        return v.detach().clone()
    if isinstance(v, (list, tuple)):
        return type(v)(_clone(x, torch) for x in v)
    if isinstance(v, dict):
        return {k: _clone(x, torch) for k, x in v.items()}
    return v


def _capture_layer_input(model, block, input_ids, torch):
    """Run the model far enough to grab the (args, kwargs) it passes to `block`."""
    grab = {}

    def pre_hook(_mod, args, kwargs):
        grab["args"], grab["kwargs"] = args, kwargs
        raise _StopForward

    h = block.register_forward_pre_hook(pre_hook, with_kwargs=True)
    try:
        with torch.no_grad():
            model(input_ids, use_cache=False)   # use_cache=False -> no in-place cache mutation
    except _StopForward:
        pass
    finally:
        h.remove()
    if "args" not in grab:
        raise SystemExit("capture hook never fired (layer not reached?)")
    return _clone(grab["args"], torch), _clone(grab["kwargs"], torch)


def _patch_block(block, engine, eps, acc):
    """Point this block's FusedBitLinear modules at the engine; accumulate their
    wall-time into acc[0]. Returns originals so we can restore the native path."""
    from mmfreelm.ops.fusedbitnet import FusedBitLinear  # noqa: PLC0415

    base = _make_forward(engine, eps)  # exact deployed bridge forward (norm + engine)

    def timed(self, x):
        t = time.perf_counter()
        y = base(self, x)
        acc[0] += time.perf_counter() - t
        return y

    targets = []
    for rel, mod in block.named_modules():
        if isinstance(mod, FusedBitLinear):
            name = f"layers.0.{rel}"          # engine is the layer re-indexed to 0
            if name not in engine.proj:
                raise KeyError(f"no packed projection '{name}' for module '{rel}'")
            mod._mmfree_name = name
            mod.forward = types.MethodType(timed, mod)
            targets.append(mod)
    if not targets:
        raise RuntimeError("no FusedBitLinear modules in the block")
    return targets


def _instrument_native(block, acc, torch):
    """Wrap each FusedBitLinear's native forward to accumulate its time into acc[0]."""
    from mmfreelm.ops.fusedbitnet import FusedBitLinear  # noqa: PLC0415
    cls_fwd = FusedBitLinear.forward

    def timed(self, x):
        t = time.perf_counter()
        y = cls_fwd(self, x)
        acc[0] += time.perf_counter() - t
        return y

    targets = []
    for _rel, mod in block.named_modules():
        if isinstance(mod, FusedBitLinear):
            mod.forward = types.MethodType(timed, mod)
            targets.append(mod)
    return targets


def _use_naive_recurrence():
    """Swap the triton-cpu fused HGRN recurrence for the pure-torch reference.

    fused_recurrent_hgrn is a @triton.autotune kernel with 12 configs; its first
    call JIT-compiles and benchmarks all 12 through LLVM on the A53 — the single
    biggest chunk of the "warmup takes forever + RSS explodes" cost. naive_recurrent_hgrn
    computes the identical h = g*h + x cumulative scan in fp32, same I/O shapes
    ((b,h,l,d) -> o, final_state), so the layer output is unchanged. hgrn_bit calls
    it by the name bound in its own module, so we rebind there."""
    from mmfreelm.ops.hgrn.naive import naive_recurrent_hgrn  # noqa: PLC0415
    import mmfreelm.layers.hgrn_bit as hb  # noqa: PLC0415
    hb.fused_recurrent_hgrn = naive_recurrent_hgrn


def _unpatch(targets):
    for mod in targets:
        if "forward" in vars(mod):
            del mod.forward          # fall back to the class (native) forward


def _malloc_trim():
    """Hand glibc's freed-but-retained heap back to the OS.

    gc.collect() drops the Python/torch refs, but glibc's *dynamic* mmap threshold
    means the freed tensor pages stay parked in the heap arena (RSS doesn't fall) —
    which is why nulling 23 layers only reclaimed ~0.36 GB. malloc_trim(0) releases
    the top of the arena, so RSS actually tracks live memory again. No-op off glibc."""
    import ctypes  # noqa: PLC0415
    try:
        ctypes.CDLL("libc.so.6").malloc_trim(0)
    except (OSError, AttributeError):
        pass


def _free_all_but_block(model, block):
    """Drop every part of the model except `block`, then gc + malloc_trim.

    The full 370M fp32 model is loaded only to (a) capture layer L's real input and
    (b) extract its weights for packing. Both are done by the time we call this, and
    the timed forwards invoke `block(*args, **kwargs)` directly — they never touch the
    embeddings, the other ~23 decoder layers, the final norm, or lm_head. On the 4 GB
    A53 those ~1.3 GB of unused params are what tips MemAvail to near-zero and pushes
    the forward (and triton-cpu's first-call JIT) into swap, which is the real "why is
    this so slow". `block` keeps its own params alive (held by the local var), so
    freeing the rest is safe. malloc_trim is what makes RSS actually drop."""
    import gc  # noqa: PLC0415

    inner = getattr(model, "model", model)
    for attr in ("layers", "embeddings", "norm", "lower_bounds"):
        if hasattr(inner, attr):
            setattr(inner, attr, None)
    if hasattr(model, "lm_head"):
        model.lm_head = None
    gc.collect()
    _malloc_trim()


def _thin_triton_autotune(keep=1):
    """Truncate each triton @autotune kernel's config list to `keep` entries.

    The cold-forward hang on the A53 is triton-cpu compiling EVERY autotune config
    through LLVM — 6 for the BitLinear quant kernel (fusedbitnet.py), 12 for the
    recurrence (recurrent_fuse.py) — i.e. ~24 slow codegen passes per layer, which
    is what fills ~/.triton/cache for an hour. Autotune only RANKS configs that are
    all already numerically correct, so keeping ONE gives bit-identical output and
    collapses the compile count ~10x. Must run before the first forward (cold cache).
    Reversible by reimport; safe to call once the ops modules are imported (the model
    load pulls both in). Returns [(qualname, before, after)] for what it trimmed."""
    import importlib  # noqa: PLC0415

    trimmed = []
    for modname in ("mmfreelm.ops.fusedbitnet", "mmfreelm.ops.hgrn.recurrent_fuse"):
        try:
            mod = importlib.import_module(modname)
        except Exception as e:  # noqa: BLE001
            print(f"  thin-autotune: skip {modname} ({e})", flush=True)
            continue
        for name, obj in vars(mod).items():
            cfgs = getattr(obj, "configs", None)  # a triton Autotuner exposes .configs
            if isinstance(cfgs, list) and len(cfgs) > keep:
                trimmed.append((f"{modname}.{name}", len(cfgs), keep))
                obj.configs = cfgs[:keep]
    if trimmed:
        for qn, before, after in trimmed:
            print(f"  thinned autotune: {qn}: {before} -> {after} config(s)", flush=True)
    else:
        print("  thin-autotune: no autotuned kernels found to trim "
              "(already thinned, or ops modules not imported yet)", flush=True)
    return trimmed


def _profile_mem_forward(block, args, kwargs, torch):
    """Snapshot RSS around every component of ONE cold forward to find the OOM culprit.

    The full-model OOM on the 4 GB A53 is dominated by triton-cpu's FIRST-call JIT:
    each triton kernel (the norms' LayerNormFn, the BitLinear quant kernel, swiglu, and
    especially the @autotune'd fused_recurrent_hgrn with its 12 configs) compiles through
    LLVM on first use, spiking RSS. The stage markers in main() only bracket load/capture;
    this brackets each sub-op INSIDE the layer forward so we see which one actually grows
    memory. It must be the block's very first forward (no warmup) or the JIT has already run.

    Instruments the projections / norms / conv as module units (skipping each BitLinear's
    inner `.norm`, which is part of the projection) plus the bare-function ops
    (fused_recurrent_hgrn, swiglu) that aren't nn.Modules. Returns nothing; prints a table.

    Each component line is printed LIVE as the forward crosses it (not buffered to the
    end) — the whole point is that this forward may never return on the A53 (triton JIT
    thrashing into swap), and a hung/killed run must still reveal which component it died
    in. The post-line's ΔRSS is the memory that component itself grew (its JIT cost)."""
    import mmfreelm.layers.hgrn_bit as hb  # noqa: PLC0415
    import mmfreelm.models.hgrn_bit.modeling_hgrn_bit as mh  # noqa: PLC0415

    hdr = f"{'component':<42}{'ΔRSS':>10}{'RSS':>10}{'cum':>10}{'MemAvail':>10}{'SwapFree':>10}"
    state = {"base": None, "prev": None, "t": None}

    def snap(label, phase):
        rss, avail, swap = _meminfo()
        if state["base"] is None:
            state["base"], state["prev"], state["t"] = rss, rss, time.perf_counter()
        d = rss - state["prev"]
        tag = label if phase == "post" else f"{label}  (enter)"
        mark = "  <==" if d > 0.05 else ""  # flag any >50 MiB jump
        el = time.perf_counter() - state["t"]
        print(f"{tag:<42}{d:>+9.3f}G{rss:>9.3f}G{rss-state['base']:>+9.3f}G"
              f"{avail:>9.3f}G{swap:>9.3f}G  +{el:5.0f}s{mark}", flush=True)
        state["prev"] = rss

    units = ("FusedBitLinear", "RMSNorm", "FusedRMSNormSwishGate", "ShortConvolution")
    handles = []
    for qn, mod in block.named_modules():
        if mod.__class__.__name__ not in units or qn.endswith(".norm"):
            continue  # skip the inner BitLinear norm; it's counted within its projection
        label = f"{qn} [{mod.__class__.__name__}]"
        handles.append(mod.register_forward_pre_hook(
            lambda m, a, _l=label: snap(_l, "pre")))
        handles.append(mod.register_forward_hook(
            lambda m, a, o, _l=label: snap(_l, "post")))

    # Bare functions (not modules) — wrap by rebinding in the calling module's namespace.
    def _wrap(ns, attr, label):
        orig = getattr(ns, attr)

        def wrapped(*a, **k):
            snap(label, "pre")
            r = orig(*a, **k)
            snap(label, "post")
            return r
        setattr(ns, attr, wrapped)
        return (ns, attr, orig)

    restore = [_wrap(hb, "fused_recurrent_hgrn", "fused_recurrent_hgrn()"),
               _wrap(hb, "swiglu", "swiglu() [attn]"),
               _wrap(mh, "swiglu", "swiglu() [mlp]")]

    print("\n== per-component memory profile of ONE COLD forward "
          "(live; lines stream as each component runs) ==", flush=True)
    print(hdr)
    print("-" * len(hdr))
    snap("<forward start>", "post")
    try:
        with torch.no_grad():
            block(*args, **kwargs)
        snap("<forward end>", "post")
        print("-" * len(hdr))
        print(f"net RSS growth across forward: {state['prev']-state['base']:+.3f} GiB  "
              f"(>50 MiB per-step jumps flagged with <==)", flush=True)
    finally:
        for h in handles:
            h.remove()
        for ns, attr, orig in restore:
            setattr(ns, attr, orig)


def _run_mode(block, args, kwargs, reps, acc, label=""):
    """Median per-call layer time + median projection time, plus the last output.

    Prints each forward as it lands so a slow run is distinguishable from a hung
    one (the CPU glue forward dominates and is the usual suspect)."""
    t = time.perf_counter()
    out = block(*args, **kwargs)                 # warmup
    print(f"  [{label}] warmup forward: {_fmt_t(time.perf_counter()-t)}", flush=True)
    totals, projs = [], []
    for i in range(reps):
        acc[0] = 0.0
        t = time.perf_counter()
        out = block(*args, **kwargs)
        dt = time.perf_counter() - t
        totals.append(dt)
        projs.append(acc[0])
        print(f"  [{label}] rep {i+1}/{reps}: {_fmt_t(dt)} "
              f"(proj {_fmt_t(acc[0])})", flush=True)
    return float(np.median(totals)), float(np.median(projs)), out


def _as_np(out, torch):
    hs = out[0] if isinstance(out, (tuple, list)) else out
    return hs.detach().to(torch.float32).cpu().numpy()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--checkpoint", default="ridger/MMfreeLM-370M")
    ap.add_argument("--blob-dir", default="/tmp/mmfree_layer_blobs")
    ap.add_argument("--so-path", default="libmmfree.so")
    ap.add_argument("--awidth", type=int, default=16)
    ap.add_argument("--xdim", type=int, default=32)
    ap.add_argument("--layer", type=int, default=0, help="which decoder layer to time")
    ap.add_argument("--reps", type=int, default=20, help="timing repetitions per mode")
    ap.add_argument("--prompt", default="In a shocking finding, scientists discovered",
                    help="prompt whose post-embedding state feeds layer L (sets T)")
    ap.add_argument("--profile-mem", action="store_true",
                    help="don't time anything: run ONE cold forward of the layer with RSS "
                         "snapshotted around each component (norms, projections, recurrence, "
                         "swiglu) to pinpoint which sub-op spikes memory. The block runs in "
                         "pure torch (no engine) so this needs neither the FPGA nor root — "
                         "it isolates the CPU-side triton-cpu JIT that OOMs the A53.")
    ap.add_argument("--thin-autotune", action="store_true",
                    help="cut every triton @autotune kernel's config list to ONE entry "
                         "before the first forward, so triton-cpu compiles ~2 kernels per "
                         "layer instead of ~24. Output is bit-identical (autotune only ranks "
                         "already-correct configs); turns the multi-hour cold compile on the "
                         "A53 into minutes. Entries it produces are cached + reused as usual.")
    ap.add_argument("--naive-recurrence", action="store_true",
                    help="run the HGRN recurrence in pure torch instead of the triton-cpu "
                         "fused kernel. Identical math/output; skips the 12-config triton "
                         "JIT (a big slice of first-forward compile time + RAM on the A53). "
                         "The norms/gate still use triton — see the docstring tradeoff.")
    args = ap.parse_args(argv)

    print(f"== Phase E single-layer END-TO-END (CPU-only vs FPGA-proj + CPU-glue) ==\n"
          f"checkpoint={args.checkpoint}  layer={args.layer}  "
          f"geom: aWidth={args.awidth} xDim={args.xdim}  reps={args.reps}")
    _start_mem_monitor()
    _mem("startup")

    print("loading model (fp32)...", flush=True)
    t0 = time.time()
    tok, model, torch = _load_model(args.checkpoint)
    torch.set_grad_enabled(False)
    print(f"model loaded: {time.time()-t0:.1f}s  (torch threads={torch.get_num_threads()})", flush=True)
    _mem("after model load")

    if args.thin_autotune:
        print("thinning triton autotune config lists (cold-compile reduction)...", flush=True)
        _thin_triton_autotune()

    block = _find_block(model, args.layer)
    input_ids = tok(args.prompt, return_tensors="pt").input_ids
    args_l, kwargs_l = _capture_layer_input(model, block, input_ids, torch)
    T = args_l[0].shape[1]
    print(f"prompt tokens={input_ids.shape[1]}  layer-{args.layer} input hidden_states="
          f"{tuple(args_l[0].shape)}", flush=True)

    # --profile-mem isolates the CPU side: no engine, no FPGA, no root. Free the
    # rest of the model and run one cold forward with per-component RSS snapshots.
    if args.profile_mem:
        _free_all_but_block(model, block)
        model = None
        import gc; gc.collect(); _malloc_trim()
        _mem("after pruning model to one layer")
        if args.naive_recurrence:
            _use_naive_recurrence()
            print("recurrence: pure-torch naive (triton-cpu fused kernel bypassed)", flush=True)
        _profile_mem_forward(block, args_l, kwargs_l, torch)
        _mem("after cold forward")
        return 0

    # Single-layer engine (re-indexed to layer 0, UNFUSED — the bridge maps one
    # FusedBitLinear module to one projection).
    geom = Geometry.derive(args.awidth, args.xdim)
    wl = _single_layer_weights(_weights_dict(model, torch), args.layer)
    libcfg = LibConfig(aWidth=args.awidth, xDim=args.xdim)
    print("packing blobs + opening FPGA engine...", flush=True)
    t0 = time.time()
    hw_eng, lib = open_real_engine(wl, geom, libcfg, args.blob_dir,
                                   so_path=args.so_path, fuse_ifg=False)
    print(f"FPGA engine opened: {time.time()-t0:.1f}s ({len(hw_eng.proj)} projections)", flush=True)
    _mem("after engine open")

    # Weights are now baked into the engine blobs and layer L's input is captured;
    # free the rest of the fp32 model so the timed forwards run in-core, not in swap.
    del wl
    _free_all_but_block(model, block)
    model = None                      # drop the top-level handle so gc can reap it
    import gc; gc.collect(); _malloc_trim()
    _mem("after pruning model to one layer")

    if args.naive_recurrence:
        _use_naive_recurrence()
        print("recurrence: pure-torch naive (triton-cpu fused kernel bypassed)", flush=True)

    acc = [0.0]

    # --- CPU-only: native forward, projections instrumented for the breakdown ---
    nat_targets = _instrument_native(block, acc, torch)
    t_cpu, proj_cpu, out_cpu = _run_mode(block, args_l, kwargs_l, args.reps, acc, "CPU-only")
    _unpatch(nat_targets)

    # --- FPGA projections + CPU glue ---
    fpga_targets = _patch_block(block, hw_eng, DEFAULT_EPS, acc)
    t_fpga, proj_fpga, out_fpga = _run_mode(block, args_l, kwargs_l, args.reps, acc, "FPGA-proj")
    _unpatch(fpga_targets)

    # ---- report ----
    glue_cpu, glue_fpga = t_cpu - proj_cpu, t_fpga - proj_fpga
    w = 62
    print("\n" + "-" * w)
    print(f"{'mode':<16}{'layer total':>14}{'projections':>14}{'glue (CPU)':>14}")
    print("-" * w)
    print(f"{'CPU-only':<16}{_fmt_t(t_cpu):>14}{_fmt_t(proj_cpu):>14}{_fmt_t(glue_cpu):>14}")
    print(f"{'FPGA proj':<16}{_fmt_t(t_fpga):>14}{_fmt_t(proj_fpga):>14}{_fmt_t(glue_fpga):>14}")
    print("-" * w)
    print(f"layer speedup (CPU-only / FPGA-proj): {t_cpu/t_fpga:.2f}x")
    print(f"projection speedup (native / FPGA):   {proj_cpu/proj_fpga:.2f}x")
    print(f"glue is {glue_fpga/t_fpga*100:.0f}% of the FPGA-mode layer time "
          f"(unaccelerated, runs on the A53)")

    a, b = _as_np(out_cpu, torch).reshape(-1), _as_np(out_fpga, torch).reshape(-1)
    abs_err = np.abs(a - b)
    denom = np.linalg.norm(a) * np.linalg.norm(b)
    cos = float(a @ b / denom) if denom else float("nan")
    rel = float(np.linalg.norm(a - b) / (np.linalg.norm(a) + 1e-9))
    print(f"\nlayer-output correctness (FPGA int16 vs CPU-native int8, T={T} tokens):")
    print(f"  max|Δ|={abs_err.max():.4e}  mean|Δ|={abs_err.mean():.4e}  "
          f"rel-L2={rel:.4e}  cosine={cos:.6f}")

    if lib is not None:
        lib.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
