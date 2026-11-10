"""Versioned, bounded H3 stages. A stage is a separate recoverable prompt.

Legacy templates remain untouched when dynamic_workflow is absent.
"""
import copy
import hashlib
import json
from pathlib import Path
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, model_validator
from h3_continuation import ArtifactInput, compatibility_key, sampler_binding


class UpscaleTiling(BaseModel):
    model_config = ConfigDict(extra="forbid")
    tile_width: int = Field(default=768, ge=256, le=2048, strict=True)
    tile_height: int = Field(default=512, ge=256, le=2048, strict=True)
    overlap: int = Field(default=192, ge=32, le=512, strict=True)
    temporal: bool = False
    window_frames: int = Field(default=56, ge=39, le=124, strict=True)
    overlap_frames: int = Field(default=22, ge=5, strict=True)

    @model_validator(mode="after")
    def valid_grid(self):
        if any(n % 32 for n in (self.tile_width, self.tile_height, self.overlap)) or self.overlap >= min(self.tile_width, self.tile_height):
            raise ValueError("DYNAMIC_TILE_GRID_INVALID")
        if (self.window_frames - 5) % 17 or (self.overlap_frames - 5) % 17 or self.overlap_frames >= self.window_frames:
            raise ValueError("DYNAMIC_TILE_WINDOW_INVALID")
        return self


class FreyjaUpscale(BaseModel):
    model_config = ConfigDict(extra="forbid")
    version: Literal[1] = 1
    method: Literal["LATENT_BICUBIC", "H3_LEARNED"] = "LATENT_BICUBIC"
    latent_model: Optional[str] = None
    steps: int = Field(default=2, ge=1, le=32, strict=True)
    memory: Literal["FULL", "TEMPORAL", "SPATIAL"] = "TEMPORAL"
    tiles: UpscaleTiling = Field(default_factory=UpscaleTiling)
    @model_validator(mode="after")
    def weight_required(self):
        if self.method == "H3_LEARNED" and (not self.latent_model or not self.latent_model.strip()):
            raise ValueError("DYNAMIC_H3_LATENT_MODEL_REQUIRED")
        return self


class DynamicStage(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    schema_version: Literal[1] = 1
    stage: Literal["BASE", "UPSCALE", "FACE"] = "BASE"
    revision: str
    source_url: Optional[str] = None
    source_sha256: Optional[str] = Field(default=None, pattern=r"^[a-f0-9]{64}$")
    denoise: float = Field(default=.25, gt=0, le=.6)
    scale: Literal[1, 2] = 1
    tiling: Optional[UpscaleTiling] = None
    component: Optional[FreyjaUpscale] = None
    source_latent: Optional[ArtifactInput] = None
    source_size: Optional[str] = Field(default=None, pattern=r"^[0-9]{2,4}x[0-9]{2,4}$")
    output_width: Optional[int] = Field(default=None, ge=2, le=4096, strict=True)
    output_height: Optional[int] = Field(default=None, ge=2, le=4096, strict=True)

    @model_validator(mode="after")
    def source_required(self):
        if self.stage == "BASE" and (self.source_url or self.source_sha256):
            raise ValueError("DYNAMIC_BASE_HAS_SOURCE")
        if self.stage != "BASE" and (not self.source_url or not self.source_sha256):
            raise ValueError("DYNAMIC_STAGE_SOURCE_REQUIRED")
        if self.stage == "UPSCALE" and self.scale != 2:
            raise ValueError("DYNAMIC_UPSCALE_REQUIRES_SCALE_2")
        if self.tiling is not None:
            raise ValueError("DYNAMIC_EXTERNAL_TILING_REMOVED: reselect FREYJA upscale component")
        if self.component is not None and self.stage != "UPSCALE":
            raise ValueError("DYNAMIC_COMPONENT_UPSCALE_ONLY")
        if (self.source_latent is None) != (self.source_size is None):
            raise ValueError("DYNAMIC_LATENT_SOURCE_SIZE_REQUIRED")
        if self.source_latent is not None:
            if self.stage != "UPSCALE" or self.component is None:
                raise ValueError("DYNAMIC_NATIVE_LATENT_UPSCALE_ONLY")
            if any(n < 32 or n % 32 for n in map(int, self.source_size.split("x"))):
                raise ValueError("DYNAMIC_LATENT_SOURCE_SIZE_INVALID")
        if (self.output_width is None) != (self.output_height is None) or self.stage == "BASE" and self.output_width is not None:
            raise ValueError("DYNAMIC_OUTPUT_SIZE_INVALID")
        return self


SUPPORTED_HANDLERS = {"minimax_h3_ref2va"}
MODULE = Path(__file__).parent / "workflow_modules/h3_face_v1.json"
COMPONENT_NODES = ["FreyjaH3UpscaleEncode", "FreyjaH3LatentResize", "FreyjaH3FinishSize"]


def stage_nodes(stage, capability):
    required = set(capability["requiredNodes"].get(stage.stage, []))
    if stage.component:
        required.discard("ImageScale")
        required.discard("H3InjectVideoLatent")
        required.update(COMPONENT_NODES)
        if stage.source_latent:
            required.discard("FreyjaH3UpscaleEncode")
            required.add("FreyjaH3LoadLatent")
        if stage.component.memory == "SPATIAL": required.add("FreyjaH3TileModel")
        if stage.component.memory == "TEMPORAL": required.add("MiniMaxH3ContextWindows")
        if stage.component.method == "H3_LEARNED": required.update(("LatentUpscaleModelLoader", "MiniMaxH3LatentUpscaleWithModel"))
    if stage.output_width is not None: required.add("FreyjaH3FinishSize")
    return required


def installed_latent_models(nodes):
    if not {"LatentUpscaleModelLoader", "MiniMaxH3LatentUpscaleWithModel"}.issubset(nodes):
        return []
    field = nodes["LatentUpscaleModelLoader"].get("input", {}).get("required", {}).get("model_name", [])
    choices = field[0] if field and isinstance(field[0], list) else []
    if len(field) > 1 and field[0] == "COMBO" and isinstance(field[1], dict):
        choices = field[1].get("options", [])
    return list(dict.fromkeys(name for name in choices if isinstance(name, str) and name.strip())) if isinstance(choices, list) else []


def validate_stage_nodes(stage, capability, nodes):
    required = stage_nodes(stage, capability)
    missing = required - set(nodes)
    if missing:
        raise ValueError("DYNAMIC_NODES_MISSING: " + ", ".join(sorted(missing)))
    if stage.component and stage.component.method == "H3_LEARNED":
        if stage.component.latent_model not in installed_latent_models(nodes):
            raise ValueError("DYNAMIC_H3_LATENT_MODEL_NOT_INSTALLED")


def describe(model, base_dir):
    supported = model.handler in SUPPORTED_HANDLERS and bool(model.h3_extensions)
    revision = None
    if supported:
        path = Path(model.template_file)
        if not path.is_absolute():
            path = base_dir / path
        digest = hashlib.sha256(path.read_bytes() + Path(__file__).read_bytes() + MODULE.read_bytes())
        digest.update(json.dumps({"bindings": model.h3_extensions, "mappings": model.mappings,
                                  "steps": model.default_steps}, sort_keys=True).encode())
        revision = digest.hexdigest()
    return {"modelCode": model.model_name, "nodeId": model.node_id, "supported": supported,
            "legacyFixedFace": model.handler == "minimax_h3_face_refine", "revision": revision,
            "stages": ["BASE", "UPSCALE", "FACE"] if supported else [],
            "scales": [2], "maxDenoise": .6, "maxTargetPixels": 4194304,
            "upscaleTiling": {"supported": supported, "node": "FreyjaH3TileModel", "apiVersion": "1",
                              "tileMin": 256, "tileMax": 2048, "tileStep": 32,
                              "overlapMin": 32, "overlapMax": 512, "temporal": True},
            "freyjaUpscale": {"supported": supported, "version": 1,
                              "nativeLatentInput": supported,
                              "methods": ["LATENT_BICUBIC", "H3_LEARNED"], "memoryModes": ["FULL", "TEMPORAL", "SPATIAL"],
                              "outputs": ["DOUBLE", "FULL_HD"], "maxSteps": model.default_steps},
            "requiredNodes": {"UPSCALE": ["VHS_LoadVideo", "ImageScale", "H3InjectVideoLatent", "FreyjaH3CheckFrames"],
                              "FACE": ["VHS_LoadVideo", "H3FaceTrackCrop", "H3InjectVideoLatent",
                                       "H3PerFrameDenoise", "H3FaceStitch", "FreyjaH3CheckFrames"]}}


def require_stage(request, model, base_dir):
    stage = request.dynamic_workflow
    if stage is None:
        return
    capability = describe(model, base_dir)
    if not capability["supported"]:
        raise ValueError("DYNAMIC_WORKFLOW_UNSUPPORTED: enhancements require a base REF2VA model")
    if stage.stage not in capability["stages"]:
        raise ValueError("DYNAMIC_STAGE_UNSUPPORTED: face repair requires a reference-video base model")
    if stage.revision != capability["revision"]:
        raise ValueError("DYNAMIC_WORKFLOW_REVISION_CHANGED: refresh workflow preview")
    if stage.stage != "BASE":
        if request.h3_options is not None:
            raise ValueError("DYNAMIC_ENHANCEMENT_CANNOT_CHANGE_CONTINUITY")
        if not request.length or request.length > 362 or (request.length - 5) % 17:
            raise ValueError("DYNAMIC_STAGE_FRAME_GRID_INVALID")
        width, height = request.parse_dimensions(multiple=32)
        if width * height > capability["maxTargetPixels"]:
            raise ValueError("DYNAMIC_TARGET_TOO_LARGE")
        if stage.component and stage.component.steps > model.default_steps:
            raise ValueError("DYNAMIC_UPSCALE_STEPS_EXCEED_MODEL")
        if stage.output_width is not None and not (0 <= width - stage.output_width <= 31 and 0 <= height - stage.output_height <= 31
                                                   and stage.output_width % 2 == 0 and stage.output_height % 2 == 0):
            raise ValueError("DYNAMIC_OUTPUT_CROP_INVALID")


def _prune(workflow, outputs):
    reachable = set()
    def visit(key):
        if key in reachable:
            return
        if key not in workflow:
            raise ValueError("DYNAMIC_GRAPH_DANGLING_LINK: " + key)
        reachable.add(key)
        for value in workflow[key].get("inputs", {}).values():
            if isinstance(value, list) and len(value) == 2 and isinstance(value[0], str) and isinstance(value[1], int):
                visit(value[0])
    for key in outputs:
        visit(key)
    return {key: node for key, node in workflow.items() if key in reachable}


def apply_stage(workflow, request, model, uploaded_source=None, uploaded_latent=None):
    stage = request.dynamic_workflow
    if stage is None or stage.stage == "BASE":
        return workflow
    if not uploaded_source:
        raise ValueError("DYNAMIC_SOURCE_UPLOAD_MISSING")
    cfg = model.h3_extensions
    sampler_id, sampler = sampler_binding(workflow, cfg)
    native_compatibility = compatibility_key(workflow, cfg, stage.source_size) if stage.source_latent else None
    outputs = [key for key, node in workflow.items() if node.get("class_type") == "SaveVideo"]
    videos = [key for key, node in workflow.items() if node.get("class_type") == "CreateVideo"]
    if len(outputs) != 1 or len(videos) != 1:
        raise ValueError("DYNAMIC_OUTPUT_BINDING_INVALID")
    video_id = videos[0]
    source = "dynamic_source"
    workflow[source] = {"class_type": "VHS_LoadVideo", "inputs": {
        "video": uploaded_source, "force_rate": 24, "custom_width": 0, "custom_height": 0,
        "frame_load_cap": 0, "skip_first_frames": 0, "select_every_nth": 1, "format": "None"}}
    workflow["dynamic_frames"] = {"class_type": "FreyjaH3CheckFrames", "inputs": {
        "images": [source, 0], "expected_frames": request.length}}
    images = ["dynamic_frames", 0]
    audio = [source, 2]
    model_link = [cfg["model_output"]["id"], 0]
    vae = [cfg["video_vae"]["id"], 0]
    cond_id = cfg["conditioning"]["id"]
    cond = workflow[cond_id]
    width, height = request.parse_dimensions(multiple=32)
    if stage.stage == "UPSCALE":
        workflow["dynamic_scale"] = {"class_type": "ImageScale", "inputs": {
            "image": images, "upscale_method": "bicubic", "width": width, "height": height, "crop": "disabled"}}
        workflow["dynamic_inject"] = {"class_type": "H3InjectVideoLatent", "inputs": {
            "av_latent": [cond_id, 1], "images": ["dynamic_scale", 0], "vae": vae}}
        sampler["inputs"]["latent_image"] = ["dynamic_inject", 0]
        if stage.component:
            component = stage.component
            # Encode at source size, then enlarge video latent; audio template is unchanged.
            workflow.pop("dynamic_scale")
            workflow.pop("dynamic_inject")
            if stage.source_latent:
                if not uploaded_latent:
                    raise ValueError("DYNAMIC_LATENT_UPLOAD_MISSING")
                source_width, source_height = map(int, stage.source_size.split("x"))
                workflow["dynamic_encode"] = {"class_type": "FreyjaH3LoadLatent", "inputs": {
                    "filename": uploaded_latent, "compatibility": native_compatibility,
                    "width": source_width, "height": source_height, "frames": request.length}}
            else:
                workflow["dynamic_encode"] = {"class_type": "FreyjaH3UpscaleEncode", "inputs": {
                    "images": images, "vae": vae, "av_template": [cond_id, 1]}}
            if component.method == "H3_LEARNED":
                workflow["dynamic_upscale_model"] = {"class_type": "LatentUpscaleModelLoader", "inputs": {"model_name": component.latent_model}}
                workflow["dynamic_lift"] = {"class_type": "MiniMaxH3LatentUpscaleWithModel", "inputs": {
                    "model": ["dynamic_upscale_model", 0], "samples": ["dynamic_encode", 0], "width": width, "height": height}}
            else:
                workflow["dynamic_lift"] = {"class_type": "FreyjaH3LatentResize", "inputs": {
                    "latent": ["dynamic_encode", 0], "width": width, "height": height}}
            sampler["inputs"]["latent_image"] = ["dynamic_lift", 0]
            final_model = model_link
            if component.memory == "SPATIAL":
                workflow["dynamic_tiles"] = {"class_type": "FreyjaH3TileModel", "inputs": {
                    "model": model_link, "latent": ["dynamic_lift", 0], "tile_width": component.tiles.tile_width,
                    "tile_height": component.tiles.tile_height, "overlap": component.tiles.overlap}}
                final_model = ["dynamic_tiles", 0]
            elif component.memory == "TEMPORAL":
                workflow["dynamic_windows"] = {"class_type": "MiniMaxH3ContextWindows", "inputs": {
                    "model": model_link, "context_length": component.tiles.window_frames, "context_overlap": component.tiles.overlap_frames,
                    "fuse_method": "pyramid", "freenoise": False, "split_conds_to_windows": False}}
                final_model = ["dynamic_windows", 0]
            for binding in cfg["model_consumers"]:
                workflow[binding["id"]]["inputs"][binding.get("input", "model")] = final_model
        schedule_id = sampler["inputs"]["sigmas"][0]
        workflow[schedule_id]["inputs"]["denoise"] = stage.denoise
        workflow[schedule_id]["inputs"]["steps"] = stage.component.steps if stage.component else model.default_steps
        workflow[video_id]["inputs"]["audio"] = audio
    else:
        # Reuse the existing face module's crop/inject/sample/stitch semantics.
        # Its model, VAE and ordered reference bindings come from this base model.
        fragment = json.loads(MODULE.read_text(encoding="utf-8"))
        replacements = {"122": images, "121": audio, "145": model_link,
                        "128": cond["inputs"]["clip"], "119": vae,
                        "120": [cfg["audio_vae"]["id"], 0],
                        "129": sampler["inputs"]["noise"], "123": sampler["inputs"]["sampler"]}
        for key, node in fragment.items():
            for port, value in list(node["inputs"].items()):
                if isinstance(value, list) and len(value) == 2 and isinstance(value[0], str):
                    node["inputs"][port] = copy.deepcopy(replacements.get(value[0], ["dynamic_face_" + value[0], value[1]]))
            workflow["dynamic_face_" + key] = node
        face_cond = workflow["dynamic_face_158"]["inputs"]
        face_cond["prompt"] = request.prompt
        # The source soundtrack is an output track, not an extra numbered reference.
        for key in list(face_cond):
            if key.startswith(("ref_images.", "ref_audios.", "ref_videos.", "ref_video_audios.")):
                face_cond.pop(key)
        for key, value in cond["inputs"].items():
            if key.startswith(("ref_images.", "ref_audios.", "ref_videos.", "ref_video_audios.")):
                face_cond[key] = copy.deepcopy(value)
        workflow["dynamic_face_162"]["inputs"].update(denoise=stage.denoise, steps=model.default_steps)
        workflow[video_id]["inputs"].update(images=["dynamic_face_165", 0], audio=audio)
    output_width, output_height = stage.output_width or width, stage.output_height or height
    if stage.output_width is not None:
        workflow["dynamic_finish"] = {"class_type": "FreyjaH3FinishSize", "inputs": {
            "images": workflow[video_id]["inputs"]["images"], "width": output_width, "height": output_height}}
        workflow[video_id]["inputs"]["images"] = ["dynamic_finish", 0]
    workflow["dynamic_output_frames"] = {"class_type": "FreyjaH3CheckFrames", "inputs": {
        "images": workflow[video_id]["inputs"]["images"], "expected_frames": request.length,
        "expected_width": output_width, "expected_height": output_height}}
    workflow[video_id]["inputs"]["images"] = ["dynamic_output_frames", 0]
    # Only this stage's output is executable. No previous sampler or hidden save branch survives.
    return _prune(workflow, outputs)
