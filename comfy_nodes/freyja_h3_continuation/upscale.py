"""FREYJA's independent H3 enhancement stage. GPL-3.0.

Spatial prediction tiling adapts the PackedLayout strategy from Songssx's
TimelineDirector (050226d), selflift_runtime/h3_tiling.py, GPL-3.0.
This is not its progressive sampler: the completed BASE remains immutable.
"""
import math
import inspect
from functools import partial

import torch
from comfy.nested_tensor import NestedTensor


def av(latent):
    samples = latent.get("samples")
    if not getattr(samples, "is_nested", False):
        raise ValueError("FREYJA_UPSCALE_AV_REQUIRED")
    parts = list(samples.unbind())
    if len(parts) != 2 or parts[0].ndim != 5 or parts[1].ndim != 4 or parts[0].shape[:2] != (1, 24) or parts[1].shape[:3] != (1, 32, 2):
        raise ValueError("FREYJA_UPSCALE_AV_LAYOUT_INVALID")
    return parts


class FreyjaH3UpscaleEncode:
    RETURN_TYPES = ("LATENT",)
    FUNCTION = "apply"
    CATEGORY = "FREYJA/H3"
    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"images": ("IMAGE",), "vae": ("VAE",), "av_template": ("LATENT",)}}
    def apply(self, images, vae, av_template):
        template, audio = av(av_template)
        if images.ndim != 4 or images.shape[-1] != 3 or len(images) < 22 or (len(images) - 5) % 17:
            raise ValueError("FREYJA_UPSCALE_SOURCE_GRID_INVALID")
        if images.shape[1] % 32 or images.shape[2] % 32:
            raise ValueError("FREYJA_UPSCALE_SOURCE_SIZE_INVALID")
        # H3's own VAE owns temporal chunking/normalization. Never encode arbitrary
        # frame batches independently: that changes the 17n+5 temporal phase.
        encoded = vae.encode(images[..., :3])
        if encoded.ndim != 5 or encoded.shape[:3] != template.shape[:3]:
            raise ValueError("FREYJA_UPSCALE_ENCODE_LAYOUT_INVALID")
        return ({"samples": NestedTensor((encoded.cpu(), audio.cpu()))},)


class FreyjaH3LoadLatent:
    RETURN_TYPES = ("LATENT",)
    FUNCTION = "apply"
    CATEGORY = "FREYJA/H3"
    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"filename": ("STRING",), "compatibility": ("STRING",),
                "width": ("INT", {"min": 32, "step": 32}), "height": ("INT", {"min": 32, "step": 32}),
                "frames": ("INT", {"min": 22})}}
    def apply(self, filename, compatibility, width, height, frames):
        from .nodes import read_artifact
        from .policy import pixel_frames
        video, audio = read_artifact(filename, compatibility)
        result = {"samples": NestedTensor((video, audio))}
        av(result)
        if width % 32 or height % 32 or video.shape[-2:] != (height // 16, width // 16) or pixel_frames(video.shape[2]) != frames:
            raise ValueError("FREYJA_UPSCALE_SOURCE_LAYOUT_MISMATCH")
        return (result,)


class FreyjaH3FinishSize:
    RETURN_TYPES = ("IMAGE",)
    FUNCTION = "apply"
    CATEGORY = "FREYJA/H3"
    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"images": ("IMAGE",), "width": ("INT", {"min": 2}), "height": ("INT", {"min": 2})}}
    def apply(self, images, width, height):
        h, w = images.shape[1:3]
        if width % 2 or height % 2 or not (0 <= w - width <= 31 and 0 <= h - height <= 31):
            raise ValueError("FREYJA_UPSCALE_CROP_INVALID")
        top, left = (h - height) // 2, (w - width) // 2
        return (images[:, top:top + height, left:left + width].contiguous(),)


def regions(length, size, overlap):
    if length % 2 or size < 2 or size % 2 or overlap % 2 or overlap < 0 or overlap >= size:
        raise ValueError("FREYJA_UPSCALE_TILE_GRID_INVALID")
    if length <= size:
        return [(0, length)]
    starts = list(range(0, length - size + 1, size - overlap))
    if starts[-1] != length - size:
        starts.append(length - size)
    return [(start, start + size) for start in starts]


def feather(spans, index):
    start, end = spans[index]
    weights = torch.ones(end - start, dtype=torch.float32)
    if index:
        shared = spans[index - 1][1] - start
        if shared > 0:
            weights[:shared] *= (torch.arange(shared) + .5) / shared
    if index + 1 < len(spans):
        shared = end - spans[index + 1][0]
        if shared > 0:
            weights[-shared:] *= 1 - (torch.arange(shared) + .5) / shared
    return weights


def tiled_prediction(video, audio, predict, tile_height, tile_width, overlap):
    """Averaged model predictions per step, not independently sampled finished tiles."""
    ys, xs = regions(video.shape[-2], tile_height, overlap), regions(video.shape[-1], tile_width, overlap)
    if len(ys) * len(xs) == 1:
        return predict(video, audio, (0, video.shape[-2]), (0, video.shape[-1]))
    out = torch.zeros(video.shape, dtype=torch.float32, device="cpu")
    weights = torch.zeros(video.shape[-2:], dtype=torch.float32, device="cpu")
    first_audio = None
    for yi, (y0, y1) in enumerate(ys):
        for xi, (x0, x1) in enumerate(xs):
            block = video[..., y0:y1, x0:x1].contiguous()
            predicted, sound = predict(block, audio, (y0, y1), (x0, x1))
            weight = feather(ys, yi)[:, None] * feather(xs, xi)[None, :]
            out[..., y0:y1, x0:x1].add_(predicted.float().cpu() * weight)
            weights[y0:y1, x0:x1].add_(weight)
            if first_audio is None:
                first_audio = sound.detach().cpu().clone()
            del block, predicted, sound
    if torch.any(weights <= 0):
        raise RuntimeError("FREYJA_UPSCALE_TILE_COVERAGE_INVALID")
    out.div_(weights)
    return [out.to(video), first_audio.to(audio)]


def tile_payload(payload, context, video, audio, ys, xs):
    from comfy.ldm.minimax.model import PackedLayout
    from comfy.ldm.common_dit import pad_to_patch_size
    h, w = video.shape[-2:]
    def layout(height, width, data):
        kwargs = {"keyframes": data.get("keyframes"), "refs": data.get("refs")}
        if "frame_count" in inspect.signature(PackedLayout).parameters:
            kwargs["frame_count"] = data.get("frame_count")
        return PackedLayout(context.shape[1], video.shape[2], height, width, audio.shape[-1], **kwargs)
    original = layout(h, w, payload)
    cropped = dict(payload)
    if payload.get("keyframes"):
        keyframes = []
        for frame in payload["keyframes"]:
            latent = frame.get("latent")
            if latent is None or latent.shape[-2:] != (h, w):
                raise ValueError("FREYJA_UPSCALE_KEYFRAME_GRID_MISMATCH")
            keyframes.append({**frame, "latent": pad_to_patch_size(latent[..., ys[0]:ys[1], xs[0]:xs[1]], (1, 2, 2)).contiguous()})
        cropped["keyframes"] = keyframes
        if not payload.get("refs"):
            cropped["cond_video_latents"] = [item["latent"] for item in keyframes]
    target = layout(ys[1] - ys[0], xs[1] - xs[0], cropped)
    if len(original.segments) != len(target.segments):
        raise ValueError("FREYJA_UPSCALE_PACKED_LAYOUT_INCOMPATIBLE")
    for (a, b, kind), (c, d, target_kind) in zip(original.segments, target.segments):
        if kind != target_kind:
            raise ValueError("FREYJA_UPSCALE_PACKED_LAYOUT_INCOMPATIBLE")
        positions = original.position_ids[a:b]
        if kind in ("video", "cond"):
            positions = positions.reshape(-1, h // 2, w // 2, 3)[:, ys[0] // 2:ys[1] // 2, xs[0] // 2:xs[1] // 2].reshape(-1, 3)
        target.position_ids[c:d].copy_(positions)
    cropped["layout"] = target
    return cropped


def forward_tiles(executor, streams, timestep, context, transformer_options, minimax_payload=None,
                  tile_height=32, tile_width=48, overlap=8, **kwargs):
    import comfy.model_management
    if kwargs.get("control") is not None:
        raise ValueError("FREYJA_UPSCALE_TILING_CONTROL_UNSUPPORTED")
    video, audio = streams
    def predict(block, sound, ys, xs):
        comfy.model_management.throw_exception_if_processing_interrupted()
        payload = tile_payload(minimax_payload or {}, context, video, audio, ys, xs)
        return executor([block, sound], timestep, context, transformer_options.copy(), minimax_payload=payload, **kwargs)
    return tiled_prediction(video, audio, predict, tile_height, tile_width, overlap)


def prepare_tiles(executor, model, noise_shape, conds, model_options=None, force_full_load=False,
                  force_offload=False, tile_height=32, tile_width=48, overlap=8, latent_shapes=None):
    # H3's flattened AV vector otherwise makes ComfyUI reserve a full-frame workspace.
    shapes = latent_shapes
    if shapes is None or len(shapes) != 2:
        raise ValueError("FREYJA_UPSCALE_MEMORY_LAYOUT_UNAVAILABLE")
    video, audio = shapes
    if tuple(noise_shape) != (video[0], 1, math.prod(video[1:]) + math.prod(audio[1:])):
        raise ValueError("FREYJA_UPSCALE_MEMORY_LAYOUT_MISMATCH")
    h = min(video[-2], tile_height)
    w = min(video[-1], tile_width)
    elements = math.prod(video[1:3]) * h * w + math.prod(audio[1:])
    # Include complete AV state buffers and references, which tiling does not remove.
    refs = 0
    for group in conds.values():
        current = 0
        for cond in group or []:
            for ref in cond.get("minimax_refs") or []:
                for key in ("latent", "audio_latent"):
                    value = ref.get(key)
                    if torch.is_tensor(value):
                        current += value.numel()
            for frame in cond.get("minimax_keyframes") or []:
                value = frame.get("latent")
                if torch.is_tensor(value):
                    current += value.shape[1] * value.shape[2] * h * w
        refs = max(refs, current)
    bytes_per_element = model.model.memory_required((1, 1, 1))
    buffer_bytes = (math.prod(video[1:]) + math.prod(audio[1:])) * 4 * 8
    budget = (noise_shape[0], 1, elements + refs + math.ceil(buffer_bytes / max(1, bytes_per_element)))
    return executor(model, budget, conds, model_options=model_options,
                    force_full_load=force_full_load, force_offload=force_offload)


class FreyjaH3TileModel:
    RETURN_TYPES = ("MODEL",)
    FUNCTION = "apply"
    CATEGORY = "FREYJA/H3"
    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"model": ("MODEL",), "latent": ("LATENT",), "tile_width": ("INT", {"default": 768, "min": 256, "max": 2048, "step": 32}),
                             "tile_height": ("INT", {"default": 512, "min": 256, "max": 2048, "step": 32}),
                             "overlap": ("INT", {"default": 128, "min": 32, "max": 512, "step": 32})}}
    def apply(self, model, latent, tile_width, tile_height, overlap):
        import comfy.model_base
        import comfy.patcher_extension
        if not isinstance(model.model, comfy.model_base.MiniMaxH3):
            raise ValueError("FREYJA_UPSCALE_H3_MODEL_REQUIRED")
        if any(n % 32 for n in (tile_width, tile_height, overlap)) or not (256 <= tile_width <= 2048 and 256 <= tile_height <= 2048
                    and 32 <= overlap <= 512 and overlap < min(tile_width, tile_height)):
            raise ValueError("FREYJA_UPSCALE_TILE_GRID_INVALID")
        # This node is exclusively for independent enhancement; continuation masks
        # belong to BASE and must never leak into this model.
        if model.model_options.get("denoise_mask_function") is not None:
            raise ValueError("FREYJA_UPSCALE_MASK_OWNER_CONFLICT")
        out = model.clone()
        shapes = tuple(tuple(part.shape) for part in av(latent))
        options = dict(tile_width=tile_width // 16, tile_height=tile_height // 16, overlap=overlap // 16)
        wrappers = comfy.patcher_extension.WrappersMP
        out.add_wrapper_with_key(wrappers.DIFFUSION_MODEL, "freyja_h3_upscale_v1", partial(forward_tiles, **options))
        out.add_wrapper_with_key(wrappers.PREPARE_SAMPLING, "freyja_h3_upscale_budget_v1", partial(prepare_tiles, latent_shapes=shapes, **options))
        return (out,)


CLASSES = (FreyjaH3UpscaleEncode, FreyjaH3LoadLatent, FreyjaH3FinishSize, FreyjaH3TileModel)
