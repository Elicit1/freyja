import copy
import json
import logging
import random
from pathlib import Path
from typing import Dict, Any, List, Optional
from config import GatewayConfig, ModelConfig, HandlerConfig, get_base_dir
from schemas import ImageGenerationRequest

logger = logging.getLogger("workflow_engine")


def _set_nested_value(data: dict, path: List[str], value: Any) -> None:
    """Set value in a nested dictionary given a key path like ['3', 'inputs', 'text']"""
    if not path:
        return
    curr = data
    for p in path[:-1]:
        if p not in curr or not isinstance(curr[p], dict):
            curr[p] = {}
        curr = curr[p]
    curr[path[-1]] = value


def _find_node_by_class(workflow: dict, class_types: List[str]) -> Optional[str]:
    """Find a node ID by its class_type in the workflow graph"""
    for nid, node in workflow.items():
        if isinstance(node, dict) and node.get("class_type") in class_types:
            return nid
    return None


class WorkflowEngine:
    """
    Config-Driven Workflow Engine.
    Dynamically loads workflow JSON templates and injects runtime request parameters,
    supporting FLUX.2 Klein, MiniMax H3, and standard models.
    Prompt generation is decoupled and received directly from the upstream caller.
    """

    def __init__(self, config: GatewayConfig):
        self.config = config
        self._template_cache: Dict[str, dict] = {}
        self._base_dir = get_base_dir()

    def get_template(self, model_cfg: ModelConfig) -> dict:
        """Load and cache workflow template from disk"""
        template_path = Path(model_cfg.template_file)
        if not template_path.is_absolute():
            template_path = (self._base_dir / template_path).resolve()

        cache_key = str(template_path)
        if cache_key in self._template_cache:
            return copy.deepcopy(self._template_cache[cache_key])

        if not template_path.exists():
            raise FileNotFoundError(f"Workflow template file not found: {template_path}")

        with open(template_path, "r", encoding="utf-8") as f:
            template = json.load(f)

        self._template_cache[cache_key] = template
        return copy.deepcopy(template)

    def assemble_workflow(
        self,
        request: ImageGenerationRequest,
        uploaded_ref_images: Optional[List[str]] = None,
        uploaded_video: Optional[str] = None,
        uploaded_audio: Optional[str] = None,
        uploaded_ref_audios: Optional[List[str]] = None
    ) -> dict:
        """
        Assemble executable ComfyUI prompt JSON from request, reference images,
        and uploaded media (video/audio).
        """
        model_cfg = self.config.get_model(request.model)
        if not model_cfg:
            available = [m.model_name for m in self.config.models]
            raise ValueError(f"Model '{request.model}' not supported. Available models: {available}")

        # 1. Load base template
        workflow = self.get_template(model_cfg)

        # 2. Extract dimensions
        is_minimax = "minimax" in model_cfg.model_name.lower() or "minimax" in model_cfg.handler.lower()
        dimension_multiple = 32 if is_minimax else 16
        width, height = request.parse_dimensions(multiple=dimension_multiple)
        logger.info(
            "[Resolution] model=%s requested=%s effective=%sx%s alignment=%s",
            request.model, request.size, width, height, dimension_multiple
        )

        # 3. Prompt handling (transparent pass-through from upstream Java service)
        final_prompt = request.prompt
        setattr(request, "_revised_prompt", None)

        logger.info(
            f"\n==================== [PROMPT ASSEMBLE RESULT] ====================\n"
            f"Shot ID : {request.shot_id or 'N/A'}\n"
            f"Model   : {request.model}\n"
            f"Has Ref : {bool(request.references)} (count={len(request.references) if request.references else 0})\n"
            f"Prompt  : {final_prompt}\n"
            f"=================================================================="
        )

        # 4. Extract seed
        seed = request.seed
        if seed is None or seed < 0:
            seed = random.randint(1, 9007199254740991)

        # 5. Extract steps and cfg
        steps = request.steps if request.steps is not None else model_cfg.default_steps
        cfg = request.cfg if request.cfg is not None else model_cfg.default_cfg

        # 6. Inject parameters according to mappings
        mappings = model_cfg.mappings or {}

        if "prompt" in mappings and final_prompt:
            _set_nested_value(workflow, mappings["prompt"], final_prompt)

        if "negative_prompt" in mappings:
            neg_prompt = request.negative_prompt or ""
            _set_nested_value(workflow, mappings["negative_prompt"], neg_prompt)

        if "width" in mappings:
            _set_nested_value(workflow, mappings["width"], width)

        if "height" in mappings:
            _set_nested_value(workflow, mappings["height"], height)

        if "length" in mappings:
            length = request.calculate_minimax_length()
            _set_nested_value(workflow, mappings["length"], length)

        if "seed" in mappings:
            _set_nested_value(workflow, mappings["seed"], seed)

        if "steps" in mappings and steps is not None:
            _set_nested_value(workflow, mappings["steps"], steps)

        if "cfg" in mappings and cfg is not None:
            _set_nested_value(workflow, mappings["cfg"], cfg)

        if "video" in mappings and uploaded_video:
            _set_nested_value(workflow, mappings["video"], uploaded_video)

        if "audio" in mappings and uploaded_audio:
            _set_nested_value(workflow, mappings["audio"], uploaded_audio)

        # Allow extra_body to dynamically override any mapped parameter
        if request.extra_body and isinstance(request.extra_body, dict):
            for extra_key, extra_val in request.extra_body.items():
                if extra_key in mappings and extra_val is not None:
                    _set_nested_value(workflow, mappings[extra_key], extra_val)
                    logger.info(f"Dynamically mapped extra_body parameter: {extra_key} = '{extra_val}'")

            if request.extra_body.get("preserve_audio") is False and "output_audio" in mappings:
                audio_path = mappings["output_audio"]
                output_node = workflow.get(audio_path[0], {})
                if len(audio_path) == 3 and audio_path[1] == "inputs":
                    output_node.get("inputs", {}).pop(audio_path[2], None)

        # 7. Dispatch to specific handler for custom node graph modifications
        ref_images = uploaded_ref_images or []
        handler_name = model_cfg.handler.lower().strip()

        if handler_name == "flux2_reference_chain":
            self._apply_flux2_reference_chain(workflow, model_cfg, ref_images)
        elif handler_name == "minimax_h3_fl2va":
            self._apply_minimax_h3_fl2va(workflow, model_cfg, ref_images, request)
        elif handler_name == "minimax_h3_ref2va":
            self._apply_minimax_h3_ref2va(
                workflow=workflow,
                model_cfg=model_cfg,
                uploaded_ref_images=ref_images,
                request=request,
                uploaded_audio=uploaded_audio,
                uploaded_ref_audios=uploaded_ref_audios
            )
        elif handler_name == "minimax_h3_face_refine":
            self._apply_minimax_h3_ref2va(
                workflow, model_cfg, ref_images, request, uploaded_audio, uploaded_ref_audios
            )
            refine_inputs = workflow["158"]["inputs"]
            for key in list(refine_inputs):
                if key.startswith("ref_images.ref_image_"):
                    del refine_inputs[key]
            for idx, filename in enumerate(ref_images[:5]):
                node_id = f"158_ref_img_{idx}"
                workflow[node_id] = {
                    "class_type": "LoadImage", "inputs": {"image": filename}
                }
                refine_inputs[f"ref_images.ref_image_{idx}"] = [node_id, 0]
            # The second pass conditions on the audio generated by the first pass.
            refine_inputs["ref_audios.ref_audio_0"] = ["121", 0]
            for key in list(refine_inputs):
                if key.startswith("ref_audios.ref_audio_") and key != "ref_audios.ref_audio_0":
                    del refine_inputs[key]
            refine_inputs["prompt"] = final_prompt
        elif handler_name == "standard":
            self._apply_standard_handler(workflow, model_cfg, ref_images)
        else:
            logger.warning(f"Unknown handler '{handler_name}', skipping custom node modifications.")

        return workflow

    def _apply_flux2_reference_chain(
        self,
        workflow: dict,
        model_cfg: ModelConfig,
        uploaded_ref_images: List[str]
    ) -> None:
        """
        Dynamically modify node graph for FLUX.2 Klein 9B reference images.
        """
        handler_cfg = model_cfg.handler_config or HandlerConfig()
        sampler_id = handler_cfg.sampler_node_id or "7"
        cond_param = handler_cfg.sampler_cond_param or "positive"
        base_cond = handler_cfg.base_conditioning_source or ["4", 0]
        vae_id = handler_cfg.vae_node_id or "8"
        ref_node_class = handler_cfg.reference_node_type or "ReferenceLatent"

        if sampler_id not in workflow:
            logger.error(f"Sampler node ID '{sampler_id}' not found in workflow template.")
            return

        if not uploaded_ref_images:
            logger.info("[FLUX2 Chain] 0 reference images provided: pure text-to-image mode.")
            workflow[sampler_id]["inputs"][cond_param] = base_cond
            return

        logger.info(f"[FLUX2 Chain] Injecting {len(uploaded_ref_images)} reference images into pipeline...")
        current_cond = base_cond

        for idx, img_filename in enumerate(uploaded_ref_images, start=1):
            load_node_id = f"10{idx}1"
            vae_encode_id = f"10{idx}2"
            ref_node_id = f"10{idx}3"

            workflow[load_node_id] = {
                "inputs": {"image": img_filename},
                "class_type": "LoadImage",
                "_meta": {"title": f"Ref Image Loader #{idx} ({img_filename})"}
            }

            workflow[vae_encode_id] = {
                "inputs": {
                    "pixels": [load_node_id, 0],
                    "vae": [vae_id, 0]
                },
                "class_type": "VAEEncode",
                "_meta": {"title": f"VAE Encode Ref #{idx}"}
            }

            workflow[ref_node_id] = {
                "inputs": {
                    "conditioning": current_cond,
                    "latent": [vae_encode_id, 0]
                },
                "class_type": ref_node_class,
                "_meta": {"title": f"Reference Latent Chain #{idx}"}
            }

            current_cond = [ref_node_id, 0]

        workflow[sampler_id]["inputs"][cond_param] = current_cond
        logger.info(f"[FLUX2 Chain] Successfully wired {len(uploaded_ref_images)} ref nodes to Sampler {sampler_id}.inputs.{cond_param}")

    def _apply_standard_handler(
        self,
        workflow: dict,
        model_cfg: ModelConfig,
        uploaded_ref_images: List[str]
    ) -> None:
        """Standard handler for base workflows without chaining"""
        if uploaded_ref_images:
            logger.info(f"[Standard Handler] {len(uploaded_ref_images)} reference images ignored for standard T2I workflow.")

    def _apply_minimax_h3_fl2va(
        self,
        workflow: dict,
        model_cfg: ModelConfig,
        uploaded_ref_images: List[str],
        request: ImageGenerationRequest
    ) -> None:
        # Dynamically resolve conditioning node ID from mappings or class_type
        cond_node_id = None
        if model_cfg.mappings and "prompt" in model_cfg.mappings:
            cond_node_id = model_cfg.mappings["prompt"][0]
        if not cond_node_id or cond_node_id not in workflow:
            cond_node_id = _find_node_by_class(workflow, ["MiniMaxVideoConditioning", "MiniMaxVideoRefConditioning"])
        if not cond_node_id or cond_node_id not in workflow:
            cond_node_id = "136"

        if cond_node_id not in workflow:
            logger.error(f"MiniMaxH3 conditioning node '{cond_node_id}' not found in workflow template.")
            return

        cond_inputs = workflow[cond_node_id].get("inputs", {})
        first_img = None
        last_img = None

        if len(uploaded_ref_images) == 1:
            first_img = uploaded_ref_images[0]
        elif len(uploaded_ref_images) >= 2:
            first_img = uploaded_ref_images[0]
            last_img = uploaded_ref_images[1]

        first_node_id = f"{cond_node_id}_first_frame"
        last_node_id = f"{cond_node_id}_last_frame"

        if first_img:
            workflow[first_node_id] = {
                "inputs": {"image": first_img},
                "class_type": "LoadImage",
                "_meta": {"title": f"First Frame ({first_img})"}
            }
            cond_inputs["first_frame"] = [first_node_id, 0]
            logger.info(f"[MiniMax FL2VA] Connected first_frame to LoadImage {first_node_id} ({first_img})")
        else:
            cond_inputs.pop("first_frame", None)
            workflow.pop(first_node_id, None)

        if last_img:
            workflow[last_node_id] = {
                "inputs": {"image": last_img},
                "class_type": "LoadImage",
                "_meta": {"title": f"Last Frame ({last_img})"}
            }
            cond_inputs["last_frame"] = [last_node_id, 0]
            logger.info(f"[MiniMax FL2VA] Connected last_frame to LoadImage {last_node_id} ({last_img})")
        else:
            cond_inputs.pop("last_frame", None)
            workflow.pop(last_node_id, None)

    def _apply_minimax_h3_ref2va(
        self,
        workflow: dict,
        model_cfg: ModelConfig,
        uploaded_ref_images: List[str],
        request: ImageGenerationRequest,
        uploaded_audio: Optional[str] = None,
        uploaded_ref_audios: Optional[List[str]] = None
    ) -> None:
        # Dynamically resolve conditioning node ID from mappings or class_type
        cond_node_id = None
        if model_cfg.mappings and "prompt" in model_cfg.mappings:
            cond_node_id = model_cfg.mappings["prompt"][0]
        if not cond_node_id or cond_node_id not in workflow:
            cond_node_id = _find_node_by_class(workflow, ["MiniMaxVideoRefConditioning", "MiniMaxVideoConditioning"])
        if not cond_node_id or cond_node_id not in workflow:
            cond_node_id = "136"

        if cond_node_id not in workflow:
            logger.error(f"MiniMaxH3 reference node '{cond_node_id}' not found in workflow template.")
            return

        cond_inputs = workflow[cond_node_id].get("inputs", {})
        keys_to_delete = [
            k for k in cond_inputs.keys()
            if k.startswith("ref_images.ref_image_") or (k.startswith("ref_image_") and k[len("ref_image_"):].isdigit())
        ]
        for k in keys_to_delete:
            cond_inputs.pop(k, None)

        # Clear existing dynamic ref image nodes
        existing_ref_keys = [k for k in workflow.keys() if str(k).startswith(f"{cond_node_id}_ref_img_") or (str(k).startswith("137") and len(str(k)) > 3)]
        for k in existing_ref_keys:
            workflow.pop(k, None)

        if not uploaded_ref_images:
            logger.warning("[MiniMax Ref2VA] 0 reference images provided for Ref2VA workflow.")
        else:
            for idx, img_filename in enumerate(uploaded_ref_images):
                load_node_id = f"{cond_node_id}_ref_img_{idx}"
                workflow[load_node_id] = {
                    "inputs": {"image": img_filename},
                    "class_type": "LoadImage",
                    "_meta": {"title": f"Ref Image #{idx} ({img_filename})"}
                }
                slot_name = f"ref_images.ref_image_{idx}"
                cond_inputs[slot_name] = [load_node_id, 0]
                logger.info(f"[MiniMax Ref2VA] Connected {slot_name} to LoadImage {load_node_id} ({img_filename})")

        # Handle Reference Audio (up to 3 audios for MiniMax H3: ref_audios.ref_audio_0..2)
        audio_keys_to_delete = [
            k for k in cond_inputs.keys()
            if k.startswith("ref_audios.ref_audio_") or (k.startswith("ref_audio_") and k[len("ref_audio_"):].isdigit())
        ]
        for k in audio_keys_to_delete:
            cond_inputs.pop(k, None)

        existing_aud_keys = [k for k in workflow.keys() if str(k).startswith(f"{cond_node_id}_ref_aud_") or (str(k).startswith("147") and len(str(k)) > 3)]
        for k in existing_aud_keys:
            workflow.pop(k, None)

        audios_to_connect = uploaded_ref_audios if uploaded_ref_audios else ([uploaded_audio] if uploaded_audio else [])
        for a_idx, aud_filename in enumerate(audios_to_connect[:3]):
            load_audio_id = f"{cond_node_id}_ref_aud_{a_idx}"
            workflow[load_audio_id] = {
                "inputs": {"audio": aud_filename},
                "class_type": "LoadAudio",
                "_meta": {"title": f"Ref Audio #{a_idx} ({aud_filename})"}
            }
            slot_name = f"ref_audios.ref_audio_{a_idx}"
            cond_inputs[slot_name] = [load_audio_id, 0]
            logger.info(f"[MiniMax Ref2VA] Connected {slot_name} to LoadAudio {load_audio_id} ({aud_filename})")

        if not audios_to_connect:
            workflow.pop("147", None)
