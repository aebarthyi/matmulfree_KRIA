"""Map an MMfreeLM-370M checkpoint to the engine's projection list.

Per layer (state_dict keys under `model.layers.{L}.`):
  - i/f/g_proj  -> FUSED into one (3*input_dim, hidden) projection `layers.{L}.ifg`
                   (they share `hidden_states`, so one LOAD + one COMPUTE_MM
                   replaces three ops — the biggest per-token perf lever)
  - o_proj      -> separate (different input: gated recurrence output)
  - gate_proj   -> `layers.{L}.gate_up` (model already fuses gate+up -> 2*inter)
  - down_proj   -> `layers.{L}.down_proj` (the only non-pow2 N)
Plus the tied/untied `lm_head`.

Weights arrive as a name -> np.ndarray dict so this is torch-free and testable
with synthetic tensors; `load_checkpoint` is the only torch entry point.
"""

from __future__ import annotations

import re
from typing import Callable, Dict, Iterator, List, Tuple

import numpy as np

# Per-layer projections to emit, in COMPUTE order. Each is (out_name, [src keys]).
# FUSED: i/f/g are concatenated along the output dim (axis 0) into one matmul.
# NOTE: fusion is only numerically exact if the three RMSNorm weights are folded
# into the projection weights at pack time (each *_proj has its OWN norm); the
# bridge uses the UNFUSED form for correctness and treats fusion as a perf step.
_LAYER_PROJS_FUSED: List[Tuple[str, List[str]]] = [
    ("ifg",       ["attn.i_proj.weight", "attn.f_proj.weight", "attn.g_proj.weight"]),
    ("o_proj",    ["attn.o_proj.weight"]),
    ("gate_up",   ["mlp.gate_proj.weight"]),
    ("down_proj", ["mlp.down_proj.weight"]),
]
# UNFUSED: one projection per BitLinear; names are the module-path suffix so the
# torch patch can map each FusedBitLinear instance to its projection 1:1.
_LAYER_PROJS_UNFUSED: List[Tuple[str, List[str]]] = [
    ("attn.i_proj",   ["attn.i_proj.weight"]),
    ("attn.f_proj",   ["attn.f_proj.weight"]),
    ("attn.g_proj",   ["attn.g_proj.weight"]),
    ("attn.o_proj",   ["attn.o_proj.weight"]),
    ("mlp.gate_proj", ["mlp.gate_proj.weight"]),
    ("mlp.down_proj", ["mlp.down_proj.weight"]),
]

_LAYER_RE = re.compile(r"(?:model\.)?layers\.(\d+)\.")


def _num_layers(weights: Dict[str, np.ndarray]) -> int:
    n = -1
    for k in weights:
        m = _LAYER_RE.match(k)
        if m:
            n = max(n, int(m.group(1)))
    return n + 1


def _get(weights: Dict[str, np.ndarray], layer: int, suffix: str) -> np.ndarray:
    """Fetch a layer weight, tolerating an optional leading `model.` prefix."""
    for key in (f"model.layers.{layer}.{suffix}", f"layers.{layer}.{suffix}"):
        if key in weights:
            return weights[key]
    raise KeyError(f"missing weight for layer {layer}: {suffix}")


def iter_projections(
    weights: Dict[str, np.ndarray],
    fuse_ifg: bool = True,
) -> Iterator[Tuple[str, np.ndarray]]:
    """Yield (name, W) for every projection, in pack/compute order.

    W is the nn.Linear weight (out_features, in_features); fused groups are
    concatenated along axis 0 (output dim). With fuse_ifg=False, every BitLinear
    is its own projection named by module-path suffix (e.g. layers.0.attn.i_proj)
    so the torch bridge can map instances 1:1.
    """
    table = _LAYER_PROJS_FUSED if fuse_ifg else _LAYER_PROJS_UNFUSED
    for L in range(_num_layers(weights)):
        for out_name, suffixes in table:
            mats = [np.asarray(_get(weights, L, s)) for s in suffixes]
            W = mats[0] if len(mats) == 1 else np.concatenate(mats, axis=0)
            yield f"layers.{L}.{out_name}", W

    for key in ("lm_head.weight", "model.lm_head.weight"):
        if key in weights:
            yield "lm_head", np.asarray(weights[key])
            break
    else:
        # weight-tied vocab head: reuse the input embedding
        for key in ("model.embeddings.weight", "embeddings.weight"):
            if key in weights:
                yield "lm_head", np.asarray(weights[key])
                break


def load_checkpoint(path: str) -> Dict[str, np.ndarray]:
    """Load a HF checkpoint dir / .safetensors / .bin into a name->np.ndarray dict.

    Lazy torch import: only this function needs torch installed.
    """
    import torch  # noqa: PLC0415  (intentional lazy import)

    p = str(path)
    if p.endswith(".safetensors"):
        from safetensors.torch import load_file  # noqa: PLC0415
        sd = load_file(p)
    elif p.endswith((".bin", ".pt", ".pth")):
        sd = torch.load(p, map_location="cpu", weights_only=True)
    else:
        from transformers import AutoModelForCausalLM  # noqa: PLC0415
        model = AutoModelForCausalLM.from_pretrained(p, torch_dtype=torch.float32)
        sd = model.state_dict()

    out: Dict[str, np.ndarray] = {}
    for k, v in sd.items():
        out[k] = v.detach().to(torch.float32).cpu().numpy()
    return out
