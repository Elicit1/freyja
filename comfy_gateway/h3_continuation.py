"""Versioned public protocol and deterministic graph adapter for FREYJA H3 nodes."""
import hashlib
import json
from typing import Literal, Optional
from pydantic import BaseModel, ConfigDict, Field, model_validator


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class Chroma(Strict):
    strength: float = Field(default=.45, ge=0, le=1)
    seamStrength: float = Field(default=.10, ge=0, le=1)
    taperFrames: int = Field(default=3, ge=1)
    @model_validator(mode="after")
    def validate_taper(self):
        if self.seamStrength > self.strength: raise ValueError("H3_CHROMA_SEAM_STRENGTH_INVALID")
        return self


class Redraw(Strict):
    strength: float = Field(default=.10, ge=0, le=.95)


class Protection(Strict):
    noiseStrength: float = Field(default=1, ge=0, le=1)
    useFirstFrameAnchor: bool = True
    useTailAnchor: bool = False
    tailAnchorStartFraction: float = Field(default=.75, ge=0, le=1)
    anchorSourceSegmentId: Optional[str] = None


class Continuity(Strict):
    mode: Literal["GUIDE", "GUIDE_CHROMA", "LATENT_REDRAW", "LATENT_PROTECTED"] = "GUIDE"
    chroma: Optional[Chroma] = None
    redraw: Optional[Redraw] = None
    protection: Optional[Protection] = None
    @model_validator(mode="after")
    def active_parameters(self):
        active={"GUIDE":None,"GUIDE_CHROMA":"chroma","LATENT_REDRAW":"redraw","LATENT_PROTECTED":"protection"}[self.mode]
        for name in ("chroma","redraw","protection"):
            if name != active and getattr(self,name) is not None: raise ValueError("H3_INACTIVE_STRATEGY_PARAMETERS")
        if active and getattr(self,active) is None:
            setattr(self,active,{"chroma":Chroma,"redraw":Redraw,"protection":Protection}[active]())
        return self


class ArtifactInput(Strict):
    url: str
    content_sha256: str = Field(pattern=r"^[a-f0-9]{64}$")


class LatentContext(ArtifactInput):
    frames: int = Field(ge=5, le=362)
    anchor: Optional[ArtifactInput] = None


REQUIREMENTS = {
    "GUIDE": {"MiniMaxH3AddGuide", "VHS_LoadVideo"},
    "GUIDE_CHROMA": {"MiniMaxH3AddGuide", "VHS_LoadVideo", "FreyjaH3ChromaNoise"},
    "LATENT_REDRAW": {"FreyjaH3Continue", "FreyjaH3Restore", "FreyjaH3SaveLatent"},
    "LATENT_PROTECTED": {"FreyjaH3Continue", "FreyjaH3Restore", "FreyjaH3SaveLatent"},
}


def mode_capabilities(nodes):
    return {mode:{"available":not (required-set(nodes)),
                  "reason":"缺少节点："+", ".join(sorted(required-set(nodes))) if required-set(nodes) else "",
                  "recipeVersion":1,"contextWindows":mode in {"GUIDE","GUIDE_CHROMA"},
                  "controlNet":True} for mode,required in REQUIREMENTS.items()}


def sampler_binding(workflow, cfg):
    cond = cfg["conditioning"]["id"]
    matches = [(key,n) for key,n in workflow.items() if n.get("class_type")=="SamplerCustomAdvanced" and n.get("inputs",{}).get("latent_image")==[cond,1]]
    if len(matches)!=1: raise ValueError("H3_NATIVE_BINDING_INVALID: expected one main AV sampler")
    return matches[0]


def compatibility_key(workflow, cfg, size):
    # Only immutable model/sampling dependencies, not prompt, seed, frame count or signed URL.
    sampler_id,sampler=sampler_binding(workflow,cfg)
    roots=[cfg["model_output"]["id"],cfg["video_vae"]["id"],cfg["audio_vae"]["id"]]
    for port in ("sigmas","sampler"):
        value=sampler["inputs"].get(port)
        if isinstance(value,list): roots.append(value[0])
    dependencies={}
    def visit(key):
        if key in dependencies: return
        node=workflow[key]
        if node.get("class_type")=="BasicScheduler":
            node={**node,"inputs":{**node["inputs"],"model":[cfg["model_output"]["id"],0]}}
        dependencies[key]=node
        for value in node.get("inputs",{}).values():
            if isinstance(value,list) and len(value)==2 and isinstance(value[0],str) and value[0] in workflow:visit(value[0])
    for root in roots: visit(root)
    return hashlib.sha256(json.dumps({"version":1,"size":size,"nodes":dependencies},sort_keys=True,separators=(",",":")).encode()).hexdigest()


def apply_native(workflow, request, cfg, base_model, latent_filename=None, anchor_filename=None):
    options=request.h3_options
    mode=options.continuity.mode
    if not options.save_latent and mode not in {"LATENT_REDRAW","LATENT_PROTECTED"}: return
    sampler_id,sampler=sampler_binding(workflow,cfg)
    compatibility=compatibility_key(workflow,cfg,request.size)
    result=[sampler_id,0]
    if options.latent_context:
        if not latent_filename: raise ValueError("H3_LATENT_UPLOAD_MISSING")
        if options.context_windows.enabled: raise ValueError("H3_NATIVE_WINDOWS_UNSUPPORTED: disable segment context windows")
        # The inherited multi-frame prefix owns the opening; retain an explicit end frame.
        workflow[cfg["conditioning"]["id"]]["inputs"].pop("first_frame",None)
        if mode=="LATENT_PROTECTED":
            p=options.continuity.protection
            strength=p.noiseStrength;first=p.useFirstFrameAnchor;tail=p.useTailAnchor;fraction=p.tailAnchorStartFraction
            if tail and options.control_net.is_active():raise ValueError("H3_TAIL_ANCHOR_CONTROL_UNSUPPORTED: disable tail anchor; first-frame protection remains available")
        else:strength=options.continuity.redraw.strength;first=False;tail=False;fraction=.75
        node={"class_type":"FreyjaH3Continue","inputs":{"latent":sampler["inputs"]["latent_image"],"model":base_model,
              "sigmas":sampler["inputs"]["sigmas"],"filename":latent_filename,"compatibility":compatibility,
              "frames":options.latent_context.frames,"mode":mode,"strength":strength,"first_anchor":first,
              "tail_anchor":tail,"tail_start_fraction":fraction,"anchor_filename":anchor_filename or ""}}
        workflow["h3_native_continue"]=node
        sampler["inputs"]["latent_image"]=["h3_native_continue",0]
        # Only guiders consume the patched model. Scheduler must stay upstream to avoid a graph cycle.
        for binding in cfg["conditioning_consumers"]:
            workflow[binding["id"]]["inputs"]["model"]=["h3_native_continue",1]
        workflow["h3_native_restore"]={"class_type":"FreyjaH3Restore","inputs":{"latent":result,"restore":["h3_native_continue",2]}}
        result=["h3_native_restore",0]
        # Decode video/audio from restored samples. This also feeds any existing refinement stage.
        for key,node in list(workflow.items()):
            if key in {"h3_native_restore",sampler_id}:continue
            for port,value in list(node.get("inputs",{}).items()):
                if value==[sampler_id,0] and node.get("class_type") in {"VAEDecode","VAEDecodeAudio"}:
                    node["inputs"][port]=result
    if options.save_latent:
        if not request.task_id:raise ValueError("H3_LATENT_REQUIRES_EXECUTION_ID")
        workflow["h3_save_latent"]={"class_type":"FreyjaH3SaveLatent","inputs":{"latent":result,"compatibility":compatibility,"execution_id":request.task_id}}
