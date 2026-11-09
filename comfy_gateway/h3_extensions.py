"""Explicit H3 graph bindings. A feature is never inferred from a model name."""
import hashlib
import asyncio
import json
import math
from pathlib import Path
from typing import Literal, Optional
from pydantic import BaseModel, ConfigDict, Field, model_validator
from h3_continuation import Continuity, LatentContext, mode_capabilities, apply_native


class ContextOptions(BaseModel):
    model_config = ConfigDict(extra="forbid")
    enabled: bool = False
    context_length: int = 124
    context_overlap: int = 39
    fuse_method: Literal["pyramid", "flat"] = "pyramid"
    freenoise: bool = True
    @model_validator(mode="after")
    def grid(self):
        if self.context_length < 22 or (self.context_length-5)%17 or self.context_overlap < 5 or (self.context_overlap-5)%17 or self.context_overlap >= self.context_length:
            raise ValueError("H3_CONTEXT_INVALID: invalid window/overlap frame grid")
        return self


class ControlOptions(BaseModel):
    model_config = ConfigDict(extra="forbid")
    enabled: bool = False
    reference_id: Optional[str] = None
    control_type: Literal["CANNY", "POSE", "DEPTH", "HED", "MLSD", "SCRIBBLE", "LAYOUT", "GRAY", "PREPARED"] = "CANNY"
    strength: float = Field(default=1, ge=0, le=2, allow_inf_nan=False)


class ContinuationInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    video_url: str
    content_sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    frames: int = Field(ge=5, le=362)


class H3Options(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schema_version: Literal[1,2] = 1
    continuity: Continuity = Field(default_factory=Continuity)
    save_latent: bool = False
    backend_signature: Optional[str] = None
    latent_context: Optional[LatentContext] = None
    continuation_enabled: bool = False
    # Old snapshots may contain false; true would require the removed private sampler path.
    lock_context: Literal[False] = False
    capability_revision: str
    length: int = Field(ge=22, le=362)
    control_required_frames: Optional[int] = Field(default=None,ge=1,le=362)
    context_windows: ContextOptions = Field(default_factory=ContextOptions)
    control_net: ControlOptions = Field(default_factory=ControlOptions)
    continuation: Optional[ContinuationInput] = None
    @model_validator(mode="after")
    def validate_frame_grid(self):
        if (self.length-5)%17 or self.continuation and (self.continuation.frames-5)%17:
            raise ValueError("H3_FRAME_GRID_INVALID")
        if self.continuation and (not self.continuation_enabled or self.continuation.frames >= self.length):
            raise ValueError("H3_CONTINUATION_INVALID")
        if self.control_required_frames is not None and self.control_required_frames>self.length:raise ValueError("H3_CONTROL_RANGE_INVALID")
        native=self.continuity.mode in {"LATENT_REDRAW","LATENT_PROTECTED"}
        if self.latent_context and (not native or not self.continuation_enabled or self.latent_context.frames>=self.length or (self.latent_context.frames-5)%17):raise ValueError("H3_LATENT_CONTEXT_INVALID")
        if native and self.continuation:raise ValueError("H3_NATIVE_NO_COND_PIN")
        if self.latent_context and self.continuation:raise ValueError("H3_CONTEXT_SOURCE_CONFLICT")
        if self.continuation and self.continuity.mode=="GUIDE_CHROMA" and self.continuity.chroma.taperFrames>self.continuation.frames:raise ValueError("H3_CHROMA_TAPER_INVALID")
        if self.schema_version==1 and (native or self.save_latent or self.continuity.mode!="GUIDE"):raise ValueError("H3_STRATEGY_REQUIRES_V2")
        if native and self.context_windows.enabled:raise ValueError("H3_NATIVE_WINDOWS_UNSUPPORTED")
        return self


def revision(model, base_dir):
    path=Path(model.template_file)
    if not path.is_absolute(): path=base_dir/path
    digest=hashlib.sha256(path.read_bytes())
    digest.update(Path(__file__).read_bytes())
    digest.update((base_dir/"workflow_engine.py").read_bytes())
    digest.update((base_dir/"reference_media.py").read_bytes())
    digest.update((base_dir/"h3_continuation.py").read_bytes())
    digest.update(json.dumps({k:v for k,v in (model.h3_extensions or {}).items() if k not in {"status","verified_backend_signature"}},sort_keys=True).encode())
    return digest.hexdigest()


async def inspect_backend(model, client):
    cfg=model.h3_extensions or {}
    public={"continuation":False,"backendSignature":None,"missingNodes":[],"controlTypes":[],"controlVersion":cfg.get("control_version")}
    if not cfg:return public
    baseline={cfg["conditioning"]["class_type"],"MiniMaxH3AddGuide"}
    required=set(baseline)
    if cfg.get("context_windows"):required.add("MiniMaxH3ContextWindows")
    if cfg.get("control_net"):required.update(("MiniMaxH3FunControlNetApply","ModelPatchLoader"))
    preprocessors=cfg.get("preprocessors",{})
    required.update(preset["class_type"] for preset in preprocessors.values())
    try:
        http=await client.get_http_client()
        nodes_response,stats_response=await asyncio.gather(
            http.get(client.http_base+"/object_info",timeout=8),
            http.get(client.http_base+"/system_stats",timeout=5))
        nodes_response.raise_for_status();nodes=nodes_response.json()
        stats_response.raise_for_status();system=stats_response.json().get("system",{})
        public["missingNodes"]=sorted(required-set(nodes))
        public["continuationModes"]=mode_capabilities(nodes)
        public["continuation"]=bool(cfg.get("continuation") and baseline<=nodes.keys())
        public["controlTypes"]=[name for name,preset in preprocessors.items() if preset["class_type"] in nodes]+["GRAY"]+cfg.get("prepared_control_types",["PREPARED"])
        def normalized(value):
            # File inventories change independently of a node's protocol. Check the selected patch below.
            if isinstance(value,list) and value and isinstance(value[0],list):return ["COMBO"]+value[1:]
            return value
        signature_nodes=required|{"FreyjaH3ChromaNoise","FreyjaH3Continue","FreyjaH3Restore","FreyjaH3SaveLatent"}
        schemas={name:{kind:{key:normalized(value) for key,value in inputs.items()} for kind,inputs in nodes[name].get("input",{}).items() if kind in ("required","optional")} for name in sorted(signature_nodes) if name in nodes}
        patch_choices=nodes.get("ModelPatchLoader",{}).get("input",{}).get("required",{}).get("name",[[]])[0]
        patch_available=cfg.get("control_patch") in patch_choices
        public["contextWindows"]=bool(cfg.get("context_windows") and "MiniMaxH3ContextWindows" in nodes)
        public["controlNet"]=bool(cfg.get("control_net") and patch_available and {"MiniMaxH3FunControlNetApply","ModelPatchLoader"}<=nodes.keys())
        public["contextWindowsWithControlNet"]=bool(cfg.get("context_windows_with_control_net") and public["controlNet"] and public["contextWindows"])
        if cfg.get("control_net") and not patch_available:public["missingNodes"].append("weight:"+str(cfg.get("control_patch")))
        fingerprint={"backend":client.http_base,"versions":{key:system.get(key) for key in ("comfyui_version","pytorch_version","python_version")},"nodes":schemas,"controlPatch":cfg.get("control_patch"),"patchAvailable":patch_available}
        public["backendSignature"]=hashlib.sha256(json.dumps(fingerprint,sort_keys=True).encode()).hexdigest()
    except Exception:
        public["missingNodes"].append("backend-unreachable")
    return public


def require_options(request, model, base_dir):
    options=request.h3_options
    if options is None: return
    if request.length is not None and request.length!=options.length:raise ValueError("H3_LENGTH_CONFLICT")
    if request.duration is not None:
        if not math.isfinite(request.duration) or request.duration<=0:raise ValueError("H3_DURATION_INVALID")
        requested=math.floor(request.duration*24+0.5)
        if requested+(5-requested)%17!=options.length:raise ValueError("H3_LENGTH_DURATION_CONFLICT")
    if not options.continuation_enabled and request.duration and request.duration>15:
        raise ValueError("H3_CONTINUATION_REQUIRED")
    cfg=model.h3_extensions
    if not cfg: raise ValueError("H3_UNSUPPORTED: workflow has no H3 adapter")
    if options.capability_revision!=revision(model,base_dir): raise ValueError("H3_REVISION_CHANGED: refresh continuation plan")
    if options.latent_context and request.first_frame:raise ValueError("H3_NATIVE_FIRST_FRAME_CONFLICT: native context owns the opening; remove the segment first frame")
    if options.continuation_enabled and not cfg.get("continuation"): raise ValueError("H3_CONTINUATION_UNSUPPORTED")
    if options.context_windows.enabled and not cfg.get("context_windows"): raise ValueError("H3_CONTEXT_UNSUPPORTED")
    if options.control_net.enabled:
        if not cfg.get("control_net"): raise ValueError("H3_CONTROL_UNSUPPORTED")
        if not any(v.reference_id==options.control_net.reference_id for v in request.get_all_ref_videos()):
            raise ValueError("H3_CONTROL_SOURCE_MISSING: select an attached reference video")
        if options.context_windows.enabled and not cfg.get("context_windows_with_control_net"):
            raise ValueError("H3_WINDOW_CONTROL_UNVERIFIED")


def apply_extensions(workflow, request, model, videos, continuation_filename=None, latent_filename=None, anchor_filename=None):
    options=request.h3_options
    if options is None: return
    cfg=model.h3_extensions
    if options.control_net.enabled and options.context_windows.enabled and not cfg.get("context_windows_with_control_net"):
        raise ValueError("H3_WINDOW_CONTROL_UNVERIFIED: use per-segment control without context windows")
    def node(key):
        binding=cfg[key]; value=workflow.get(binding["id"])
        if not value or value.get("class_type")!=binding["class_type"]: raise ValueError("H3_GRAPH_MISMATCH: "+key)
        return binding["id"],value
    model_id,_=node("model_output")
    cond_id,cond=node("conditioning")
    vae_id,_=node("video_vae")
    cond["inputs"]["length"]=options.length
    final=[model_id,0]
    if options.control_net.enabled:
        control=options.control_net
        video=next(v for v in videos if v["reference_id"]==control.reference_id)
        if video.get("frames",0)<(options.control_required_frames or options.length):
            raise ValueError("H3_CONTROL_RANGE_INVALID: control video does not cover the requested output interval")
        if control.control_type=="GRAY" and not video.get("control_filename"):
            raise ValueError("H3_GRAY_SOURCE_MISSING: prepare a separate grayscale control video")
        filename=video["control_filename"] if control.control_type=="GRAY" else video["filename"]
        workflow["h3_control_video"]={"class_type":"VHS_LoadVideo","inputs":{"video":filename,"force_rate":24,"custom_width":0,"custom_height":0,"frame_load_cap":options.length,"skip_first_frames":0,"select_every_nth":1,"format":"None"}}
        source=["h3_control_video",0]
        if control.control_type!="GRAY" and control.control_type not in cfg.get("prepared_control_types",["PREPARED"]):
            preset=cfg.get("preprocessors",{}).get(control.control_type)
            if not preset: raise ValueError("H3_PREPROCESSOR_UNSUPPORTED: "+control.control_type)
            workflow["h3_control_preprocess"]={"class_type":preset["class_type"],"inputs":dict(preset.get("inputs",{}),**{preset.get("image_input","image"):source})}
            source=["h3_control_preprocess",preset.get("output_slot",0)]
        workflow["h3_patch"]={"class_type":"ModelPatchLoader","inputs":{"name":cfg["control_patch"]}}
        workflow["h3_control"]={"class_type":"MiniMaxH3FunControlNetApply","inputs":{"model":final,"model_patch":["h3_patch",0],"vae":[vae_id,0],"strength":control.strength,"start_percent":0.0,"end_percent":1.0,"control_video":source}}
        final=["h3_control",0]
    if options.context_windows.enabled:
        window=options.context_windows
        workflow["h3_windows"]={"class_type":"MiniMaxH3ContextWindows","inputs":{"model":final,"context_length":window.context_length,"context_overlap":window.context_overlap,"fuse_method":window.fuse_method,"freenoise":window.freenoise,"split_conds_to_windows":False}}
        final=["h3_windows",0]
    for binding in cfg["model_consumers"]:
        value=workflow.get(binding["id"])
        if not value or value.get("class_type")!=binding["class_type"]: raise ValueError("H3_GRAPH_MISMATCH: model consumer")
        value["inputs"][binding.get("input","model")]=final
    if options.continuation:
        if not continuation_filename: raise ValueError("H3_CONTINUATION_MISSING")
        workflow["h3_context_video"]={"class_type":"VHS_LoadVideo","inputs":{"video":continuation_filename,"force_rate":24,"custom_width":0,"custom_height":0,"frame_load_cap":options.continuation.frames,"skip_first_frames":0,"select_every_nth":1,"format":"None"}}
        images=["h3_context_video",0]
        if options.continuity.mode=="GUIDE_CHROMA":
            chroma=options.continuity.chroma
            workflow["h3_chroma"]={"class_type":"FreyjaH3ChromaNoise","inputs":{"images":images,"strength":chroma.strength,"seam_strength":chroma.seamStrength,"taper_frames":chroma.taperFrames,"seed":request.seed or 1}}
            images=["h3_chroma",0]
        # Guide conditioning is separate from reference labels and their ordered contract.
        workflow["h3_guide"]={"class_type":"MiniMaxH3AddGuide","inputs":{"positive":[cond_id,0],"latent":[cond_id,1],"vae":[vae_id,0],"image":["h3_context_video",0],"frame_idx":0}}
        workflow["h3_guide"]["inputs"]["image"]=images
        if cfg.get("audio_vae"):
            audio_id,_=node("audio_vae")
            workflow["h3_guide"]["inputs"].update({"audio_vae":[audio_id,0],"audio":["h3_context_video",2]})
        for binding in cfg["conditioning_consumers"]:
            workflow[binding["id"]]["inputs"][binding.get("input","conditioning")]=["h3_guide",0]
    apply_native(workflow,request,cfg,final,latent_filename,anchor_filename)
