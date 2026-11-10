"""Native H3 AV masks and managed safetensors handoff.

Algorithms informed by AIMixer Director, ethanfel Context Loop and roycho87 Geiru.
The optional late-tail model-call adapter follows Geiru's GPL-3.0 strategy.
This node package is distributed under GPL-3.0-or-later; see LICENSE and NOTICE.md.
"""
import hashlib
import json
import math
import os
from pathlib import Path
import uuid

import torch
from safetensors import safe_open
from safetensors.torch import save_file
import folder_paths
from comfy.nested_tensor import NestedTensor
from .policy import VERSION, pixel_frames, tail_start, noise_weights, schedule_ratio


def streams(latent):
    value = latent["samples"]
    if not getattr(value, "is_nested", False):
        raise ValueError("FREYJA_H3_AV_REQUIRED: expected native joint video/audio latent")
    parts = list(value.unbind())
    if len(parts) != 2 or parts[0].ndim != 5 or parts[1].ndim != 4 or parts[0].shape[0] != 1:
        raise ValueError("FREYJA_H3_AV_LAYOUT_MISMATCH")
    return parts


def pack(latent, video, audio):
    out = dict(latent)
    out["samples"] = NestedTensor((video, audio))
    return out


def input_path(filename):
    root = Path(folder_paths.get_input_directory()).resolve()
    path = (root / filename).resolve()
    if not path.is_relative_to(root) or path.suffix != ".safetensors" or not path.is_file():
        raise ValueError("FREYJA_H3_ARTIFACT_PATH_INVALID")
    return path


def read_artifact(filename, compatibility, stage="FIRST_PASS"):
    with safe_open(str(input_path(filename)), framework="pt", device="cpu") as data:
        meta = data.metadata() or {}
        if meta.get("format") != VERSION or meta.get("compatibility") != compatibility:
            raise ValueError("FREYJA_H3_ARTIFACT_INCOMPATIBLE: model/VAE/LoRA/scheduler/canvas changed")
        if meta.get("stage") != stage:
            raise ValueError("FREYJA_H3_ARTIFACT_STAGE_MISMATCH")
        if set(data.keys()) != {"video", "audio"}:
            raise ValueError("FREYJA_H3_ARTIFACT_LAYOUT_INVALID")
        video, audio = data.get_tensor("video"), data.get_tensor("audio")
    if video.ndim != 5 or audio.ndim != 4 or video.shape[0] != 1 or audio.shape[0] != 1:
        raise ValueError("FREYJA_H3_ARTIFACT_LAYOUT_INVALID")
    return video, audio


class FreyjaH3ChromaNoise:
    RETURN_TYPES = ("IMAGE",)
    FUNCTION = "apply"
    CATEGORY = "FREYJA/H3"
    DESCRIPTION = "FREYJA chroma-only Gaussian noise v1, tapered near the seam. Never modifies audio or control video."
    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"images": ("IMAGE",), "strength": ("FLOAT", {"default": .45, "min": 0., "max": 1.}),
                             "seam_strength": ("FLOAT", {"default": .10, "min": 0., "max": 1.}),
                             "taper_frames": ("INT", {"default": 3, "min": 1}), "seed": ("INT", {"default": 1, "min": 0})}}
    def apply(self, images, strength, seam_strength, taper_frames, seed):
        if not 0 <= seam_strength <= strength <= 1 or not 1 <= taper_frames <= len(images):
            raise ValueError("FREYJA_H3_CHROMA_RANGE_INVALID")
        if images.shape[-1] != 3:
            raise ValueError("FREYJA_H3_RGB_REQUIRED")
        # Recipe CHROMA_TAPER_V1: independent Gaussian Cb/Cr deviations, std=.15*strength,
        # inverse BT.601 difference coefficients in RGB [0,1]. Luma is unchanged before clipping.
        generator = torch.Generator(device="cpu").manual_seed(int(seed))
        noise = torch.randn((*images.shape[:-1], 2), generator=generator, dtype=torch.float32).to(images.device)
        weights = torch.full((len(images),), float(strength), device=images.device)
        weights[-taper_frames:] = torch.linspace(strength, seam_strength, taper_frames+1, device=images.device)[1:]
        noise *= .15 * weights.view(-1, 1, 1, 1)
        cb, cr = noise[..., 0], noise[..., 1]
        rgb = torch.stack((1.402*cr, -.344136*cb-.714136*cr, 1.772*cb), dim=-1)
        return ((images.float()+rgb).clamp(0, 1).to(images.dtype),)


class PrefixMask:
    def __init__(self, shape, count, mode, strength, sigmas, anchor):
        self.shape, self.count, self.mode, self.strength = tuple(shape), count, mode, strength
        self.sigmas = [float(x) for x in sigmas.detach().float().cpu().tolist()]
        self.anchor = anchor
        self.live = noise_weights(count, mode, strength)

    def sampler(self, sigma, mask, extra_options=None):
        if not torch.is_tensor(mask) or mask.ndim != 3:
            raise ValueError("FREYJA_H3_PACKED_MASK_REQUIRED")
        value = float(torch.as_tensor(sigma).flatten()[0])
        self.live = noise_weights(self.count, self.mode, self.strength, schedule_ratio(value, self.sigmas))
        if self.anchor:
            self.live[0] = 0.0
        size = math.prod(self.shape[1:])
        if mask.shape[-1] < size:
            raise ValueError("FREYJA_H3_PACKED_MASK_SHAPE_INVALID")
        out = mask.clone()
        video = out[..., :size].reshape(self.shape)
        w = torch.tensor(self.live, device=video.device, dtype=video.dtype)
        video[:, :, :self.count] = torch.ceil(w*256).div(256).view(1, 1, -1, 1, 1)
        return out

    def model(self, executor, *args, **kwargs):
        mask = kwargs.get("denoise_mask")
        if mask is None or mask.ndim != 5 or mask.shape[2] < self.count:
            raise ValueError("FREYJA_H3_MODEL_MASK_HOOK_INCOMPATIBLE")
        mask = mask.clone()
        w = torch.tensor(self.live, device=mask.device, dtype=mask.dtype)
        mask[:, :, :self.count] = torch.ceil(w*256).div(256).view(1, 1, -1, 1, 1)
        kwargs["denoise_mask"] = mask
        return executor(*args, **kwargs)


def install_tail_anchor(model, anchor, sigmas, start_fraction):
    """Add one clean source token only inside late model calls, never to delivered frames.

    Adapted from the Geiru late capstone strategy (GPL-3.0); previous model wrappers compose.
    """
    import comfy.utils
    previous = model.model_options.get("model_function_wrapper")
    levels = sigmas.detach().float().cpu()
    start = min(len(levels)-2, math.ceil((len(levels)-1)*start_fraction))
    def wrapped(apply_model, arguments):
        x, timestep, cond = arguments["input"], arguments["timestep"], arguments["c"]
        def invoke(state, settings):
            if previous is None:
                return apply_model(state, timestep, **settings)
            return previous(apply_model, {**arguments, "input": state, "c": settings})
        # model_function_wrapper receives sampler sigma (not H3's millisecond timestep).
        step = int((levels-float(timestep.flatten()[0])).abs().argmin())
        shapes = cond.get("latent_shapes")
        if step < start:
            return invoke(x, cond)
        if not shapes or len(shapes) != 2:
            raise ValueError("FREYJA_H3_TAIL_ANCHOR_LAYOUT_INVALID")
        parts = list(comfy.utils.unpack_latents(x, shapes))
        video = parts[0]
        extra = anchor.to(device=video.device, dtype=video.dtype)
        extended, layout = comfy.utils.pack_latents([torch.cat((video, extra), dim=2), parts[1]])
        settings = dict(cond)
        settings["latent_shapes"] = layout
        mask = settings.get("denoise_mask")
        if mask is None:
            raise ValueError("FREYJA_H3_TAIL_ANCHOR_MASK_REQUIRED")
        settings["denoise_mask"] = torch.cat((mask, torch.zeros_like(mask[:, :, :1])), dim=2)
        out = comfy.utils.unpack_latents(invoke(extended, settings), layout)
        return comfy.utils.pack_latents([out[0][:, :, :video.shape[2]], out[1]])[0]
    model.set_model_unet_function_wrapper(wrapped)


class FreyjaH3Continue:
    RETURN_TYPES = ("LATENT", "MODEL", "FREYJA_H3_RESTORE")
    RETURN_NAMES = ("latent", "model", "restore")
    FUNCTION = "apply"
    CATEGORY = "FREYJA/H3"
    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"latent": ("LATENT",), "model": ("MODEL",), "sigmas": ("SIGMAS",),
          "filename": ("STRING",), "compatibility": ("STRING",), "frames": ("INT", {"default":22,"min":5}),
          "mode": (["LATENT_REDRAW", "LATENT_PROTECTED"],),
          "strength": ("FLOAT", {"default":.1,"min":0.,"max":1.}),
          "first_anchor": ("BOOLEAN", {"default":True}), "tail_anchor": ("BOOLEAN", {"default":False}),
          "tail_start_fraction": ("FLOAT", {"default":.75,"min":0.,"max":1.})},
          "optional": {"anchor_filename": ("STRING", {"default":""})}}
    def apply(self, latent, model, sigmas, filename, compatibility, frames, mode, strength,
              first_anchor=True, tail_anchor=False, tail_start_fraction=.75, anchor_filename=""):
        import comfy.patcher_extension
        video, audio = streams(latent)
        source_v, source_a = read_artifact(filename, compatibility)
        if source_v.shape[:2] != video.shape[:2] or source_v.shape[3:] != video.shape[3:] or source_a.shape[:3] != audio.shape[:3]:
            raise ValueError("FREYJA_H3_ARTIFACT_CANVAS_INVALID")
        start, count = tail_start(source_v.shape[2], frames)
        if count >= video.shape[2]:
            raise ValueError("FREYJA_H3_CONTEXT_TOO_LONG")
        clean = source_v[:, :, start:start+count].to(video)
        out_v, out_a = video.clone(), audio.clone()
        out_v[:, :, :count] = clean
        audio_count = round(frames*40/24)
        if audio_count >= audio.shape[-1] or source_a.shape[-1] < audio_count:
            raise ValueError("FREYJA_H3_AUDIO_CONTEXT_INVALID")
        clean_audio = source_a[..., -audio_count:].to(audio)
        out_a[..., :audio_count] = clean_audio
        anchored = mode == "LATENT_PROTECTED" and first_anchor
        source_anchor = None
        if mode == "LATENT_PROTECTED" and (first_anchor or tail_anchor):
            if not anchor_filename:
                raise ValueError("FREYJA_H3_ANCHOR_REQUIRED")
            source_anchor, _ = read_artifact(anchor_filename, compatibility)
            if source_anchor.shape[1:2] != video.shape[1:2] or source_anchor.shape[3:] != video.shape[3:]:
                raise ValueError("FREYJA_H3_ANCHOR_CANVAS_INVALID")
            if first_anchor:
                out_v[:, :, :1] = source_anchor[:, :, :1].to(video)
        state = PrefixMask(video.shape, count, mode, strength, sigmas, anchored)
        vm = torch.ones((1, 1, video.shape[2], video.shape[3], video.shape[4]), dtype=torch.float32, device=video.device)
        weights = noise_weights(count, mode, strength)
        if anchored: weights[0] = 0.
        vm[:, :, :count] = torch.tensor(weights, device=video.device).view(1, 1, -1, 1, 1)
        am = torch.ones((1, 1, 1, audio.shape[-1]), dtype=torch.float32, device=audio.device)
        am[..., :audio_count] = 0.
        # A short cosine release keeps the independent audio handoff from a hard mask edge.
        release = min(8, audio_count)
        am[..., audio_count-release:audio_count] = .5-.5*torch.cos(torch.arange(1,release+1,device=audio.device)*math.pi/release)
        out = pack(latent, out_v, out_a)
        out["noise_mask"] = NestedTensor((vm, am))
        cloned = model.clone()
        if cloned.model_options.get("denoise_mask_function") is not None:
            raise ValueError("FREYJA_H3_MASK_CONFLICT: another adapter owns the mask hook")
        cloned.set_model_denoise_mask_function(state.sampler)
        cloned.add_wrapper_with_key(comfy.patcher_extension.WrappersMP.APPLY_MODEL,"freyja_h3_prefix_v1",state.model)
        if mode == "LATENT_PROTECTED" and tail_anchor:
            install_tail_anchor(cloned, source_anchor[:, :, -1:], sigmas, tail_start_fraction)
        # Only protection restores the inherited region; redraw deliberately retains its redraw.
        restore = {"video":clean if mode=="LATENT_PROTECTED" else None,"audio":None}
        return out, cloned, restore


class FreyjaH3Restore:
    RETURN_TYPES = ("LATENT",)
    FUNCTION = "apply"
    CATEGORY = "FREYJA/H3"
    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"latent": ("LATENT",), "restore": ("FREYJA_H3_RESTORE",)}}
    def apply(self, latent, restore):
        video, audio = streams(latent)
        clean = restore.get("video")
        if clean is not None:
            video = video.clone()
            video[:, :, :clean.shape[2]] = clean.to(video)
        out = pack(latent, video, audio)
        out.pop("noise_mask", None)
        return (out,)


class FreyjaH3SaveLatent:
    OUTPUT_NODE = True
    RETURN_TYPES = ()
    FUNCTION = "save"
    CATEGORY = "FREYJA/H3"
    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"latent": ("LATENT",), "compatibility": ("STRING",), "execution_id": ("STRING",)},
                "optional": {"stage": (["FIRST_PASS", "SECOND_PASS"],)}}
    def save(self, latent, compatibility, execution_id, stage="FIRST_PASS"):
        if stage not in {"FIRST_PASS", "SECOND_PASS"}:
            raise ValueError("FREYJA_H3_ARTIFACT_STAGE_INVALID")
        video, audio = streams(latent)
        # Atomic content is opaque to gateway/Java. Only safe tensor format is accepted on reload.
        root = Path(folder_paths.get_output_directory()) / "freyja_h3"
        root.mkdir(parents=True, exist_ok=True)
        name = hashlib.sha256(execution_id.encode()).hexdigest()+".safetensors"
        temp = root / (uuid.uuid4().hex+".tmp")
        try:
            save_file({"video":video.detach().cpu().contiguous(),"audio":audio.detach().cpu().contiguous()}, str(temp),
                      metadata={"format":VERSION,"stage":stage,"compatibility":compatibility,"frames":str(pixel_frames(video.shape[2]))})
            os.replace(temp, root/name)
        finally:
            temp.unlink(missing_ok=True)
        return {"ui":{"latents":[{"filename":name,"subfolder":"freyja_h3","type":"output"}]}}


class FreyjaH3CheckFrames:
    """Reject truncated stage inputs before sampling; never silently stretch time."""
    RETURN_TYPES = ("IMAGE",)
    FUNCTION = "apply"
    CATEGORY = "FREYJA/H3"
    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"images": ("IMAGE",), "expected_frames": ("INT", {"min": 5, "max": 362})},
                "optional": {"expected_width": ("INT", {"default": 0, "min": 0}), "expected_height": ("INT", {"default": 0, "min": 0})}}
    def apply(self, images, expected_frames, expected_width=0, expected_height=0):
        if len(images) != expected_frames:
            raise ValueError(f"DYNAMIC_SOURCE_FRAME_MISMATCH: expected {expected_frames}, got {len(images)}")
        if expected_width and images.shape[2] != expected_width or expected_height and images.shape[1] != expected_height:
            raise ValueError("DYNAMIC_OUTPUT_SIZE_MISMATCH")
        return (images,)


NODE_CLASS_MAPPINGS = {c.__name__:c for c in (FreyjaH3ChromaNoise,FreyjaH3Continue,FreyjaH3Restore,FreyjaH3SaveLatent,FreyjaH3CheckFrames)}
from .upscale import CLASSES as UPSCALE_CLASSES
NODE_CLASS_MAPPINGS.update({c.__name__: c for c in UPSCALE_CLASSES})
NODE_DISPLAY_NAME_MAPPINGS = {name:name.replace("FreyjaH3", "FREYJA H3 · ") for name in NODE_CLASS_MAPPINGS}
