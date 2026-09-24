import os
import time
import uuid
import base64
import asyncio
import logging
from contextlib import asynccontextmanager
from typing import List, Optional, Dict, Tuple, Any

from fastapi import FastAPI, Request, HTTPException, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
import uvicorn
import aiofiles

from config import get_config, GatewayConfig, NodeConfig
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
from comfy_client import ComfyAsyncClient, ComfyClientPool
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
    return JSONResponse(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, content={"detail": errors})


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
                "code": "backend_failure"
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
                "code": "task_timeout"
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

    # 1. Resolve target model configuration
    model_cfg = config.get_model(request.model)
    if not model_cfg:
        available = [m.model_name for m in config.models]
        raise ValueError(f"Model '{request.model}' not supported. Available models: {available}")

    # 2. Resolve target compute node / ComfyUI client
    # Priority:
    #   a) Direct target_backend URL in request (e.g. "http://192.168.1.120:8188")
    #   b) Preferred node_id from request body or HTTP header (X-Comfy-Node)
    #   c) Standard model-to-node routing via config
    preferred_node = (
        request.node_id or
        http_req.headers.get("x-comfy-node") or
        model_cfg.node_id
    )

    if request.target_backend:
        logger.info(f"Using direct target_backend override: {request.target_backend}")
        client = await pool.get_client_by_url(request.target_backend)
    else:
        target_node = config.get_node_for_model(request.model, preferred_node=preferred_node)
        if not target_node:
            raise RuntimeError(f"No active compute node found for model '{request.model}' (preferred node: '{preferred_node}').")
        logger.info(f"Routing request for model '{request.model}' to node '{target_node.node_id}' ({target_node.http_url})")
        client = await pool.get_client_for_node(target_node)

    # 2.5 MiniMax H3 parameter validation
    is_minimax = "minimax" in model_cfg.model_name.lower() or "minimax" in model_cfg.handler.lower()
    if is_minimax:
        request.validate_minimax_h3_limits()

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

    # 4. Assemble workflow JSON
    prompt_workflow = engine.assemble_workflow(
        request=request,
        uploaded_ref_images=uploaded_ref_names,
        uploaded_video=uploaded_video_name,
        uploaded_audio=uploaded_audio_name,
        uploaded_ref_audios=uploaded_ref_audios
    )

    # Extract the exact prompt submitted to ComfyUI
    extracted_prompt = None
    if "136" in prompt_workflow and "prompt" in prompt_workflow["136"].get("inputs", {}):
        extracted_prompt = prompt_workflow["136"]["inputs"]["prompt"]
    elif "4" in prompt_workflow and "text" in prompt_workflow["4"].get("inputs", {}):
        extracted_prompt = prompt_workflow["4"]["inputs"]["text"]

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
    timeout_override = model_cfg.timeout_seconds or config.server.timeout_seconds

    # 6. Generate client ID and submit prompt to target machine
    client_id = str(uuid.uuid4())
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

    try:
        # 7. Wait for WebSocket execution completion on target machine
        output_asset_meta = await client.wait_for_completion(
            prompt_id=prompt_id,
            client_id=client_id,
            timeout_override=timeout_override
        )

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

        logger.info(f"Returning {len(response_data)} asset(s) to client for model '{request.model}'.")
        return ImageGenerationResponse(
            created=int(time.time()),
            data=response_data
        )
    finally:
        for tk in tracker_keys:
            active_executions.pop(tk, None)
        await task_tracker.unregister(
            prompt_id=prompt_id,
            task_id=getattr(request, "task_id", None),
            shot_id=getattr(request, "shot_id", None)
        )


@app.post("/v1/videos/generations", response_model=ImageGenerationResponse)
async def generate_videos(request: ImageGenerationRequest, http_req: Request):
    """
    OpenAI-compatible Video Generation Endpoint alias.
    Dispatches to multi-node generation pipeline.
    """
    return await generate_images(request, http_req)


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
