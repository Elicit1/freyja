"""
Backend Adapter Layer for Video Generation.

Decouples high-level generation requests and ReferenceBindings from specific execution backends:
- Local ComfyUI Adapter (H3 FL2VA / Ref2VA node workflows)
- MiniMax Commercial API Adapter (Subject Reference / Reference Asset translation)
"""

import abc
from typing import Dict, Any, List, Optional
from schemas import ImageGenerationRequest
from reference_prompt import ReferenceBinding, ReferenceRelationPromptBuilder, ReferenceType


class BaseVideoBackendAdapter(abc.ABC):
    """Abstract Base Video Generation Backend Adapter"""

    @abc.abstractmethod
    def prepare_payload(self, request: ImageGenerationRequest) -> Dict[str, Any]:
        """Convert standard request and ReferenceBindings into backend-specific payload"""
        pass


class ComfyUIBackendAdapter(BaseVideoBackendAdapter):
    """
    Local ComfyUI Backend Adapter.
    Translates request and bindings into ComfyUI prompt workflow node graph.
    """

    def __init__(self, workflow_engine=None):
        self.workflow_engine = workflow_engine

    def prepare_payload(
        self,
        request: ImageGenerationRequest,
        uploaded_ref_images: Optional[List[str]] = None,
        uploaded_video: Optional[str] = None,
        uploaded_audio: Optional[str] = None
    ) -> Dict[str, Any]:
        if not self.workflow_engine:
            raise RuntimeError("WorkflowEngine not initialized in ComfyUIBackendAdapter")
        return self.workflow_engine.assemble_workflow(
            request=request,
            uploaded_ref_images=uploaded_ref_images,
            uploaded_video=uploaded_video,
            uploaded_audio=uploaded_audio
        )


class MiniMaxCommercialAdapter(BaseVideoBackendAdapter):
    """
    MiniMax Commercial API Backend Adapter (Video-01 / Subject Reference Compatible).
    Translates ReferenceBinding into MiniMax commercial API format (e.g. subject_reference / first_frame_image).
    """

    def prepare_payload(self, request: ImageGenerationRequest) -> Dict[str, Any]:
        # Generate semantic reference prompt
        final_prompt = ReferenceRelationPromptBuilder.assemble_final_prompt(
            original_prompt=request.prompt,
            bindings=request.references,
            custom_continuity=request.continuity_prompt
        )

        subject_references: List[Dict[str, Any]] = []
        first_frame_image: Optional[str] = request.first_frame

        if request.references:
            for b in request.references:
                if b.reference_type == ReferenceType.FIRST_FRAME:
                    if b.images:
                        first_frame_image = b.images[0]
                elif b.reference_type in (ReferenceType.CHARACTER, ReferenceType.SCENE, ReferenceType.PROP):
                    subject_references.append({
                        "type": b.reference_type.value.lower(),
                        "entity_id": b.entity_id,
                        "entity_name": b.entity_name,
                        "images": b.images or []
                    })

        payload = {
            "model": request.model,
            "prompt": final_prompt,
            "prompt_optimizer": True,
            "subject_reference": subject_references if subject_references else None,
            "first_frame_image": first_frame_image,
            "duration": request.duration or 5.0
        }
        return {k: v for k, v in payload.items() if v is not None}
