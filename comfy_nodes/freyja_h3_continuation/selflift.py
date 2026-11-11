"""SelfLift-zero consistency lift at the clean BASE -> refinement boundary.

Implements the paired-lift residual correction (SelfLift, arXiv:2609.02036,
equations 4-10). The ordinary refinement sampler owns re-noising; the archived
first-pass AV latent is immutable. No external SelfLift plugin is required.
"""
import torch
import torch.nn.functional as F
from comfy.nested_tensor import NestedTensor
from .upscale import av


def consistency_lift(direct, anchor, rho=.25, low=.0, high=.5):
    if direct.shape != anchor.shape or not 0 < rho <= 1 or not 0 <= low <= high <= 1:
        raise ValueError("FREYJA_SELFLIFT_LAYOUT_OR_WEIGHTS_INVALID")
    residual = anchor.float() - direct.float()
    risk = residual.abs().mean(dim=1, keepdim=True)
    threshold = torch.quantile(risk.flatten(1), 1 - rho, dim=1)
    threshold = threshold.reshape((-1,) + (1,) * (risk.ndim - 1))
    selected = (risk >= threshold) & (risk > 0)
    maximum = risk.flatten(1).amax(1).reshape(threshold.shape)
    weight = low + (high - low) * (risk - threshold) / (maximum - threshold).clamp_min(1e-8)
    # Constant nonzero residual still needs correction; a zero residual is identity.
    weight = torch.where(maximum == threshold, torch.full_like(weight, high), weight)
    return (direct.float() + torch.where(selected, weight, 0) * residual).to(direct)


class FreyjaH3SelfLift:
    RETURN_TYPES = ("LATENT",)
    FUNCTION = "apply"
    CATEGORY = "FREYJA/H3"

    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"source": ("LATENT",), "lifted": ("LATENT",), "vae": ("VAE",)}}

    def apply(self, source, lifted, vae):
        original, audio = av(source)
        direct, _ = av(lifted)
        if original.shape[:3] != direct.shape[:3]:
            raise ValueError("FREYJA_SELFLIFT_TEMPORAL_LAYOUT_INVALID")
        frames = vae.decode(original)
        if frames.ndim != 4 or frames.shape[-1] != 3:
            raise ValueError("FREYJA_SELFLIFT_PIXEL_LAYOUT_INVALID")
        height, width = direct.shape[-2] * 16, direct.shape[-1] * 16
        # Allocate once on CPU; resize bounded batches without stretching time.
        pixels = torch.empty((len(frames), height, width, 3), dtype=frames.dtype, device="cpu")
        for start in range(0, len(frames), 8):
            batch = frames[start:start+8].movedim(-1, 1).float()
            pixels[start:start+8] = F.interpolate(batch, size=(height, width), mode="bicubic", align_corners=False, antialias=True).clamp(0, 1).movedim(1, -1).to(pixels)
        del frames
        # H3 VAE owns the complete 17n+5 temporal phase, never encode independent frame chunks.
        anchor = vae.encode(pixels).to(direct)
        corrected = consistency_lift(direct, anchor)
        result = dict(lifted)
        result["samples"] = NestedTensor((corrected, audio.to(corrected.device)))
        return (result,)
