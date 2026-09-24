import copy
import json
import logging
import random
from pathlib import Path
from typing import Dict, Any, List, Optional
from config import GatewayConfig, ModelConfig, HandlerConfig, get_base_dir
from schemas import ImageGenerationRequest
from reference_prompt import ReferenceRelationPromptBuilder

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


class WorkflowEngine:
    """
    Config-Driven Workflow Engine.
    Dynamically loads workflow JSON templates and injects runtime request parameters,
    supporting FLUX.2 Klein, MiniMax H3, LatentSync LipSync, and standard models.
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

        # 3. Reference Relation Prompt generation for MiniMax models
        final_prompt = request.prompt
        is_h3_dedicated = getattr(request, "prompt_format", None) in ("MINIMAX_H3_REF2VA_V1", "MINIMAX_H3_FL2VA_V1")
        if is_minimax and request.references and request.enable_reference_prompt and not is_h3_dedicated:
            final_prompt = ReferenceRelationPromptBuilder.assemble_final_prompt(
                original_prompt=request.prompt,
                bindings=request.references,
                custom_continuity=request.continuity_prompt
            )
        elif is_h3_dedicated:
            logger.info(f"[MiniMax H3] Using native dedicated format '{request.prompt_format}', bypassing legacy relation wrapping.")
        # Cache revised_prompt on request object
        setattr(request, "_revised_prompt", final_prompt if final_prompt != request.prompt else None)

        logger.info(
            f"\n==================== [PROMPT ASSEMBLE RESULT] ====================\n"
            f"Shot ID : {request.shot_id or 'N/A'}\n"
            f"Model   : {request.model}\n"
            f"Has Ref : {bool(request.references)} (count={len(request.references) if request.references else 0})\n"
            f"------------------- [ORIGINAL PROMPT] -------------------\n"
            f"{request.prompt}\n"
            f"-------------------- [FINAL PROMPT] ---------------------\n"
            f"{final_prompt}\n"
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

        # 6. LatentSync parameter mappings
        if "video" in mappings and uploaded_video:
            _set_nested_value(workflow, mappings["video"], uploaded_video)

        if "audio" in mappings and uploaded_audio:
            _set_nested_value(workflow, mappings["audio"], uploaded_audio)

        if "inference_steps" in mappings and steps is not None:
            _set_nested_value(workflow, mappings["inference_steps"], steps)

        if "lips_expression" in mappings:
            lips_exp = request.lips_expression if request.lips_expression is not None else 1.5
            _set_nested_value(workflow, mappings["lips_expression"], lips_exp)

        if "vram_usage" in mappings:
            vram_u = request.vram_usage if request.vram_usage is not None else "low"
            _set_nested_value(workflow, mappings["vram_usage"], vram_u)

        # Allow extra_body to dynamically override any mapped parameter
        if request.extra_body and isinstance(request.extra_body, dict):
            for extra_key, extra_val in request.extra_body.items():
                if extra_key in mappings and extra_val is not None:
                    _set_nested_value(workflow, mappings[extra_key], extra_val)
                    logger.info(f"Dynamically mapped extra_body parameter: {extra_key} = '{extra_val}'")

        # 7. Dispatch to specific handler for custom node graph modifications
        ref_images = uploaded_ref_images or []
        handler_name = model_cfg.handler.lower().strip()

        if handler_name == "latentsync_lipsync":
            self._apply_latentsync_lipsync(
                workflow=workflow,
                model_cfg=model_cfg,
                request=request,
                uploaded_video=uploaded_video,
                uploaded_audio=uploaded_audio,
                seed=seed,
                steps=steps
            )
        elif handler_name == "flux2_reference_chain":
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
        elif handler_name == "standard":
            self._apply_standard_handler(workflow, model_cfg, ref_images)
        else:
            logger.warning(f"Unknown handler '{handler_name}', skipping custom node modifications.")

        return workflow

    def _apply_latentsync_lipsync(
        self,
        workflow: dict,
        model_cfg: ModelConfig,
        request: ImageGenerationRequest,
        uploaded_video: Optional[str],
        uploaded_audio: Optional[str],
        seed: int,
        steps: Optional[int]
    ) -> None:
        """
        Apply parameters to LatentSync LipSync workflow:
        - Node 1: VHS_LoadVideo -> inputs.video
        - Node 2: VHS_LoadAudio -> inputs.audio
        - Node 4: GeekyLatentSyncNode -> inputs.seed, inputs.lips_expression, inputs.inference_steps, inputs.vram_usage
        """
        # Node 1: Video input
        if uploaded_video and "1" in workflow:
            workflow["1"].setdefault("inputs", {})["video"] = uploaded_video
            logger.info(f"[LatentSync] Set video input to: {uploaded_video}")

        # Node 2: Audio input
        if uploaded_audio and "2" in workflow:
            workflow["2"].setdefault("inputs", {})["audio"] = uploaded_audio
            logger.info(f"[LatentSync] Set audio input to: {uploaded_audio}")

        # Node 4: GeekyLatentSyncNode
        if "4" in workflow:
            node4_inputs = workflow["4"].setdefault("inputs", {})
            node4_inputs["seed"] = seed
            node4_inputs["lips_expression"] = request.lips_expression if request.lips_expression is not None else 1.5
            node4_inputs["inference_steps"] = steps if steps is not None else 15
            node4_inputs["vram_usage"] = request.vram_usage if request.vram_usage is not None else "low"
            logger.info(
                f"[LatentSync] Configured Node 4: seed={seed}, "
                f"lips_expression={node4_inputs['lips_expression']}, "
                f"inference_steps={node4_inputs['inference_steps']}, "
                f"vram_usage={node4_inputs['vram_usage']}"
            )

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

        if first_img:
            workflow["137"] = {
                "inputs": {"image": first_img},
                "class_type": "LoadImage",
                "_meta": {"title": f"First Frame ({first_img})"}
            }
            cond_inputs["first_frame"] = ["137", 0]
            logger.info(f"[MiniMax FL2VA] Connected first_frame to LoadImage 137 ({first_img})")
        else:
            cond_inputs.pop("first_frame", None)
            workflow.pop("137", None)

        if last_img:
            workflow["139"] = {
                "inputs": {"image": last_img},
                "class_type": "LoadImage",
                "_meta": {"title": f"Last Frame ({last_img})"}
            }
            cond_inputs["last_frame"] = ["139", 0]
            logger.info(f"[MiniMax FL2VA] Connected last_frame to LoadImage 139 ({last_img})")
        else:
            cond_inputs.pop("last_frame", None)
            workflow.pop("139", None)

    def _apply_minimax_h3_ref2va(
        self,
        workflow: dict,
        model_cfg: ModelConfig,
        uploaded_ref_images: List[str],
        request: ImageGenerationRequest,
        uploaded_audio: Optional[str] = None,
        uploaded_ref_audios: Optional[List[str]] = None
    ) -> None:
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

        if not uploaded_ref_images:
            logger.warning("[MiniMax Ref2VA] 0 reference images provided for Ref2VA workflow.")
        else:
            for idx, img_filename in enumerate(uploaded_ref_images):
                load_node_id = f"137{idx}"
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

        audios_to_connect = uploaded_ref_audios if uploaded_ref_audios else ([uploaded_audio] if uploaded_audio else [])
        for a_idx, aud_filename in enumerate(audios_to_connect[:3]):
            load_audio_id = f"147{a_idx}" if a_idx > 0 else "147"
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
