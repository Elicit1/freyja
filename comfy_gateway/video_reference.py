"""Explicit, versioned reference-video capabilities; no model-name inference."""
import hashlib
import json
from pathlib import Path
from typing import Optional, List, Literal

from pydantic import BaseModel, Field, model_validator


class ReferenceVideoCapability(BaseModel):
    schema_version: Literal[1] = 1
    adapter: Literal["MINIMAX_H3_REF_VIDEO_V1"] = "MINIMAX_H3_REF_VIDEO_V1"
    max_videos: int = Field(ge=1)
    min_clip_seconds: Optional[float] = Field(default=0.1, gt=0)
    max_clip_seconds: Optional[float] = Field(default=15, gt=0)
    max_total_clip_seconds: Optional[float] = Field(default=15, gt=0)
    decode_fps: int = Field(default=24, ge=1, le=60)
    max_decoded_frames: Optional[int] = Field(default=360, gt=0)
    max_decode_width: Optional[int] = Field(default=768, ge=2)
    max_decode_height: Optional[int] = Field(default=768, ge=2)
    max_mixed_assets: Optional[int] = Field(default=12, gt=0)
    supports_video_audio: bool = False
    max_download_bytes: int = Field(default=128 * 1024 * 1024, gt=0)
    condition_node_id: str = "136"
    output_node_ids: List[str] = Field(default_factory=lambda: ["130"])

    @model_validator(mode="after")
    def validate_limits(self):
        if self.min_clip_seconds is not None and self.max_clip_seconds is not None and self.min_clip_seconds > self.max_clip_seconds:
            raise ValueError("Reference-video minimum duration exceeds maximum")
        return self


def workflow_revision(model, base_dir: Path) -> str:
    path = Path(model.template_file)
    if not path.is_absolute():
        path = base_dir / path
    # Include adapter code so queued preparations cannot use a different workflow.
    adapter_files = (Path(__file__), base_dir / "workflow_engine.py", base_dir / "reference_prompt.py")
    digest = hashlib.sha256(path.read_bytes())
    for adapter in adapter_files:
        digest.update(adapter.read_bytes())
    capability = model.reference_video.model_dump() if model.reference_video else None
    digest.update(json.dumps({"handler": model.handler, "mappings": model.mappings,
                              "capability": capability}, sort_keys=True).encode())
    return digest.hexdigest()


def require_supported(model, base_dir: Path, backend_url=None):
    capability = model.reference_video
    if capability is None:
        raise ValueError("REFERENCE_VIDEO_UNSUPPORTED: model has no reference-video adapter")
    # Historical acceptance stamps are informational. Current requests still pin
    # workflow_revision, exact model/node routing, media hashes and graph bindings.
    required = (capability.min_clip_seconds, capability.max_clip_seconds, capability.max_total_clip_seconds,
                capability.max_decoded_frames, capability.max_decode_width, capability.max_decode_height, capability.max_mixed_assets)
    if any(value is None for value in required) or capability.min_clip_seconds > min(capability.max_clip_seconds, capability.max_total_clip_seconds):
        raise ValueError("REFERENCE_VIDEO_LIMITS_INVALID: configure valid media limits")
    if model.handler not in {"minimax_h3_ref2va", "minimax_h3_face_refine"}:
        raise ValueError("REFERENCE_VIDEO_UNSUPPORTED: handler does not support video references")
    return capability


def assert_video_paths(workflow, capability, loader_ids):
    """Validate an actual path from each loader to conditioning and configured output."""
    cond = capability.condition_node_id
    if cond not in workflow or workflow[cond].get("class_type") != "MiniMaxH3ReferenceToVideo":
        raise ValueError("REFERENCE_VIDEO_WORKFLOW_MISMATCH: reference conditioning node is missing")

    def ancestors(node_id, seen=None):
        seen = set() if seen is None else seen
        if node_id in seen:
            return seen
        seen.add(node_id)
        node = workflow.get(node_id)
        if not isinstance(node, dict):
            raise ValueError("REFERENCE_VIDEO_WORKFLOW_MISMATCH: dangling node link")
        for value in node.get("inputs", {}).values():
            if isinstance(value, list) and len(value) == 2 and isinstance(value[0], str) and isinstance(value[1], int):
                ancestors(value[0], seen)
        return seen

    outputs = [ancestors(node_id) for node_id in capability.output_node_ids]
    for index, loader in enumerate(loader_ids):
        if workflow[cond]["inputs"].get(f"ref_videos.ref_video_{index}") != [loader, 0]:
            raise ValueError("REFERENCE_VIDEO_WORKFLOW_MISMATCH: video frames are not connected")
        if not any(cond in upstream and loader in upstream for upstream in outputs):
            raise ValueError("REFERENCE_VIDEO_WORKFLOW_MISMATCH: reference is disconnected from output")
