"""Monkeypatch FusedBitLinear.forward to run on the engine (Phase D/E).

prepare_for_inference(model, engine) tags every FusedBitLinear with its packed
projection name and replaces forward with one that does RMSNorm in torch, runs
the int16 engine, and returns the result as a tensor.

Faithful-to-the-model notes:
  * Each FusedBitLinear owns its RMSNorm; the model feeds self.norm.weight/bias
    into the fused kernel with is_rms_norm=True and the *function default*
    eps=1e-6 (NOT the module's construction eps). We replicate that here.
  * The model's native path int8-fake-quantizes x_norm inside the norm kernel;
    we instead feed the real-valued x_norm to the int16 engine (higher precision,
    the whole point of the a16 build).

Lazy torch / mmfreelm imports: this module only loads on the board. Validated
end-to-end in Phase E, not on the torch-less dev host.
"""

from __future__ import annotations

import types

import numpy as np

DEFAULT_EPS = 1e-6  # matches layer_norm_linear_quant_fn's default, see note above


def _strip_model_prefix(qualified_name: str) -> str:
    return qualified_name[len("model."):] if qualified_name.startswith("model.") else qualified_name


def _make_forward(engine, eps: float):
    import torch  # noqa: PLC0415

    def forward(self, x):
        # RMSNorm (no mean-subtraction), then affine — manual to match the
        # model's eps quirk exactly and to avoid int8 fake-quant.
        xf = x.to(torch.float32)
        var = xf.pow(2).mean(dim=-1, keepdim=True)
        x_hat = xf * torch.rsqrt(var + eps)
        x_norm = x_hat * self.norm.weight.to(torch.float32)
        if getattr(self.norm, "bias", None) is not None:
            x_norm = x_norm + self.norm.bias.to(torch.float32)

        og = x_norm.shape                       # (..., N)
        flat = x_norm.reshape(-1, og[-1]).detach().cpu().numpy()
        y = engine.project(self._mmfree_name, flat)            # (T, M) float32
        y_t = torch.from_numpy(np.ascontiguousarray(y)).to(device=x.device, dtype=x.dtype)
        return y_t.reshape(*og[:-1], y.shape[-1])

    return forward


def prepare_for_inference(model, engine, eps: float = DEFAULT_EPS):
    """Patch every FusedBitLinear in `model` to run on `engine`. Returns model."""
    from mmfreelm.ops.fusedbitnet import FusedBitLinear  # noqa: PLC0415

    fwd = _make_forward(engine, eps)
    patched = 0
    for qn, mod in model.named_modules():
        if isinstance(mod, FusedBitLinear):
            name = _strip_model_prefix(qn)
            if name not in engine.proj:
                raise KeyError(f"no packed projection for module '{qn}' (looked up '{name}')")
            mod._mmfree_name = name
            mod.forward = types.MethodType(fwd, mod)
            patched += 1
    if patched == 0:
        raise RuntimeError("prepare_for_inference: no FusedBitLinear modules found")
    return model
