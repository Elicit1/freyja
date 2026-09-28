"""
Reference Binding Domain Model for Comfy Gateway.

Defines ReferenceType and ReferenceBinding for OpenAI-compatible multi-modal
reference assets (characters, scenes, props, keyframes, audio, video).
Prompt generation is handled purely by the caller / upstream Java service.
"""

from enum import Enum
from typing import List, Optional, Dict, Any, Union
from pydantic import BaseModel, Field, model_validator


class ReferenceType(str, Enum):
    CHARACTER = "CHARACTER"
    CHARACTER_REFERENCE = "CHARACTER_REFERENCE"
    SCENE = "SCENE"
    PROP = "PROP"
    KEYFRAME = "KEYFRAME"
    UPLOAD = "UPLOAD"
    STYLE = "STYLE"
    FIRST_FRAME = "FIRST_FRAME"
    LAST_FRAME = "LAST_FRAME"
    REFERENCE_VIDEO = "REFERENCE_VIDEO"
    REFERENCE_AUDIO = "REFERENCE_AUDIO"


class ReferenceBinding(BaseModel):
    """
    Internal Reference Binding Domain Model.
    Represents an explicit association between reference assets (images, audio, video)
    and business domain entities (character, scene, prop, keyframe, etc.).
    Fully compatible with MiniMax Subject Reference / Reference Asset semantics.
    """
    reference_id: Optional[str] = Field(default=None, alias="referenceId", description="Internal unique reference asset ID")
    reference_type: ReferenceType = Field(..., alias="referenceType", description="Type of reference asset")
    entity_id: Optional[str] = Field(default=None, alias="entityId", description="Domain entity ID (e.g. char_001)")
    entity_name: Optional[str] = Field(default=None, alias="entityName", description="Human-readable entity name (e.g. 林默)")
    character_id: Optional[str] = Field(default=None, alias="characterId", description="Owning character ID for a visual look reference")
    look_id: Optional[str] = Field(default=None, alias="lookId", description="Character look ID for a visual look reference")
    role: Optional[str] = Field(default=None, description="Optional role description or character tag")
    usage_role: Optional[str] = Field(default=None, alias="usageRole", description="Explicit usage role supplied by the system")
    reference_role: Optional[str] = Field(default=None, alias="referenceRole", description="Explicit semantic role supplied by the system")

    # Media asset sources
    images: Optional[List[str]] = Field(default=None, alias="referenceImages", description="List of image URLs or file paths")
    audio_url: Optional[str] = Field(default=None, alias="referenceAudio", description="Reference audio URL or file path")
    video_url: Optional[str] = Field(default=None, alias="referenceVideo", description="Reference video URL or file path")

    @model_validator(mode="before")
    @classmethod
    def normalize_fields(cls, data: Any) -> Any:
        if not isinstance(data, dict):
            return data

        # Normalize reference_type alias 'type'
        ref_type = data.get("reference_type") or data.get("referenceType") or data.get("type")
        if ref_type:
            if isinstance(ref_type, str):
                ref_type = ref_type.strip().upper()
            data["referenceType"] = ref_type

        # Normalize reference_id alias 'id'
        ref_id = data.get("reference_id") or data.get("referenceId") or data.get("id")
        if ref_id:
            data["referenceId"] = str(ref_id)

        # Normalize entity_id
        ent_id = data.get("entity_id") or data.get("entityId")
        if ent_id:
            data["entityId"] = str(ent_id)

        # Normalize entity_name alias 'name'
        ent_name = data.get("entity_name") or data.get("entityName") or data.get("name")
        if ent_name:
            data["entityName"] = str(ent_name).strip()

        # Normalize system-provided relationship metadata without inferring semantics.
        for field_name, alias_name in (
            ("character_id", "characterId"),
            ("look_id", "lookId"),
            ("usage_role", "usageRole"),
            ("reference_role", "referenceRole"),
        ):
            value = data.get(field_name) or data.get(alias_name)
            if value is not None:
                data[alias_name] = str(value)

        # Normalize images from 'images', 'referenceImages', 'reference_images', 'image_url', 'image'
        imgs = (
            data.get("images") or
            data.get("referenceImages") or
            data.get("reference_images") or
            data.get("ref_images")
        )
        if imgs is None:
            single_img = data.get("image_url") or data.get("image") or data.get("url")
            if single_img and isinstance(single_img, str):
                imgs = [single_img]
        if isinstance(imgs, str):
            imgs = [imgs]
        elif isinstance(imgs, list):
            imgs = [str(x) for x in imgs if x]
        if imgs is not None:
            data["referenceImages"] = imgs

        # Normalize audio_url
        audio = data.get("audio_url") or data.get("referenceAudio") or data.get("audio")
        if audio:
            data["referenceAudio"] = str(audio)

        # Normalize video_url
        video = data.get("video_url") or data.get("referenceVideo") or data.get("video")
        if video:
            data["referenceVideo"] = str(video)

        return data

    def get_image_count(self) -> int:
        if self.images:
            return len(self.images)
        return 1 if self.reference_id else 0
