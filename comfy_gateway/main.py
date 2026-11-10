import os
import time
import uuid
import base64
import asyncio
import logging
import hashlib
import json
import secrets
from pathlib import Path
from contextlib import asynccontextmanager
from typing import List, Optional, Dict, Tuple, Any

from fastapi import FastAPI, Request, HTTPException, status
from fastapi.exceptions import RequestValidationError
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse, FileResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
import uvicorn
import aiofiles

from config import get_config, get_base_dir, GatewayConfig, NodeConfig
from video_reference import require_supported, workflow_revision
from reference_media import upload_reference_video
from render_input_validation import validate_transport
from h3_extensions import require_options, revision as h3_revision, inspect_backend
from schemas import (
    ImageGenerationRequest,
    ImageGenerationResponse,
    ImageData,
    ModelListResponse,
    ModelCard,
    NodeListResponse,
    NodeCard,
    TaskCancelRequest,
    TaskCancelResponse
)
from workflow_engine import WorkflowEngine
from comfy_client import ComfyAsyncClient, ComfyClientPool, ComfyExecutionError
from task_tracker import task_tracker

# Configure structured logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [%(name)s] %(message)s"
)
logger = logging.getLogger("gateway")

# Global instances & active execution tracking
config: GatewayConfig = get_config()
workflow_engine: Optional[WorkflowEngine] = None
client_pool: Optional[ComfyClientPool] = None
active_executions: Dict[str, Tuple[ComfyAsyncClient, str, Optional[str]]] = {}


def get_workflow_engine() -> WorkflowEngine:
    global workflow_engine
    if workflow_engine is None:
        workflow_engine = WorkflowEngine(get_config())
    return workflow_engine


def get_client_pool() -> ComfyClientPool:
    global client_pool
    if client_pool is None:
        client_pool = ComfyClientPool(get_config())
    return client_pool


@asynccontextmanager
async def lifespan(app: FastAPI):
    global workflow_engine, client_pool, config
    logger.info("Initializing ComfyUI Multi-Node Universal Gateway...")
    config = get_config()
    workflow_engine = get_workflow_engine()
    client_pool = get_client_pool()

    # Check health across all registered compute nodes
    health_map = await client_pool.check_all_health()
    for node_id, is_ok in health_map.items():
        node = config.get_node(node_id)
        status_icon = "✅ Online" if is_ok else "⚠️ Offline/Unreachable"
        logger.info(f"Node [{node_id}] ({node.name if node else ''}) at {node.http_url if node else ''}: {status_icon}")

    loaded_models = [m.model_name for m in config.models]
    logger.info(f"Loaded {len(loaded_models)} model(s) across {len(config.nodes)} node(s): {loaded_models}")
    logger.info(f"Public outputs directory mounted at: {config.server.output_dir}")

    yield

    logger.info("Shutting down ComfyUI Gateway and closing client connections...")
    if client_pool:
        await client_pool.close_all()


app = FastAPI(
    title="ComfyUI Universal Multi-Node OpenAI Gateway",
    description="Production-grade OpenAI-compatible Image & Video Generation API Gateway with Multi-Machine Support",
    version="1.1.0",
    lifespan=lifespan
)

@app.middleware("http")
async def authenticate_api(request: Request, call_next):
    if request.url.path == "/v1" or request.url.path.startswith("/v1/"):
        token = os.getenv("GATEWAY_API_KEY", "").strip()
        if len(token) < 24:
            return JSONResponse(status_code=503, content={"error": "GATEWAY_API_KEY must contain at least 24 characters"})
        scheme, _, supplied = request.headers.get("authorization", "").partition(" ")
        if scheme.lower() != "bearer" or not secrets.compare_digest(supplied.encode(), token.encode()):
            return JSONResponse(status_code=401, content={"error": "Unauthorized"}, headers={"WWW-Authenticate": "Bearer"})
    return await call_next(request)


# Enable CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Mount static outputs folder to serve generated images & videos
os.makedirs(config.server.output_dir, exist_ok=True)
app.mount("/outputs", StaticFiles(directory=config.server.output_dir), name="outputs")


# Standard OpenAI Error Response Handlers
@app.exception_handler(RequestValidationError)
async def request_validation_error_handler(request: Request, exc: RequestValidationError):
    """Keep FastAPI's 422 contract while logging the exact invalid request path."""
    errors = exc.errors()
    logger.warning(
        "Request validation failed: method=%s path=%s errors=%s",
        request.method,
        request.url.path,
        errors,
    )
    return JSONResponse(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, content=jsonable_encoder({"detail": errors}))


@app.exception_handler(ValueError)
async def value_error_handler(request: Request, exc: ValueError):
    return JSONResponse(
        status_code=status.HTTP_400_BAD_REQUEST,
        content={
            "error": {
                "message": str(exc),
                "type": "invalid_request_error",
                "param": None,
                "code": "invalid_parameters"
            }
        }
    )


@app.exception_handler(RuntimeError)
async def runtime_error_handler(request: Request, exc: RuntimeError):
    return JSONResponse(
        status_code=status.HTTP_502_BAD_GATEWAY,
        content={
            "error": {
                "message": str(exc),
                "type": "comfyui_backend_error",
                "param": None,
                "code": "backend_failure",
                "upstream_status": "FAILED" if isinstance(exc, ComfyExecutionError) else "UNKNOWN",
                "prompt_id": getattr(exc, "prompt_id", None),
                "node_id": getattr(exc, "node_id", None)
            }
        }
    )


@app.exception_handler(TimeoutError)
async def timeout_error_handler(request: Request, exc: TimeoutError):
    return JSONResponse(
        status_code=status.HTTP_504_GATEWAY_TIMEOUT,
        content={
            "error": {
                "message": str(exc),
                "type": "timeout_error",
                "param": None,
                "code": "task_timeout",
                "upstream_status": "UNKNOWN",
                "prompt_id": getattr(exc, "prompt_id", None),
                "node_id": getattr(exc, "node_id", None)
            }
        }
    )


@app.get("/")
async def index():
    return {
        "service": "ComfyUI Universal Multi-Node OpenAI Gateway",
        "version": "1.1.0",
        "documentation": "/docs",
        "nodes_count": len(config.nodes),
        "available_models": [m.model_name for m in config.models]
    }


@app.get("/health")
async def health_check():
    pool = get_client_pool()
    health_map = await pool.check_all_health()
    all_healthy = any(health_map.values()) if health_map else False
    nodes_info = {}
    for nid, ok in health_map.items():
        node = config.get_node(nid)
        nodes_info[nid] = {
            "name": node.name if node else "",
            "http_url": node.http_url if node else "",
            "healthy": ok
        }
    return {
        "status": "healthy" if all_healthy else "degraded",
        "nodes": nodes_info
    }


@app.get("/v1/nodes", response_model=NodeListResponse)
async def list_nodes():
    """List all registered compute nodes and their hosted models"""
    pool = get_client_pool()
    health_map = await pool.check_all_health()

    cards = []
    for nid, node in config.nodes.items():
        models_in_node = [m.model_name for m in node.models]
        cards.append(NodeCard(
            node_id=node.node_id,
            name=node.name or node.node_id,
            enabled=node.enabled,
            http_url=node.http_url,
            models=models_in_node,
            is_healthy=health_map.get(nid, False)
        ))
    return NodeListResponse(data=cards)


@app.get("/v1/models", response_model=ModelListResponse)
async def list_models():
    """List all available models registered across all compute nodes"""
    cards = [
        ModelCard(
            id=m.model_name,
            owned_by="freyja-gateway",
            node_id=m.node_id
        )
        for m in config.models
    ]
    return ModelListResponse(data=cards)


@app.post("/v1/images/generations", response_model=ImageGenerationResponse)
async def generate_images(request: ImageGenerationRequest, http_req: Request):
    http_req.state.submission_started = False
    try:
        return await _generate_images(request, http_req)
    except ValueError:
        raise
    except Exception as error:
        if request.dynamic_workflow and not http_req.state.submission_started:
            raise ValueError("DYNAMIC_PREPARATION_FAILED: " + str(error)) from error
        raise


async def _generate_images(request: ImageGenerationRequest, http_req: Request):
    """
    OpenAI-compatible Image & Video Generation Endpoint.
    Dynamically routes to the corresponding compute node machine, uploads media assets,
    submits prompt to that machine's ComfyUI, monitors WebSocket, and returns standardized response.
    """
    engine = get_workflow_engine()
    pool = get_client_pool()

    logger.info(f"Received generation request for model '{request.model}'")
    if request.references:
        logger.info(
            f"[ReferenceBinding] ShotID='{request.shot_id}': Processing {len(request.references)} reference bindings: "
            f"{[{'id': r.reference_id, 'type': r.reference_type.value, 'entity': r.entity_name} for r in request.references]}"
        )

    validate_transport(request)
    # 1. Resolve target model configuration
    video_references = request.get_all_ref_videos()
    model_cfg = config.get_exact_model(request.model, request.node_id) if video_references or request.h3_options or request.dynamic_workflow else config.get_model(request.model)
    if not model_cfg:
        available = [m.model_name for m in config.models]
        raise ValueError(f"Model '{request.model}' not supported. Available models: {available}")

    # 2. Resolve target compute node / ComfyUI client
    # Priority:
    #   a) Preferred node_id from request body or HTTP header (X-Comfy-Node)
    #   b) Standard model-to-node routing via config
    preferred_node = (
        request.node_id or
        http_req.headers.get("x-comfy-node") or
        model_cfg.node_id
    )

    if request.target_backend:
        raise ValueError("Direct backend overrides are forbidden; select a configured node_id")
    target_node = config.get_node_for_model(request.model, preferred_node=preferred_node)
    if not target_node:
        raise RuntimeError(f"No active compute node found for model '{request.model}' (preferred node: '{preferred_node}').")
    logger.info(f"Routing request for model '{request.model}' to node '{target_node.node_id}' ({target_node.http_url})")
    client = await pool.get_client_for_node(target_node)

    if request.dynamic_workflow:
        from dynamic_workflow import require_stage, describe, validate_stage_nodes
        if request.node_id and request.node_id != target_node.node_id: raise ValueError("DYNAMIC_MODEL_NODE_MISMATCH")
        model_cfg = config.get_exact_model(request.model, target_node.node_id)
        if model_cfg is None: raise ValueError("DYNAMIC_MODEL_NODE_MISMATCH")
        require_stage(request, model_cfg, get_base_dir())
        request.node_id = target_node.node_id
        stage = request.dynamic_workflow.stage
        required = describe(model_cfg, get_base_dir())["requiredNodes"].get(stage, [])
        if required:
            http = await client.get_http_client()
            response = await http.get(client.http_base + "/object_info", timeout=10)
            response.raise_for_status()
            validate_stage_nodes(request.dynamic_workflow, describe(model_cfg, get_base_dir()), response.json())
    video_capability = None
    if request.h3_options:
        model_cfg=config.get_exact_model(request.model,target_node.node_id)
        if model_cfg is None:raise ValueError("H3_MODEL_NODE_MISMATCH")
        require_options(request,model_cfg,get_base_dir())
        accepted=await inspect_backend(model_cfg,client)
        if request.h3_options.backend_signature and request.h3_options.backend_signature!=accepted.get("backendSignature"):raise ValueError("H3_BACKEND_SIGNATURE_CHANGED: refresh continuation preview")
        if request.h3_options.continuation_enabled and not accepted.get("continuation"):raise ValueError("H3_CONTINUATION_UNAVAILABLE: required backend nodes are missing or unreachable")
        strategy=request.h3_options.continuity.mode
        if (strategy!="GUIDE" or "continuationModes" in accepted) and not accepted.get("continuationModes",{}).get(strategy,{}).get("available"):
            raise ValueError("H3_STRATEGY_UNAVAILABLE: "+accepted.get("continuationModes",{}).get(strategy,{}).get("reason",strategy))
        if request.h3_options.save_latent and not accepted.get("continuationModes",{}).get("LATENT_REDRAW",{}).get("available"):
            raise ValueError("H3_LATENT_OUTPUT_UNAVAILABLE: install FREYJA H3 node package")
        if request.h3_options.context_windows.enabled and not accepted.get("contextWindows"):raise ValueError("H3_CONTEXT_UNAVAILABLE")
        if request.h3_options.control_net.enabled and not accepted.get("controlNet"):raise ValueError("H3_CONTROL_UNAVAILABLE")
        if request.h3_options.control_net.enabled and request.h3_options.context_windows.enabled and not accepted.get("contextWindowsWithControlNet"):raise ValueError("H3_WINDOW_CONTROL_UNAVAILABLE")
        if request.h3_options.control_net.enabled and request.h3_options.control_net.control_type not in accepted["controlTypes"]:raise ValueError("H3_PREPROCESSOR_UNSUPPORTED")
        request.node_id=target_node.node_id
        if request.h3_options.control_net.enabled and not video_references: raise ValueError("H3_CONTROL_SOURCE_MISSING")
    elif model_cfg.handler in {"minimax_h3_fl2va","minimax_h3_ref2va","minimax_h3_face_refine"} and (request.duration and request.duration>15 or request.length and request.length>362):
        raise ValueError("H3_CONTINUATION_REQUIRED: requests longer than 15 seconds require continuation mode")
    if video_references:
        model_cfg = config.get_exact_model(request.model, target_node.node_id)
        if not model_cfg:
            raise ValueError("REFERENCE_VIDEO_UNSUPPORTED: model is not registered on the selected node")
        video_capability = require_supported(model_cfg, get_base_dir(), target_node.http_url)
        if request.reference_video_revision != workflow_revision(model_cfg, get_base_dir()):
            raise ValueError("REFERENCE_VIDEO_WORKFLOW_MISMATCH: refresh reference-video preflight")
        if request.video_url or request.first_frame or request.last_frame:
            raise ValueError("REFERENCE_VIDEO_UNSUPPORTED: H3 references use video bindings, not video_url or first/last frames")
        if request.prompt_format != "MINIMAX_H3_REF2VA_VIDEO_V1":
            raise ValueError("REFERENCE_VIDEO_WORKFLOW_MISMATCH: expected video reference prompt format")
        if len(video_references) > video_capability.max_videos:
            raise ValueError("REFERENCE_VIDEO_LIMIT_EXCEEDED: too many reference videos")
        total = len(request.get_all_ref_images()) + len(request.get_all_ref_audios()) + len(video_references)
        total += sum(1 for ref in video_references if ref.include_audio)
        if total > video_capability.max_mixed_assets:
            raise ValueError("REFERENCE_VIDEO_LIMIT_EXCEEDED: mixed reference limit exceeded")
        if any(ref.include_audio for ref in video_references) and not video_capability.supports_video_audio:
            raise ValueError("REFERENCE_VIDEO_UNSUPPORTED: source video audio has not been accepted")
        if not await client.check_health():
            raise RuntimeError("COMPUTE_NODE_OFFLINE: selected reference-video node is offline")
        # Pin the exact model configuration for workflow assembly too.
        request.node_id = target_node.node_id

    # 2.5 MiniMax H3 parameter validation
    is_minimax = "minimax" in model_cfg.model_name.lower() or "minimax" in model_cfg.handler.lower()
    if is_minimax:
        request.validate_minimax_h3_limits()
        if model_cfg.model_name in {
            "minimax-h3-face-refine", "minimax-h3-ref", "minimax-h3-ref-turbo4-face-refine"
        }:
            if len(request.get_all_ref_images()) > 5:
                raise ValueError("This MiniMax H3 workflow supports at most 5 reference images.")

    # 3. Process and upload media assets to target compute node
    uploaded_ref_names: List[str] = []
    ref_sources = request.get_all_ref_images()
    if ref_sources:
        logger.info(f"Uploading {len(ref_sources)} reference images to {client.http_base}...")
        upload_tasks = [client.load_and_upload_ref_image(ref) for ref in ref_sources]
        uploaded_ref_names = await asyncio.gather(*upload_tasks)

    uploaded_video_name: Optional[str] = None
    if request.video_url:
        logger.info(f"Uploading input video for lip-sync/video generation to {client.http_base}...")
        uploaded_video_name = await client.load_and_upload_media(request.video_url, default_name="input_video.mp4")

    uploaded_ref_videos = []
    if video_capability:
        hosts = {host.strip().lower() for host in os.getenv("REFERENCE_MEDIA_HOSTS", "").split(",") if host.strip()}
        for binding in video_references:
            control_upload = {}
            if request.h3_options and request.h3_options.control_net.enabled and binding.reference_id == request.h3_options.control_net.reference_id:
                control_upload = {"required_frames":request.h3_options.control_required_frames or request.h3_options.length,
                                  "grayscale":request.h3_options.control_net.control_type=="GRAY"}
            uploaded_ref_videos.append(await asyncio.wait_for(upload_reference_video(client, binding, video_capability, hosts, **control_upload), timeout=120))
            if sum(video["duration"] for video in uploaded_ref_videos) > video_capability.max_total_clip_seconds + len(uploaded_ref_videos) / video_capability.decode_fps:
                raise ValueError("REFERENCE_VIDEO_LIMIT_EXCEEDED: total reference duration exceeds model configuration")

    # Multi-audio upload for MiniMax H3 (up to 3 audios)
    uploaded_ref_audios: List[str] = []
    ref_aud_sources = request.get_all_ref_audios()
    if ref_aud_sources:
        logger.info(f"Uploading {len(ref_aud_sources)} reference audios to {client.http_base}...")
        for a_idx, aud_src in enumerate(ref_aud_sources):
            try:
                uploaded_name = await client.load_and_upload_media(aud_src, default_name=f"ref_audio_{a_idx}.wav")
                uploaded_ref_audios.append(uploaded_name)
            except Exception as e:
                logger.error(f"Failed to upload reference audio #{a_idx} ({aud_src}): {e}")
                raise RuntimeError(f"Reference audio #{a_idx} upload failed: {e}")

    uploaded_audio_name: Optional[str] = None
    if uploaded_ref_audios:
        uploaded_audio_name = uploaded_ref_audios[0]
    elif request.audio_url:
        logger.info(f"Uploading input audio dialogue to {client.http_base}...")
        uploaded_audio_name = await client.load_and_upload_media(request.audio_url, default_name="input_dialogue.wav")
        uploaded_ref_audios = [uploaded_audio_name]

    continuation_filename=None
    if request.h3_options and request.h3_options.continuation:
        from reference_prompt import ReferenceBinding, ReferenceType
        from video_reference import ReferenceVideoCapability
        context=request.h3_options.continuation
        context_cap=ReferenceVideoCapability(max_videos=1,min_clip_seconds=context.frames/24,max_clip_seconds=context.frames/24,max_total_clip_seconds=context.frames/24,max_decoded_frames=context.frames,max_decode_width=4096,max_decode_height=4096)
        hosts={host.strip().lower() for host in os.getenv("REFERENCE_MEDIA_HOSTS", "").split(",") if host.strip()}
        binding=ReferenceBinding(referenceType=ReferenceType.REFERENCE_VIDEO,referenceId="continuation-context",referenceVideo=context.video_url,contentSha256=context.content_sha256)
        uploaded=await upload_reference_video(client,binding,context_cap,hosts)
        continuation_filename=uploaded["filename"]
    latent_filename=None;anchor_filename=None
    if request.h3_options and request.h3_options.latent_context:
        from continuation_artifacts import upload_latent
        context=request.h3_options.latent_context
        latent_filename=await upload_latent(client,context)
        if context.anchor:
            anchor_filename=latent_filename if context.anchor.content_sha256==context.content_sha256 else await upload_latent(client,context.anchor)
    dynamic_source_filename = None
    dynamic_latent_filename = None
    if request.dynamic_workflow:
        from dynamic_workflow import require_stage
        require_stage(request, model_cfg, get_base_dir())
        if request.dynamic_workflow.stage != "BASE":
            from reference_prompt import ReferenceBinding, ReferenceType
            from video_reference import ReferenceVideoCapability
            context = request.dynamic_workflow
            hosts = {host.strip().lower() for host in os.getenv("REFERENCE_MEDIA_HOSTS", "").split(",") if host.strip()}
            cap = ReferenceVideoCapability(max_videos=1, max_clip_seconds=request.length/24,
                max_total_clip_seconds=request.length/24, max_decoded_frames=request.length,
                max_decode_width=4096, max_decode_height=4096, max_download_bytes=1024**3)
            binding = ReferenceBinding(referenceType=ReferenceType.REFERENCE_VIDEO,
                referenceId="dynamic-stage-source", referenceVideo=context.source_url,
                contentSha256=context.source_sha256)
            uploaded = await upload_reference_video(client, binding, cap, hosts)
            dynamic_source_filename = uploaded["filename"]
            if context.source_latent:
                from continuation_artifacts import upload_latent
                dynamic_latent_filename = await upload_latent(client, context.source_latent)
    # 4. Assemble workflow JSON
    prompt_workflow = engine.assemble_workflow(
        request=request,
        uploaded_ref_images=uploaded_ref_names,
        uploaded_video=uploaded_video_name,
        uploaded_audio=uploaded_audio_name,
        uploaded_ref_audios=uploaded_ref_audios,
        uploaded_ref_videos=uploaded_ref_videos,
        continuation_filename=continuation_filename,
        latent_filename=latent_filename,
        anchor_filename=anchor_filename,
        dynamic_source_filename=dynamic_source_filename,
        dynamic_latent_filename=dynamic_latent_filename
    )

    # Extract the exact prompt submitted to ComfyUI
    extracted_prompt = None
    if model_cfg.mappings and "prompt" in model_cfg.mappings:
        prompt_path = model_cfg.mappings["prompt"]
        if len(prompt_path) == 3 and prompt_path[0] in prompt_workflow:
            extracted_prompt = prompt_workflow[prompt_path[0]].get(prompt_path[1], {}).get(prompt_path[2])
    if extracted_prompt is None:
        extracted_prompt = request.prompt

    target_node_id = target_node.node_id if 'target_node' in locals() else 'default'
    logger.info(
        f"\n====================== [COMFY GATEWAY SUBMIT PROMPT] ======================\n"
        f"Shot ID    : {request.shot_id or 'N/A'}\n"
        f"Model      : {request.model}\n"
        f"Target Node: {target_node_id} ({client.http_base})\n"
        f"Ref Images : {uploaded_ref_names}\n"
        f"Ref Audio  : {uploaded_audio_name}\n"
        f"-------------------------- [FINAL ASSEMBLED PROMPT] --------------------------\n"
        f"{extracted_prompt or getattr(request, '_revised_prompt', None) or request.prompt}\n"
        f"=============================================================================="
    )

    # 5. Check model-specific timeout override
    timeout_override = request.execution_timeout_seconds or model_cfg.timeout_seconds or config.server.timeout_seconds

    # 6. Generate client ID and submit prompt to target machine
    client_id = str(uuid.uuid4())
    async_submit = http_req.headers.get("x-freyja-async-submit")=="true"
    if async_submit:
        if not request.task_id: raise ValueError("ASYNC_REQUIRES_EXECUTION_ID")
        redis=task_tracker._redis_client
        if redis is None: raise RuntimeError("H3_TRACKING_UNAVAILABLE: Redis is required")
        canonical=request.model_dump(exclude={"extra_body"})
        if canonical.get("dynamic_workflow") is None: canonical.pop("dynamic_workflow",None)
        for reference in canonical.get("references") or []:
            if reference.get("content_sha256"):reference.pop("video_url",None)
        if canonical.get("dynamic_workflow") and canonical["dynamic_workflow"].get("source_sha256"):canonical["dynamic_workflow"].pop("source_url",None)
        if canonical.get("dynamic_workflow"):
            dynamic = canonical["dynamic_workflow"]
            if dynamic.get("source_latent"):
                dynamic["source_latent"].pop("url", None)
            else:
                for field in ("source_latent", "source_size"): dynamic.pop(field, None)
        if (canonical.get("h3_options") or {}).get("continuation"):canonical["h3_options"]["continuation"].pop("video_url",None)
        if (canonical.get("h3_options") or {}).get("latent_context"):
            canonical["h3_options"]["latent_context"].pop("url",None)
            if canonical["h3_options"]["latent_context"].get("anchor"):canonical["h3_options"]["latent_context"]["anchor"].pop("url",None)
        if canonical.get("h3_options") and canonical["h3_options"].get("schema_version",1)==1:
            # Preserve idempotency hashes for immutable pre-v2 task snapshots.
            for field in ("continuity","save_latent","latent_context","backend_signature"):canonical["h3_options"].pop(field,None)
        fingerprint=hashlib.sha256(json.dumps(canonical,sort_keys=True).encode()).hexdigest()
        key="comfy:submission:"+request.task_id
        claimed=await redis.set(key,fingerprint,nx=True,ex=604800)
        if not claimed:
            http_req.state.submission_started = True  # An earlier invocation may already own a GPU task.
            if await redis.get(key)!=fingerprint: raise ValueError("H3_EXECUTION_CONFLICT")
            binding=await task_tracker.find(task_id=request.task_id)
            if not binding: raise RuntimeError("H3_SUBMISSION_UNCONFIRMED: reconcile original submission")
            return ImageGenerationResponse(execution={"taskId":request.task_id,"promptId":binding[0],"nodeId":binding[1],"status":"QUEUED"})
        http_req.state.submission_started = True
        try:prompt_id=await client.submit_prompt(prompt_workflow,client_id,execution_id=request.task_id)
        except ValueError:
            await redis.delete(key)
            raise
        await task_tracker.register(prompt_id=prompt_id,node_id=target_node_id,task_id=request.task_id,shot_id=request.shot_id,strict=True)
        from render_progress import start
        start(client, prompt_id, client_id, prompt_workflow, timeout_override)
        return ImageGenerationResponse(execution={"taskId":request.task_id,"promptId":prompt_id,"nodeId":target_node_id,"status":"QUEUED"})
    http_req.state.submission_started = True
    prompt_id = await client.submit_prompt(prompt_workflow, client_id)

    # 注册到 TaskTracker (Redis/内存) 与本地活跃任务映射以支持精准远程取消
    await task_tracker.register(
        prompt_id=prompt_id,
        node_id=target_node_id,
        task_id=getattr(request, "task_id", None),
        shot_id=getattr(request, "shot_id", None)
    )
    tracker_keys = [f"prompt:{prompt_id}"]
    if getattr(request, "task_id", None):
        tracker_keys.append(f"task:{request.task_id}")
    if getattr(request, "shot_id", None):
        tracker_keys.append(f"shot:{request.shot_id}")
    for tk in tracker_keys:
        active_executions[tk] = (client, prompt_id, target_node_id)

    completion_state = "UNKNOWN"
    try:
        # 7. Wait for WebSocket execution completion on target machine
        from render_progress import follow
        output_asset_meta = await follow(client, prompt_id, client_id, prompt_workflow, timeout_override)

        # FREYJA can take over result transfer as soon as rendering finishes. Other
        # OpenAI clients keep the existing eager-download response contract.
        if (getattr(http_req.state, "defer_video_output", False)
                and request.task_id and request.response_format == "url"
                and any(Path(a.get("filename", "")).suffix.lower() in {".mp4", ".webm", ".mov", ".mkv"}
                        for a in output_asset_meta)):
            completion_state = "SUCCESS"
            output_url = str(http_req.url_for("get_task_video", task_id=request.task_id))
            logger.info("Video rendered; handing result transfer to client: task=%s prompt=%s", request.task_id, prompt_id)
            return ImageGenerationResponse(created=int(time.time()), data=[ImageData(
                url=output_url, revised_prompt=getattr(request, "_revised_prompt", None))])

        # 8. Retrieve output media bytes and prepare OpenAI response
        response_data: List[ImageData] = []

        for asset_meta in output_asset_meta:
            filename = asset_meta.get("filename")
            subfolder = asset_meta.get("subfolder", "")
            type_name = asset_meta.get("type", "output")

            raw_bytes = await client.fetch_asset_bytes(filename, subfolder, type_name)

            saved_filename = f"{uuid.uuid4().hex[:12]}_{filename}"
            local_filepath = os.path.join(config.server.output_dir, saved_filename)
            async with aiofiles.open(local_filepath, "wb") as f:
                await f.write(raw_bytes)

            revised_prompt = getattr(request, "_revised_prompt", None)

            if request.response_format == "b64_json":
                b64_str = base64.b64encode(raw_bytes).decode("utf-8")
                response_data.append(ImageData(b64_json=b64_str, url=None, revised_prompt=revised_prompt))
            else:
                base_url = config.server.base_url.rstrip('/')
                client_host = http_req.headers.get("host")
                if client_host and ("127.0.0.1" in base_url or "localhost" in base_url):
                    proto = http_req.headers.get("x-forwarded-proto", http_req.url.scheme or "http")
                    base_url = f"{proto}://{client_host}".rstrip('/')

                public_url = f"{base_url}/outputs/{saved_filename}"
                response_data.append(ImageData(url=public_url, b64_json=None, revised_prompt=revised_prompt))

        completion_state = "SUCCESS"
        logger.info(f"Returning {len(response_data)} asset(s) to client for model '{request.model}'.")
        return ImageGenerationResponse(
            created=int(time.time()),
            data=response_data
        )
    except Exception as exc:
        if isinstance(exc, ComfyExecutionError):
            completion_state = "FAILED"
        exc.prompt_id = prompt_id
        exc.node_id = target_node_id
        logger.warning("Generation response interrupted: task=%s prompt=%s node=%s error_type=%s error=%r",
                       request.task_id, prompt_id, target_node_id, type(exc).__name__, exc)
        raise
    finally:
        for tk in tracker_keys:
            active_executions.pop(tk, None)
        # Retain the exact task binding across disconnection/restarts for result recovery.
        await task_tracker.set_status(prompt_id, request.task_id, completion_state)


@app.post("/v1/videos/generations", response_model=ImageGenerationResponse)
async def generate_videos(request: ImageGenerationRequest, http_req: Request):
    """
    OpenAI-compatible Video Generation Endpoint alias.
    Dispatches to multi-node generation pipeline.
    """
    http_req.state.defer_video_output = http_req.headers.get("x-freyja-deferred-video") == "true"
    return await generate_images(request, http_req)


@app.get("/v1/dynamic-workflows/capabilities")
async def dynamic_workflow_capabilities():
    from dynamic_workflow import describe
    return {"schemaVersion": 1, "models": [describe(model, get_base_dir()) for model in config.models]}


@app.get("/v1/models/{model_code}/capabilities")
async def model_capabilities(model_code: str, node_id: Optional[str] = None):
    model = config.get_exact_model(model_code, node_id)
    if not model:
        raise HTTPException(status_code=404, detail="Model is not registered with this exact identifier")
    capability = model.reference_video
    node = config.get_node(model.node_id) if model.node_id else None
    healthy = False
    h3=model.h3_extensions or {}
    h3_public={"revision":h3_revision(model,get_base_dir()) if h3 else None,
               "continuation":bool(h3.get("continuation")),"contextWindows":bool(h3.get("context_windows")),
               "controlNet":bool(h3.get("control_net")),"contextWindowsWithControlNet":bool(h3.get("context_windows_with_control_net"))}
    if node and node.enabled:
        client=await get_client_pool().get_client_for_node(node)
        if h3:
            h3_public.update(await inspect_backend(model,client))
            # inspect_backend already checked both node schemas and system stats.
            healthy="backend-unreachable" not in h3_public.get("missingNodes",[])
        else:
            healthy=await client.check_health()
    if capability is None:
        return {"modelCode": model_code, "nodeId": model.node_id,
                "referenceVideo": {}, "healthy": healthy,"h3":h3_public}
    revision = workflow_revision(model, get_base_dir())
    values = capability.model_dump()
    names = {"schema_version": "schemaVersion", "max_videos": "maxVideos", "min_clip_seconds": "minClipSeconds",
             "max_clip_seconds": "maxClipSeconds", "max_total_clip_seconds": "maxTotalClipSeconds",
             "decode_fps": "decodeFps", "max_decoded_frames": "maxDecodedFrames", "max_decode_width": "maxDecodeWidth",
             "max_decode_height": "maxDecodeHeight", "max_mixed_assets": "maxMixedAssets",
             "supports_video_audio": "supportsVideoAudio", "max_download_bytes": "maxDownloadBytes"}
    public = {names.get(key, key): value for key, value in values.items()
              if key not in ("condition_node_id", "output_node_ids")}
    return {"modelCode": model_code, "nodeId": model.node_id, "workflowRevision": revision,
            "referenceVideo": public, "healthy": healthy,"h3":h3_public}


async def _execute_cancel(
    task_id: Optional[str] = None,
    shot_id: Optional[str] = None,
    prompt_id: Optional[str] = None,
    node_id: Optional[str] = None
) -> TaskCancelResponse:
    logger.info(f"🛑 [CANCEL] Received task cancel request: task_id={task_id}, shot_id={shot_id}, prompt_id={prompt_id}, node_id={node_id}")

    # 1. 优先通过 TaskTracker (Redis/内存) 跨进程定位 prompt_id 与 node_id
    found_target = await task_tracker.find(task_id=task_id, shot_id=shot_id, prompt_id=prompt_id)

    target_prompt_id = prompt_id
    target_node_id = node_id
    if found_target:
        target_prompt_id, target_node_id = found_target
        logger.info(f"🛑 [CANCEL] Located task via TaskTracker: prompt_id={target_prompt_id}, node_id={target_node_id}")

    # 2. 尝试从本地 active_executions 获取已建立连接的 client
    matched_client = None
    if target_prompt_id and f"prompt:{target_prompt_id}" in active_executions:
        matched_client, _, live_node = active_executions[f"prompt:{target_prompt_id}"]
        target_node_id = target_node_id or live_node
    elif task_id and f"task:{task_id}" in active_executions:
        matched_client, target_prompt_id, live_node = active_executions[f"task:{task_id}"]
        target_node_id = target_node_id or live_node
    elif shot_id and f"shot:{shot_id}" in active_executions:
        matched_client, target_prompt_id, live_node = active_executions[f"shot:{shot_id}"]
        target_node_id = target_node_id or live_node

    # 3. 若当前 worker 进程中无 active client 句柄，但定位到了 target_node_id，从 client pool 获取对应节点客户端
    pool = get_client_pool()
    if not matched_client and target_node_id and target_node_id in config.nodes:
        node_cfg = config.nodes[target_node_id]
        matched_client = await pool.get_client_for_node(node_cfg)

    # 4. 若精准定位到了目标节点与 prompt_id，执行真正的精准取消
    if matched_client and target_prompt_id:
        ok, detail = await matched_client.cancel_job(target_prompt_id)
        logger.info(f"🛑 [CANCEL] cancel_job result for prompt {target_prompt_id}: ok={ok}, detail={detail}")
        if ok:
            return TaskCancelResponse(
                success=True,
                message=f"Task cancelled successfully: {detail}",
                detail_status=detail,
                interrupted_nodes=[target_node_id] if target_node_id else [],
                cancelled_prompt_ids=[target_prompt_id]
            )
        else:
            return TaskCancelResponse(
                success=False,
                message=f"Cancel failed: {detail}",
                detail_status=detail,
                interrupted_nodes=[],
                cancelled_prompt_ids=[target_prompt_id]
            )

    # 5. 若指定了任务标识但未找到任何活跃记录，绝不盲目广播，返回明确的 NOT_FOUND
    if task_id or shot_id or prompt_id:
        logger.warning(f"🛑 [CANCEL] No active task found for task_id={task_id}, shot_id={shot_id}, prompt_id={prompt_id}")
        return TaskCancelResponse(
            success=False,
            message="No matching active render task found (may have already completed or was not queued)",
            detail_status="NOT_FOUND",
            interrupted_nodes=[],
            cancelled_prompt_ids=[]
        )

    # 6. 未指定任何任务标识时，提示调用专用 /v1/interrupt
    return TaskCancelResponse(
        success=False,
        message="No task_id or shot_id specified. Use /v1/interrupt for emergency broadcast.",
        detail_status="MISSING_IDENTIFIER",
        interrupted_nodes=[],
        cancelled_prompt_ids=[]
    )


@app.post("/v1/tasks/{task_id}/cancel", response_model=TaskCancelResponse)
async def cancel_task_by_id(task_id: str):
    """Cancel and interrupt render task by task_id"""
    return await _execute_cancel(task_id=task_id)


async def tracked_task_state(task_id: str, prompt_id: Optional[str] = None, node_id: Optional[str] = None):
    if (prompt_id is None)!=(node_id is None): raise HTTPException(status_code=400,detail="Provide both persisted execution identifiers")
    binding = (prompt_id,node_id) if prompt_id and node_id else await task_tracker.find(task_id=task_id)
    if not binding:
        return None, {"task_id": task_id, "status": "UNKNOWN"}
    prompt_id, node_id = binding
    node = config.get_node(node_id)
    if not node:
        return None, {"task_id": task_id, "prompt_id": prompt_id, "node_id": node_id, "status": "UNKNOWN"}
    client = await get_client_pool().get_client_for_node(node)
    state = await client.task_state(prompt_id)
    state.update(task_id=task_id, prompt_id=prompt_id, node_id=node_id)
    progress = await task_tracker.progress(prompt_id)
    if progress is not None:
        state["progress"] = progress
    return client, state


@app.get("/v1/tasks/{task_id}")
async def get_task_state(task_id: str, request: Request, prompt_id: Optional[str] = None, node_id: Optional[str] = None):
    _, state = await tracked_task_state(task_id,prompt_id,node_id) if prompt_id or node_id else await tracked_task_state(task_id)
    assets = state.pop("assets", [])
    videos = [a for a in assets if Path(a.get("filename", "")).suffix.lower() in {".mp4", ".webm", ".mov", ".mkv"}]
    if state["status"] == "SUCCESS":
        if videos:
            state["video_url"] = str(request.url_for("get_task_video", task_id=task_id))
            if prompt_id and node_id:
                from urllib.parse import urlencode
                state["video_url"]+="?"+urlencode({"prompt_id":prompt_id,"node_id":node_id})
        else:
            state["status"] = "UNKNOWN"
    return state


@app.get("/v1/tasks/{task_id}/latent")
async def get_task_latent(task_id: str, prompt_id: Optional[str] = None, node_id: Optional[str] = None):
    client,state=await tracked_task_state(task_id,prompt_id,node_id)
    if state["status"]!="SUCCESS":raise HTTPException(status_code=409,detail="Task completion is not confirmed")
    assets=state.get("latents",[])
    if len(assets)!=1:raise HTTPException(status_code=409,detail="Native H3 latent output is missing; do not rerender automatically")
    from continuation_artifacts import cache_output_latent
    path=await cache_output_latent(client,assets[0],config.server.output_dir,f"{state['node_id']}:{state['prompt_id']}:latent")
    return FileResponse(path,media_type="application/octet-stream")


@app.get("/v1/tasks/{task_id}/output")
async def get_task_video(task_id: str, prompt_id: Optional[str] = None, node_id: Optional[str] = None):
    client, state = await tracked_task_state(task_id,prompt_id,node_id) if prompt_id or node_id else await tracked_task_state(task_id)
    if state["status"] != "SUCCESS":
        raise HTTPException(status_code=409, detail="Task completion is not confirmed")
    videos = [a for a in state.get("assets", []) if Path(a.get("filename", "")).suffix.lower() in {".mp4", ".webm", ".mov", ".mkv"}]
    if not videos:
        raise HTTPException(status_code=409, detail="No completed video output")
    asset = videos[0]
    # Stable cache name and atomic replacement make repeated recovery safe.
    digest = hashlib.sha256(f"{state['node_id']}:{state['prompt_id']}:{asset}".encode()).hexdigest()
    path = Path(config.server.output_dir) / (digest + Path(asset["filename"]).suffix.lower())
    if not path.is_file():
        data = await client.fetch_asset_bytes(asset["filename"], asset.get("subfolder", ""), asset.get("type", "output"))
        temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
        try:
            async with aiofiles.open(temporary, "wb") as output:
                await output.write(data)
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)
    return FileResponse(path, media_type="video/mp4")


@app.post("/v1/tasks/cancel", response_model=TaskCancelResponse)
async def cancel_task_endpoint(req: TaskCancelRequest):
    """Cancel and interrupt render task by TaskCancelRequest payload"""
    return await _execute_cancel(
        task_id=req.task_id,
        shot_id=req.shot_id,
        prompt_id=req.prompt_id,
        node_id=req.node_id
    )


@app.post("/v1/interrupt", response_model=TaskCancelResponse)
async def interrupt_all_nodes():
    """Emergency stop: interrupt all running ComfyUI tasks across all nodes (admin only)"""
    pool = get_client_pool()
    broadcast_res = await pool.interrupt_all()
    interrupted_nodes = [nid for nid, ok in broadcast_res.items() if ok]
    logger.warning(f"🛑 [ADMIN INTERRUPT] Broadcast interrupt executed on nodes: {interrupted_nodes}")
    return TaskCancelResponse(
        success=True,
        message="Emergency broadcast interrupt sent to all compute nodes",
        detail_status="EMERGENCY_INTERRUPT_ALL",
        interrupted_nodes=interrupted_nodes,
        cancelled_prompt_ids=[]
    )


if __name__ == "__main__":
    cfg = get_config()
    uvicorn.run(
        "main:app",
        host=cfg.server.host,
        port=cfg.server.port,
        reload=False
    )
