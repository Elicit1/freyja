"""Explicit, versioned reference-video capabilities; no model-name inference."""
import hashlib
import json
from pathlib import Path
from typing import Optional, List, Literal

from pydantic import BaseModel, Field, model_validator


class ReferenceVideoCapability(BaseModel):
    schema_version: Literal[1] = 1
    status: Literal["UNSUPPORTED", "UNVERIFIED", "VERIFIED"] = "UNVERIFIED"
    adapter: Literal["MINIMAX_H3_REF_VIDEO_V1"] = "MINIMAX_H3_REF_VIDEO_V1"
    verified_revision: Optional[str] = None
    verified_backend: Optional[str] = None
    max_videos: int = Field(default=1, ge=1, le=1)
    min_clip_seconds: Optional[float] = Field(default=None, gt=0)
    max_clip_seconds: Optional[float] = Field(default=None, gt=0)
    max_total_clip_seconds: Optional[float] = Field(default=None, gt=0)
    decode_fps: int = Field(default=24, ge=1, le=60)
    max_decoded_frames: Optional[int] = Field(default=None, gt=0)
    max_decode_width: Optional[int] = Field(default=None, ge=2)
    max_decode_height: Optional[int] = Field(default=None, ge=2)
    max_mixed_assets: Optional[int] = Field(default=None, gt=0)
    supports_video_audio: bool = False
    max_download_bytes: int = Field(default=128 * 1024 * 1024, gt=0)
    condition_node_id: str = "136"
    output_node_ids: List[str] = Field(default_factory=lambda: ["130"])

    @model_validator(mode="after")
    def validate_verified(self):
        if self.status == "VERIFIED":
            required = (self.verified_revision, self.verified_backend, self.min_clip_seconds, self.max_clip_seconds,
                        self.max_total_clip_seconds, self.max_decoded_frames,
                        self.max_decode_width, self.max_decode_height, self.max_mixed_assets)
            if any(value is None for value in required):
                raise ValueError("VERIFIED reference-video capability requires all measured limits and revision")
            if self.min_clip_seconds > self.max_clip_seconds:
                raise ValueError("Reference-video minimum duration exceeds maximum")
        return self


def workflow_revision(model, base_dir: Path) -> str:
    path = Path(model.template_file)
    if not path.is_absolute():
        path = base_dir / path
    # Include adapter code: changing the adapter invalidates the online acceptance record.
    adapter_files = (Path(__file__), base_dir / "workflow_engine.py", base_dir / "reference_prompt.py")
    digest = hashlib.sha256(path.read_bytes())
    for adapter in adapter_files:
        digest.update(adapter.read_bytes())
    capability = model.reference_video.model_dump(exclude={"status", "verified_revision", "verified_backend"}) if model.reference_video else None
    digest.update(json.dumps({"handler": model.handler, "mappings": model.mappings,
                              "capability": capability}, sort_keys=True).encode())
    return digest.hexdigest()


def require_verified(model, base_dir: Path, backend_url=None):
    capability = model.reference_video
    if capability is None or capability.status == "UNSUPPORTED":
        raise ValueError("REFERENCE_VIDEO_UNSUPPORTED: model has no reference-video adapter")
    if capability.status != "VERIFIED":
        raise ValueError("REFERENCE_VIDEO_UNVERIFIED: online acceptance is required")
    if capability.verified_revision != workflow_revision(model, base_dir):
        raise ValueError("REFERENCE_VIDEO_WORKFLOW_MISMATCH: workflow/adapter changed since acceptance")
    if backend_url is not None and capability.verified_backend != backend_url:
        raise ValueError("REFERENCE_VIDEO_UNVERIFIED: this compute backend has not been accepted")
    if model.handler != "minimax_h3_ref2va":
        raise ValueError("REFERENCE_VIDEO_UNSUPPORTED: handler has not been accepted for video references")
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
