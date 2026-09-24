"""
Reference Binding Domain Model and Deterministic Prompt Builder for MiniMax H3.

Transforms structured entity bindings (characters, scenes, props, first/last frame, audio)
into deterministic, natural language reference relation prompts without LLM overhead.
"""

from enum import Enum
from typing import List, Optional, Dict, Any, Union
from pydantic import BaseModel, Field, model_validator


class ReferenceType(str, Enum):
    CHARACTER = "CHARACTER"
    CHARACTER_REFERENCE = "CHARACTER_REFERENCE"
    SCENE = "SCENE"
    PROP = "PROP"
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
    and business domain entities (character, scene, prop, first frame, etc.).
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


class ReferenceRelationPromptBuilder:
    """
    Builder responsible for translating structured ReferenceBinding objects
    into natural language semantic prompts for MiniMax H3.
    Purely deterministic template engine without external LLM latency.
    """

    @classmethod
    def build_relation_prompt(cls, bindings: Optional[List[ReferenceBinding]], language: str = "zh") -> str:
        """
        Build reference relation section prompt from list of ReferenceBindings.
        Returns empty string if bindings is empty or None.
        """
        if not bindings:
            return ""

        # Filter and group bindings by type
        char_bindings: List[ReferenceBinding] = []
        scene_bindings: List[ReferenceBinding] = []
        prop_bindings: List[ReferenceBinding] = []
        first_frame_bindings: List[ReferenceBinding] = []
        last_frame_bindings: List[ReferenceBinding] = []
        audio_bindings: List[ReferenceBinding] = []
        style_bindings: List[ReferenceBinding] = []
        upload_bindings: List[ReferenceBinding] = []
        video_bindings: List[ReferenceBinding] = []

        for b in bindings:
            t = b.reference_type
            if t in (ReferenceType.CHARACTER, ReferenceType.CHARACTER_REFERENCE):
                char_bindings.append(b)
            elif t == ReferenceType.SCENE:
                scene_bindings.append(b)
            elif t == ReferenceType.PROP:
                prop_bindings.append(b)
            elif t == ReferenceType.FIRST_FRAME:
                first_frame_bindings.append(b)
            elif t == ReferenceType.LAST_FRAME:
                last_frame_bindings.append(b)
            elif t == ReferenceType.REFERENCE_AUDIO:
                audio_bindings.append(b)
            elif t == ReferenceType.STYLE:
                style_bindings.append(b)
            elif t == ReferenceType.UPLOAD:
                upload_bindings.append(b)
            elif t == ReferenceType.REFERENCE_VIDEO:
                video_bindings.append(b)

        sections: List[str] = []

        # 1. Characters handling
        char_prompt = cls._build_character_prompt(char_bindings, language=language)
        if char_prompt:
            sections.append(char_prompt)

        # 2. Scenes handling
        scene_prompt = cls._build_scene_prompt(scene_bindings, language=language)
        if scene_prompt:
            sections.append(scene_prompt)

        # 3. Props handling
        prop_prompt = cls._build_prop_prompt(prop_bindings, language=language)
        if prop_prompt:
            sections.append(prop_prompt)

        # 4. First Frame handling (explicitly separated from character)
        if first_frame_bindings:
            sections.append(cls._build_first_frame_prompt(first_frame_bindings, language=language))

        # 5. Last Frame handling
        if last_frame_bindings:
            sections.append(cls._build_last_frame_prompt(last_frame_bindings, language=language))

        # 6. Audio handling (voice identity binding)
        if audio_bindings:
            audio_prompt = cls._build_audio_prompt(audio_bindings, language=language)
            if audio_prompt:
                sections.append(audio_prompt)

        # 7. Style handling
        if style_bindings:
            style_prompt = cls._build_style_prompt(style_bindings, language=language)
            if style_prompt:
                sections.append(style_prompt)

        # UPLOAD is a first-class system reference type. Its intended usage is
        # carried explicitly by usageRole/referenceRole; do not infer a domain
        # type from the file name or prompt text.
        if upload_bindings:
            upload_prompt = cls._build_upload_prompt(upload_bindings, language=language)
            if upload_prompt:
                sections.append(upload_prompt)

        # 8. Video dynamic reference handling
        if video_bindings:
            video_prompt = cls._build_video_prompt(video_bindings, language=language)
            if video_prompt:
                sections.append(video_prompt)

        return "\n\n".join(sections).strip()

    @classmethod
    def _build_character_prompt(cls, bindings: List[ReferenceBinding], language: str = "zh") -> str:
        if not bindings:
            return ""

        # Group by distinct character (entity_name or entity_id)
        # Note: Do not treat multiple images of same character as multiple characters!
        char_groups: Dict[str, List[ReferenceBinding]] = {}
        for b in bindings:
            key = (b.entity_name or b.entity_id or "未命名角色").strip()
            char_groups.setdefault(key, []).append(b)

        distinct_names = list(char_groups.keys())

        # Case A: Single Character
        if len(distinct_names) == 1:
            name = distinct_names[0]
            group = char_groups[name]
            total_images = sum(b.get_image_count() for b in group)

            if total_images > 1 or len(group) > 1:
                # Same character with multiple reference images
                if language == "en":
                    return (
                        f"[CHARACTER REFERENCE]\n\n"
                        f"Multiple character reference images are provided, all corresponding to the character [{name}].\n"
                        f"These reference images jointly serve to maintain [{name}]'s visual consistency and identity across angles and appearances.\n"
                        f"They represent the same single character, not different characters."
                    )
                return (
                    f"【人物参考】\n\n"
                    f"提供的多张人物参考图共同对应角色【{name}】。\n"
                    f"这些参考图共同用于保持{name}的人物身份和视觉一致性。\n"
                    f"它们不是不同角色，而是同一角色的多个视觉参考。"
                )
            else:
                # Single character with single reference image
                if language == "en":
                    return (
                        f"[CHARACTER REFERENCE]\n\n"
                        f"The provided character reference represents [{name}].\n"
                        f"The character appearing in the video as [{name}] should correspond to this reference.\n"
                        f"Maintain [{name}]'s identity and consistent visual appearance throughout the video."
                    )
                return (
                    f"【人物参考】\n\n"
                    f"提供的人物参考图对应角色【{name}】。\n"
                    f"视频中的【{name}】应与该参考人物保持明确的身份对应关系。\n"
                    f"保持{name}的人物身份和主要视觉特征一致。"
                )

        # Case B: Multiple Characters (Must explicitly distinguish roles and avoid swapping)
        if language == "en":
            lines = ["[CHARACTER REFERENCE RELATIONS]\n\nThe provided reference images correspond to the following distinct characters:"]
            for name in distinct_names:
                lines.append(f"- {name}: corresponds to its designated reference image.")
            lines.append("")
            for name in distinct_names:
                lines.append(f"The character [{name}] appearing in the video must maintain consistent identity with [{name}]'s reference.")
            lines.append("Do not swap or confuse the identities of different characters.")
            return "\n".join(lines)

        lines = [
            "【人物参考关系】\n\n"
            "提供的人物参考图分别对应以下角色："
        ]
        for name in distinct_names:
            group = char_groups[name]
            total_imgs = sum(b.get_image_count() for b in group)
            if total_imgs > 1:
                lines.append(f"- {name}：对应其提供的多张人物参考图（同一角色的多视角/多造型视觉参考）。")
            else:
                lines.append(f"- {name}：对应其提供的人物参考图。")

        lines.append("")
        for name in distinct_names:
            lines.append(f"视频中的{name}应保持与{name}参考人物一致。")
        lines.append("不要交换两个角色的身份。")
        return "\n".join(lines)

    @classmethod
    def _build_scene_prompt(cls, bindings: List[ReferenceBinding], language: str = "zh") -> str:
        if not bindings:
            return ""

        scene_names = [b.entity_name for b in bindings if b.entity_name]
        scene_desc = "、".join([f"【{name}】" for name in scene_names]) if scene_names else "指定环境"

        if language == "en":
            name_str = ", ".join(scene_names) if scene_names else "designated environment"
            return (
                f"[SCENE REFERENCE]\n\n"
                f"The provided scene reference corresponds to scene [{name_str}].\n"
                f"The environment in the video must maintain spatial correspondence with this reference.\n"
                f"Maintain consistent spatial structure, environmental features, and overall visual identity."
            )

        return (
            f"【场景参考】\n\n"
            f"提供的场景参考图对应场景{scene_desc}。\n"
            f"当前视频中的环境应与该参考场景保持对应关系。\n"
            f"保持主要空间结构、环境特征和整体视觉身份一致。"
        )

    @classmethod
    def _build_prop_prompt(cls, bindings: List[ReferenceBinding], language: str = "zh") -> str:
        if not bindings:
            return ""

        prop_names = [b.entity_name for b in bindings if b.entity_name]
        prop_desc = "、".join([f"【{name}】" for name in prop_names]) if prop_names else "指定道具"
        prop_plain = "、".join(prop_names) if prop_names else "指定道具"

        if language == "en":
            name_str = ", ".join(prop_names) if prop_names else "designated prop"
            return (
                f"[PROP REFERENCE]\n\n"
                f"The provided prop reference corresponds to [{name_str}].\n"
                f"The prop appearing in the shot must maintain visual correspondence with this reference.\n"
                f"Maintain its primary appearance, shape, and visual identity consistently."
            )

        return (
            f"【道具参考】\n\n"
            f"提供的道具参考图对应道具{prop_desc}。\n"
            f"当前镜头中的{prop_plain}应与该参考道具保持对应关系。\n"
            f"保持其主要外观、形状和视觉身份一致。"
        )

    @classmethod
    def _build_first_frame_prompt(cls, bindings: List[ReferenceBinding], language: str = "zh") -> str:
        if language == "en":
            return (
                "[FIRST FRAME REFERENCE]\n\n"
                "The provided first frame reference defines the initial state of the video.\n"
                "The video must start from the exact character positions, framing composition, poses, and environment depicted in this frame."
            )
        return (
            "【首帧参考】\n\n"
            "提供的首帧参考图定义当前视频的初始画面状态。\n"
            "视频应从该参考图所表达的人物位置、构图、姿态和环境状态开始。"
        )

    @classmethod
    def _build_last_frame_prompt(cls, bindings: List[ReferenceBinding], language: str = "zh") -> str:
        if language == "en":
            return (
                "[LAST FRAME REFERENCE]\n\n"
                "The provided last frame reference defines the ending state of the video.\n"
                "The video should smoothly evolve to reach the composition and scene state depicted in this reference frame."
            )
        return (
            "【尾帧参考】\n\n"
            "提供的尾帧参考图定义当前视频的结束画面状态。\n"
            "视频结束时应逐渐达到该参考图所表达的画面状态。"
        )

    @classmethod
    def _build_audio_prompt(cls, bindings: List[ReferenceBinding], language: str = "zh") -> str:
        if not bindings:
            return ""

        lines = []
        for b in bindings:
            char_name = b.entity_name or b.entity_id
            if char_name:
                if language == "en":
                    lines.append(
                        f"The provided reference audio corresponds to character [{char_name}].\n"
                        f"Dialogue spoken by [{char_name}] in this shot must use this audio as voice identity reference."
                    )
                else:
                    lines.append(
                        f"提供的参考音频对应角色【{char_name}】。\n"
                        f"当前镜头中属于{char_name}的对白应使用该声音作为声音身份参考。"
                    )
            else:
                if language == "en":
                    lines.append("The provided reference audio guides voice tone and dialogue characteristics for this shot.")
                else:
                    lines.append("提供的参考音频用于指引当前镜头的对白语音与声音质感。")

        header = "[AUDIO REFERENCE]" if language == "en" else "【声音参考】"
        return f"{header}\n\n" + "\n\n".join(lines)

    @classmethod
    def _build_style_prompt(cls, bindings: List[ReferenceBinding], language: str = "zh") -> str:
        if not bindings:
            return ""
        if language == "en":
            return (
                "[STYLE REFERENCE]\n\n"
                "The provided style reference defines the overall visual tone and artistic style.\n"
                "Maintain consistent color palette, lighting atmosphere, and rendering style."
            )
        return (
            "【风格参考】\n\n"
            "提供的风格参考图定义当前视频的整体视觉基调和艺术风格。\n"
            "保持视频的色调、光影质感和画面渲染风格与该参考图一致。"
        )

    @classmethod
    def _build_upload_prompt(cls, bindings: List[ReferenceBinding], language: str = "zh") -> str:
        if not bindings:
            return ""
        roles = list(dict.fromkeys(
            b.usage_role or b.reference_role
            for b in bindings
            if b.usage_role or b.reference_role
        ))
        role_text = "、".join(roles)
        if language == "en":
            suffix = f" Their explicitly assigned roles are: {role_text}." if role_text else ""
            return (
                "[UPLOADED REFERENCE]\n\n"
                "The uploaded reference images are authoritative visual references for this shot."
                " Follow only the explicit reference role assigned by the system; do not reinterpret their domain meaning."
                f"{suffix}"
            )
        suffix = f"其系统明确指定的用途为：{role_text}。" if role_text else ""
        return (
            "【上传参考】\n\n"
            "上传参考图是当前镜头的指定视觉参考。仅按照系统明确提供的参考用途使用，禁止根据文件名或文本擅自推断其领域类型。"
            f"{suffix}"
        )

    @classmethod
    def _build_video_prompt(cls, bindings: List[ReferenceBinding], language: str = "zh") -> str:
        if not bindings:
            return ""
        if language == "en":
            return (
                "[MOTION REFERENCE]\n\n"
                "The provided reference video guides motion dynamics and shot pacing.\n"
                "Follow the motion flow, character pacing, and camera movements depicted in the reference."
            )
        return (
            "【动态参考】\n\n"
            "提供的参考视频用于指引动作动态与镜头节奏。\n"
            "当前镜头应参考其动态走势，保持动作节奏与镜头运动规律一致。"
        )

    @classmethod
    def build_continuity_prompt(cls, bindings: Optional[List[ReferenceBinding]], custom_continuity: Optional[str] = None, language: str = "zh") -> str:
        """
        Build continuity constraints section based on bound entities.
        """
        if custom_continuity and custom_continuity.strip():
            return custom_continuity.strip()

        if not bindings:
            return ""

        char_names = list(dict.fromkeys([
            b.entity_name for b in bindings
            if b.reference_type in (ReferenceType.CHARACTER, ReferenceType.CHARACTER_REFERENCE)
            and b.entity_name
        ]))
        scene_props = [b.entity_name for b in bindings if b.reference_type in (ReferenceType.SCENE, ReferenceType.PROP) and b.entity_name]

        rules: List[str] = []
        if char_names:
            names_str = "和".join(char_names)
            rules.append(f"保持{names_str}的人物身份稳定。")
            if len(char_names) >= 2:
                rules.append("不要交换不同角色与其参考素材之间的对应关系。")
        if scene_props:
            rules.append("保持场景和道具的视觉身份稳定。")

        return "\n".join(rules)

    @classmethod
    def assemble_final_prompt(
        cls,
        original_prompt: str,
        bindings: Optional[List[ReferenceBinding]],
        custom_continuity: Optional[str] = None,
        language: str = "zh"
    ) -> str:
        """
        Assemble final multi-section prompt:
        - 【参考素材关系】 (only if bindings exist)
        - 【当前镜头】 (original shot prompt)
        - 【连续性】 (or continuity requirements)

        If bindings is empty or None, original_prompt is returned intact without adding headers.
        """
        if not bindings:
            return original_prompt

        relation_prompt = cls.build_relation_prompt(bindings, language=language)
        if not relation_prompt:
            return original_prompt

        clean_original = original_prompt.strip() if original_prompt else ""
        continuity = cls.build_continuity_prompt(bindings, custom_continuity=custom_continuity, language=language)

        if language == "en":
            parts = [
                f"[REFERENCE RELATIONS]\n\n{relation_prompt}",
                f"[CURRENT SHOT]\n\n{clean_original}"
            ]
            if continuity:
                parts.append(f"[CONTINUITY REQUIREMENTS]\n\n{continuity}")
            return "\n\n".join(parts)

        parts = [
            f"【参考素材关系】\n\n{relation_prompt}",
            f"【当前镜头】\n\n{clean_original}"
        ]
        if continuity:
            parts.append(f"【连续性】\n\n{continuity}")

        return "\n\n".join(parts)
