"""Explicit H3 graph bindings. A feature is never inferred from a model name."""
import hashlib
import json
import math
from pathlib import Path
from typing import Literal, Optional
from pydantic import BaseModel, ConfigDict, Field, model_validator


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
    schema_version: Literal[1] = 1
    continuation_enabled: bool = False
    lock_context: bool = False
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
        return self


def revision(model, base_dir):
    path=Path(model.template_file)
    if not path.is_absolute(): path=base_dir/path
    digest=hashlib.sha256(path.read_bytes())
    digest.update(Path(__file__).read_bytes())
    digest.update((base_dir/"workflow_engine.py").read_bytes())
    digest.update(json.dumps(model.h3_extensions,sort_keys=True).encode())
    return digest.hexdigest()


async def backend_acceptance(model, client):
    cfg=model.h3_extensions or {}
    public={"status":cfg.get("status","UNSUPPORTED"),"backendSignature":None,"missingNodes":[],"controlTypes":[],"controlVersion":cfg.get("control_version")}
    if not cfg:return public
    baseline={cfg["conditioning"]["class_type"],"MiniMaxH3AddGuide"}
    required=set(baseline)
    if cfg.get("context_windows"):required.add("MiniMaxH3ContextWindows")
    if cfg.get("masked_continuation"):required.add("FreyjaH3Continuation")
    if cfg.get("control_net"):required.update(("MiniMaxH3FunControlNetApply","ModelPatchLoader","FreyjaH3ControlFrames"))
    if cfg.get("window_control_adapter"):required.add("FreyjaMiniMaxH3FunControlNetApply")
    if cfg.get("control_version")=="2.0":required.add("FreyjaH3Union2Patch")
    preprocessors=cfg.get("preprocessors",{})
    required.update(preset["class_type"] for preset in preprocessors.values())
    try:
        http=await client.get_http_client()
        nodes_response=await http.get(client.http_base+"/object_info",timeout=8);nodes_response.raise_for_status();nodes=nodes_response.json()
        stats_response=await http.get(client.http_base+"/system_stats",timeout=5);stats_response.raise_for_status();system=stats_response.json().get("system",{})
        public["missingNodes"]=sorted(required-set(nodes))
        public["controlTypes"]=[name for name,preset in preprocessors.items() if preset["class_type"] in nodes]+cfg.get("prepared_control_types",["PREPARED"])
        def normalized(value):
            # File inventories change independently of a node's protocol. Check the selected patch below.
            if isinstance(value,list) and value and isinstance(value[0],list):return ["COMBO"]+value[1:]
            return value
        schemas={name:{kind:{key:normalized(value) for key,value in inputs.items()} for kind,inputs in nodes[name].get("input",{}).items() if kind in ("required","optional")} for name in sorted(required) if name in nodes}
        patch_choices=nodes.get("ModelPatchLoader",{}).get("input",{}).get("required",{}).get("name",[[]])[0]
        patch_available=cfg.get("control_patch") in patch_choices
        public["contextWindows"]=bool(cfg.get("context_windows") and "MiniMaxH3ContextWindows" in nodes)
        public["maskedContinuation"]=bool(cfg.get("masked_continuation") and "FreyjaH3Continuation" in nodes)
        public["controlNet"]=bool(cfg.get("control_net") and patch_available and {"MiniMaxH3FunControlNetApply","ModelPatchLoader","FreyjaH3ControlFrames"}<=nodes.keys())
        if cfg.get("control_version")=="2.0":public["controlNet"]=public["controlNet"] and "FreyjaH3Union2Patch" in nodes
        public["windowControlAdapter"]=bool(cfg.get("window_control_adapter") and public["controlNet"] and public["contextWindows"] and "FreyjaMiniMaxH3FunControlNetApply" in nodes)
        if cfg.get("control_net") and not patch_available:public["missingNodes"].append("weight:"+str(cfg.get("control_patch")))
        fingerprint={"backend":client.http_base,"versions":{key:system.get(key) for key in ("comfyui_version","pytorch_version","python_version")},"nodes":schemas,"controlPatch":cfg.get("control_patch"),"patchAvailable":patch_available}
        public["backendSignature"]=hashlib.sha256(json.dumps(fingerprint,sort_keys=True).encode()).hexdigest()
        # Optional modules do not block plain guide continuation. Their independent flags
        # are checked only when the request opts in; the signed inventory still changes.
        if baseline-set(nodes) or cfg.get("verified_backend_signature")!=public["backendSignature"]:public["status"]="UNVERIFIED"
    except Exception:
        public["status"]="UNVERIFIED";public["missingNodes"].append("backend-unreachable")
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
    if not cfg or cfg.get("status")!="VERIFIED": raise ValueError("H3_UNVERIFIED: workflow requires online acceptance")
    if options.capability_revision!=revision(model,base_dir): raise ValueError("H3_REVISION_CHANGED: refresh continuation plan")
    if options.continuation_enabled and not cfg.get("continuation"): raise ValueError("H3_CONTINUATION_UNSUPPORTED")
    if options.lock_context and not cfg.get("masked_continuation"): raise ValueError("H3_MASKED_CONTINUATION_UNVERIFIED")
    if options.context_windows.enabled and not cfg.get("context_windows"): raise ValueError("H3_CONTEXT_UNSUPPORTED")
    if options.control_net.enabled:
        if not cfg.get("control_net"): raise ValueError("H3_CONTROL_UNSUPPORTED")
        if not any(v.reference_id==options.control_net.reference_id for v in request.get_all_ref_videos()):
            raise ValueError("H3_CONTROL_SOURCE_MISSING: select an attached reference video")
        if options.context_windows.enabled and not cfg.get("window_control_adapter"):
            raise ValueError("H3_WINDOW_CONTROL_UNVERIFIED")


def apply_extensions(workflow, request, model, videos, continuation_filename=None):
    options=request.h3_options
    if options is None: return
    cfg=model.h3_extensions
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
        workflow["h3_control_video"]={"class_type":"VHS_LoadVideo","inputs":{"video":video["filename"],"force_rate":24,"custom_width":0,"custom_height":0,"frame_load_cap":options.length,"skip_first_frames":0,"select_every_nth":1,"format":"None"}}
        workflow["h3_control_frames"]={"class_type":"FreyjaH3ControlFrames","inputs":{"images":["h3_control_video",0],"target_frames":options.length,"required_frames":options.control_required_frames or options.length}}
        source=["h3_control_frames",0]
        if control.control_type not in cfg.get("prepared_control_types",["PREPARED"]):
            preset=cfg.get("preprocessors",{}).get(control.control_type)
            if not preset: raise ValueError("H3_PREPROCESSOR_UNSUPPORTED: "+control.control_type)
            workflow["h3_control_preprocess"]={"class_type":preset["class_type"],"inputs":dict(preset.get("inputs",{}),**{preset.get("image_input","image"):source})}
            source=["h3_control_preprocess",preset.get("output_slot",0)]
        workflow["h3_patch"]={"class_type":"ModelPatchLoader","inputs":{"name":cfg["control_patch"]}}
        patch_source=["h3_patch",0]
        if cfg.get("control_version")=="2.0":
            workflow["h3_union2_patch"]={"class_type":"FreyjaH3Union2Patch","inputs":{"model_patch":patch_source}}
            patch_source=["h3_union2_patch",0]
        apply_class="FreyjaMiniMaxH3FunControlNetApply" if options.context_windows.enabled else "MiniMaxH3FunControlNetApply"
        workflow["h3_control"]={"class_type":apply_class,"inputs":{"model":final,"model_patch":patch_source,"vae":[vae_id,0],"strength":control.strength,"start_percent":0.0,"end_percent":1.0,"control_video":source}}
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
        # Guide conditioning is separate from reference labels and their ordered contract.
        workflow["h3_guide"]={"class_type":"MiniMaxH3AddGuide","inputs":{"positive":[cond_id,0],"latent":[cond_id,1],"vae":[vae_id,0],"image":["h3_context_video",0],"frame_idx":0}}
        if options.lock_context:
            workflow["h3_guide"]["class_type"]="FreyjaH3Continuation"
            for binding in cfg["latent_consumers"]:
                workflow[binding["id"]]["inputs"][binding["input"]]=["h3_guide",1]
        if cfg.get("audio_vae"):
            audio_id,_=node("audio_vae")
            workflow["h3_guide"]["inputs"].update({"audio_vae":[audio_id,0],"audio":["h3_context_video",2]})
        for binding in cfg["conditioning_consumers"]:
            workflow[binding["id"]]["inputs"][binding.get("input","conditioning")]=["h3_guide",0]
