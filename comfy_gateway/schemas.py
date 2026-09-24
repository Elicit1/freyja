import re
import time
from typing import List, Optional, Dict, Any, Tuple, Literal
from pydantic import BaseModel, Field, model_validator
from reference_prompt import ReferenceBinding, ReferenceType


class ImageGenerationRequest(BaseModel):
    model: str = Field(default="flux-2-klein-9b", description="Model name matching workflows or nodes")
    prompt: str = Field(..., description="Positive text prompt")
    negative_prompt: Optional[str] = Field(default=None, description="Optional negative text prompt")
    size: str = Field(default="1024x1024", description="Image resolution (e.g. '1280x720', '1024x1024')")
    seed: Optional[int] = Field(default=None, description="Random seed (defaults to random int)")
    steps: Optional[int] = Field(default=None, description="Sampling steps")
    cfg: Optional[float] = Field(default=None, description="Classifier Free Guidance scale")
    response_format: Literal["url", "b64_json"] = Field(default="url", description="Response format ('url' or 'b64_json')")
    extra_body: Optional[Dict[str, Any]] = Field(default=None, description="OpenAI extra parameters dictionary")
    ref_images: Optional[List[str]] = Field(default=None, description="Optional list of reference image URLs or file paths")
    duration: Optional[float] = Field(default=None, description="Video duration in seconds (e.g. 5.0, 15.0)")
    first_frame: Optional[str] = Field(default=None, description="Optional first frame URL/path/Base64")
    last_frame: Optional[str] = Field(default=None, description="Optional last frame URL/path/Base64")
    length: Optional[int] = Field(default=None, description="Explicit frame count for video generation")

    # Reference Material Bindings & Semantic Prompt
    references: Optional[List[ReferenceBinding]] = Field(default=None, description="Structured reference bindings for characters, scenes, props, etc.")
    shot_id: Optional[str] = Field(default=None, description="Optional shot identifier for tracking and logging")
    task_id: Optional[str] = Field(default=None, description="Optional render task ID for cancellation and tracking")
    enable_reference_prompt: bool = Field(default=True, description="Whether to auto-generate reference relation prompt when references are present")
    continuity_prompt: Optional[str] = Field(default=None, description="Optional custom continuity constraints prompt override")

    # LatentSync & Video Extensions
    video_url: Optional[str] = Field(default=None, description="Source face video URL or local file path for lip-sync")
    audio_url: Optional[str] = Field(default=None, description="Source audio dialogue URL or local file path for lip-sync")
    inference_steps: Optional[int] = Field(default=None, description="Inference steps override")
    lips_expression: Optional[float] = Field(default=None, description="LatentSync lip expression multiplier (e.g. 1.5)")
    vram_usage: Optional[str] = Field(default=None, description="LatentSync VRAM usage mode: low, medium, high")

    # Multi-Node Routing Overrides
    node_id: Optional[str] = Field(default=None, description="Optional compute node ID override")
    target_backend: Optional[str] = Field(default=None, description="Optional direct ComfyUI HTTP URL override")

    # Prompt Format Specification
    prompt_format: Optional[str] = Field(default=None, description="Prompt format specification (e.g. 'MINIMAX_H3_REF2VA_V1', 'MINIMAX_H3_FL2VA_V1')")

    @model_validator(mode="after")
    def populate_video_and_ref_fields(self) -> "ImageGenerationRequest":
        """Merge ref_images, video, audio, and generation parameters from extra_body"""
        if self.seed is not None and self.seed < 0:
            self.seed = None
        if self.extra_body and isinstance(self.extra_body, dict):
            # 1. Reference images
            if self.ref_images is None:
                extracted = self.extra_body.get("ref_images") or self.extra_body.get("reference_images")
                if isinstance(extracted, list):
                    self.ref_images = [str(x) for x in extracted]
                elif isinstance(extracted, str):
                    self.ref_images = [extracted]

            # 2. Duration / Length / First-Last Frame
            if self.duration is None and "duration" in self.extra_body:
                try:
                    self.duration = float(self.extra_body["duration"])
                except (ValueError, TypeError):
                    pass

            if self.first_frame is None and "first_frame" in self.extra_body:
                self.first_frame = str(self.extra_body["first_frame"])

            if self.last_frame is None and "last_frame" in self.extra_body:
                self.last_frame = str(self.extra_body["last_frame"])

            if self.length is None and "length" in self.extra_body:
                try:
                    self.length = int(self.extra_body["length"])
                except (ValueError, TypeError):
                    pass

            # 3. LatentSync & Lip-Sync parameters
            if self.video_url is None:
                v = self.extra_body.get("video_url") or self.extra_body.get("video") or self.extra_body.get("video_path")
                if v is not None:
                    self.video_url = str(v)

            if self.audio_url is None:
                a = self.extra_body.get("audio_url") or self.extra_body.get("audio") or self.extra_body.get("audio_path")
                if a is not None:
                    self.audio_url = str(a)

            if self.inference_steps is None:
                steps_val = self.extra_body.get("inference_steps") or self.extra_body.get("steps")
                if steps_val is not None:
                    try:
                        self.inference_steps = int(steps_val)
                    except (ValueError, TypeError):
                        pass

            if self.lips_expression is None and "lips_expression" in self.extra_body:
                try:
                    self.lips_expression = float(self.extra_body["lips_expression"])
                except (ValueError, TypeError):
                    pass

            if self.seed is None and "seed" in self.extra_body:
                try:
                    s = int(self.extra_body["seed"])
                    self.seed = None if s < 0 else s
                except (ValueError, TypeError):
                    pass

            if self.vram_usage is None and "vram_usage" in self.extra_body:
                self.vram_usage = str(self.extra_body["vram_usage"]).lower()

            # 4. Reference Material Bindings & Semantic Prompt
            if self.references is None and ("references" in self.extra_body or "reference_bindings" in self.extra_body):
                raw_refs = self.extra_body.get("references") or self.extra_body.get("reference_bindings")
                if isinstance(raw_refs, list):
                    parsed_refs = []
                    for item in raw_refs:
                        if isinstance(item, ReferenceBinding):
                            parsed_refs.append(item)
                        elif isinstance(item, dict):
                            parsed_refs.append(ReferenceBinding.model_validate(item))
                    self.references = parsed_refs

            if self.shot_id is None:
                sid = self.extra_body.get("shot_id") or self.extra_body.get("shotId")
                if sid:
                    self.shot_id = str(sid)

            if self.task_id is None:
                tid = self.extra_body.get("task_id") or self.extra_body.get("taskId")
                if tid:
                    self.task_id = str(tid)

            if "enable_reference_prompt" in self.extra_body:
                self.enable_reference_prompt = bool(self.extra_body["enable_reference_prompt"])

            if self.continuity_prompt is None and "continuity_prompt" in self.extra_body:
                self.continuity_prompt = str(self.extra_body["continuity_prompt"])

            # Auto-fill first_frame / last_frame / audio_url from references if not explicitly set
            if self.references:
                for b in self.references:
                    if self.first_frame is None and b.reference_type == ReferenceType.FIRST_FRAME and b.images:
                        self.first_frame = b.images[0]
                    elif self.last_frame is None and b.reference_type == ReferenceType.LAST_FRAME and b.images:
                        self.last_frame = b.images[0]
                    elif self.audio_url is None and b.reference_type == ReferenceType.REFERENCE_AUDIO and b.audio_url:
                        self.audio_url = b.audio_url

            # 5. Multi-node routing
            if self.node_id is None and "node_id" in self.extra_body:
                self.node_id = str(self.extra_body["node_id"])

            if self.target_backend is None:
                tb = self.extra_body.get("target_backend") or self.extra_body.get("backend") or self.extra_body.get("backend_url")
                if tb is not None:
                    self.target_backend = str(tb)

            if self.prompt_format is None:
                pf = self.extra_body.get("prompt_format") or self.extra_body.get("promptFormat")
                if pf is not None:
                    self.prompt_format = str(pf).strip()

        # Also check references outside extra_body for first_frame / last_frame / audio_url
        if self.references:
            for b in self.references:
                if self.first_frame is None and b.reference_type == ReferenceType.FIRST_FRAME and b.images:
                    self.first_frame = b.images[0]
                elif self.last_frame is None and b.reference_type == ReferenceType.LAST_FRAME and b.images:
                    self.last_frame = b.images[0]
                elif self.audio_url is None and b.reference_type == ReferenceType.REFERENCE_AUDIO and b.audio_url:
                    self.audio_url = b.audio_url

        # Sync steps with inference_steps if either is set
        if self.steps is None and self.inference_steps is not None:
            self.steps = self.inference_steps
        elif self.inference_steps is None and self.steps is not None:
            self.inference_steps = self.steps

        return self

    def calculate_minimax_length(self, default_duration: float = 15.0) -> int:
        """
        Calculate MiniMax H3 required frame count aligned to (frames - 5) % 17 == 0.
        """
        if self.length is not None and self.length > 0:
            return self.length
        d = self.duration if self.duration is not None and self.duration > 0 else default_duration
        raw_frames = max(5, round(d * 24))
        aligned_frames = raw_frames + (5 - (raw_frames % 17)) % 17
        return aligned_frames

    def parse_dimensions(self, multiple: int = 16) -> Tuple[int, int]:
        """
        Parse resolution string into (width, height).

        Dimensions must already satisfy the target model's alignment requirement.
        Reject invalid input instead of silently changing the requested output size.
        """
        cleaned = self.size.strip().lower()
        ratio_presets = {
            "16:9": (1344, 768) if multiple == 32 else (1280, 720),
            "9:16": (768, 1344) if multiple == 32 else (720, 1280),
            "1:1": (1024, 1024),
            "4:3": (1152, 864),
            "3:4": (864, 1152)
        }
        if cleaned in ratio_presets:
            width, height = ratio_presets[cleaned]
        else:
            match = re.match(r"^(\d+)[x\*](\d+)$", cleaned)
            if match:
                width = int(match.group(1))
                height = int(match.group(2))
            else:
                raise ValueError(
                    f"Invalid size '{self.size}'. Expected WIDTHxHEIGHT (for example 960x544) "
                    f"or a supported aspect-ratio preset."
                )

        if width < multiple or height < multiple:
            raise ValueError(f"Invalid size '{self.size}'. Width and height must be at least {multiple} pixels.")
        if width % multiple != 0 or height % multiple != 0:
            raise ValueError(
                f"Invalid size '{self.size}' for model '{self.model}'. Width and height must both be "
                f"multiples of {multiple}; the gateway will not silently resize the requested output."
            )
        return width, height

    def get_all_ref_images(self) -> List[str]:
        """Return non-empty list of reference image paths/URLs"""
        images = []
        if self.ref_images:
            images.extend([img.strip() for img in self.ref_images if isinstance(img, str) and img.strip()])
        elif self.references:
            for b in self.references:
                if b.reference_type not in (ReferenceType.FIRST_FRAME, ReferenceType.LAST_FRAME):
                    if b.images:
                        for img in b.images:
                            if img and isinstance(img, str) and img.strip() and img.strip() not in images:
                                images.append(img.strip())

        if self.first_frame and isinstance(self.first_frame, str) and self.first_frame.strip() and self.first_frame.strip() not in images:
            images.append(self.first_frame.strip())
        if self.last_frame and isinstance(self.last_frame, str) and self.last_frame.strip() and self.last_frame.strip() not in images:
            images.append(self.last_frame.strip())
        return images

    def get_all_ref_audios(self) -> List[str]:
        """Return non-empty list of reference audio paths/URLs (up to 3 for MiniMax H3)"""
        audios: List[str] = []
        if self.references:
            for b in self.references:
                if b.reference_type == ReferenceType.REFERENCE_AUDIO and b.audio_url:
                    a = str(b.audio_url).strip()
                    if a and a not in audios:
                        audios.append(a)
        if self.audio_url and isinstance(self.audio_url, str):
            a = self.audio_url.strip()
            if a and a not in audios:
                audios.append(a)
        return audios[:3]

    def validate_minimax_h3_limits(self) -> None:
        """Validate input parameters against MiniMax H3 official constraints"""
        ref_imgs = self.get_all_ref_images()
        ref_auds = self.get_all_ref_audios()
        if len(ref_imgs) > 9:
            raise ValueError(f"MiniMax H3 allows at most 9 reference images, but {len(ref_imgs)} were provided.")
        if len(ref_auds) > 3:
            raise ValueError(f"MiniMax H3 allows at most 3 reference audios, but {len(ref_auds)} were provided.")
        if len(ref_imgs) + len(ref_auds) > 12:
            raise ValueError(f"MiniMax H3 allows at most 12 mixed reference assets, but {len(ref_imgs) + len(ref_auds)} were provided.")
        if ref_auds and not ref_imgs and not self.video_url:
            raise ValueError("MiniMax H3 does not allow reference audio as the sole reference input. At least one reference image or video is required.")
        if self.duration is not None and (self.duration < 4.0 or self.duration > 15.0):
            # Log warning or clamp if appropriate, MiniMax H3 video duration is typically 4-15s
            pass


class ImageData(BaseModel):
    url: Optional[str] = Field(default=None, description="Public URL to access the generated image/video")
    b64_json: Optional[str] = Field(default=None, description="Base64-encoded string if requested")
    revised_prompt: Optional[str] = Field(default=None, description="Revised prompt if any")


class ImageGenerationResponse(BaseModel):
    created: int = Field(default_factory=lambda: int(time.time()), description="Unix timestamp")
    data: List[ImageData] = Field(default_factory=list, description="Array of generated media objects")


class ModelCard(BaseModel):
    id: str
    object: str = "model"
    created: int = Field(default_factory=lambda: int(time.time()))
    owned_by: str = "comfy-gateway"
    node_id: Optional[str] = None


class ModelListResponse(BaseModel):
    object: str = "list"
    data: List[ModelCard]


class NodeCard(BaseModel):
    node_id: str
    name: str
    enabled: bool
    http_url: str
    models: List[str]
    is_healthy: Optional[bool] = None


class NodeListResponse(BaseModel):
    object: str = "list"
    data: List[NodeCard]


class TaskCancelRequest(BaseModel):
    task_id: Optional[str] = Field(default=None, description="Task identifier")
    shot_id: Optional[str] = Field(default=None, description="Shot identifier")
    node_id: Optional[str] = Field(default=None, description="Optional node identifier")
    prompt_id: Optional[str] = Field(default=None, description="Optional ComfyUI prompt identifier")


class TaskCancelResponse(BaseModel):
    success: bool
    message: str
    detail_status: Optional[str] = Field(default=None, description="Detailed cancel status")
    interrupted_nodes: List[str] = Field(default_factory=list)
    cancelled_prompt_ids: List[str] = Field(default_factory=list)
