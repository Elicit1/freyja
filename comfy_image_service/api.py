import hashlib
import hmac
import io
import json
import zipfile
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import APIRouter, Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from starlette.exceptions import HTTPException as StarletteHTTPException

from .comfy import ComfyClient
from .config import Settings
from .media import resolve_image, save_upload
from .models import ASPECT_RATIOS, GenerateRequest, ServiceError
from .registry import Registry
from .runner import Runner
from .store import Store


class BodyLimitMiddleware:
    def __init__(self, app, limit):
        self.app, self.limit = app, limit

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        total = 0
        async def limited_receive():
            nonlocal total
            message = await receive()
            total += len(message.get("body", b""))
            if total > self.limit:
                raise StarletteHTTPException(413, "Request body exceeds the upload limit.")
            return message
        await self.app(scope, limited_receive, send)


def create_app(settings=None, client=None):
    settings = settings or Settings.from_env()
    registry = Registry(settings.apps_dir)

    @asynccontextmanager
    async def lifespan(app):
        app.state.store = Store(settings.data_dir)
        app.state.client = client or ComfyClient(settings)
        app.state.runner = Runner(settings, app.state.store, app.state.client)
        await app.state.runner.start()
        try:
            yield
        finally:
            await app.state.runner.stop()
            await app.state.client.close()
            app.state.store.close()

    app = FastAPI(title="SIF", version="1.0.0", lifespan=lifespan,
                  description="Independent t2i/i2i service. Profiles: pro (INT8), standard (W4A8), fast (Q4_K_M). Persisted asynchronous jobs and hot-discovered app bundles.")
    app.add_middleware(BodyLimitMiddleware, limit=(settings.max_upload_bytes + 2) // 3 * 4 + 65536)
    bearer = HTTPBearer(auto_error=False)

    async def authenticate(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        if len(settings.api_key) < 24 or settings.api_key.startswith("replace-with"):
            raise ServiceError("not_configured", "Configure IMAGE_API_KEY with a random token of at least 24 characters.", 503)
        if credentials is None or not hmac.compare_digest(credentials.credentials.encode(), settings.api_key.encode()):
            raise ServiceError("unauthorized", "A valid Bearer token is required.", 401)

    router = APIRouter(prefix="/v1", dependencies=[Depends(authenticate)])

    @app.exception_handler(ServiceError)
    async def service_error(request, error):
        return JSONResponse({"error": {"code": error.code, "message": error.detail}}, status_code=error.status_code)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, error):
        errors = [{"location": x["loc"], "message": x["msg"], "type": x["type"]} for x in error.errors()]
        return JSONResponse({"error": {"code": "invalid_parameters", "details": errors}}, status_code=422)

    @app.exception_handler(StarletteHTTPException)
    async def http_error(request, error):
        return JSONResponse({"error": {"code": "body_too_large" if error.status_code == 413 else "http_error", "message": error.detail}}, status_code=error.status_code)

    @app.get("/health")
    async def health():
        return {"service": "sif", "worker_alive": app.state.runner.task is not None and not app.state.runner.task.done(),
                "configured": len(settings.api_key) >= 24 and not settings.api_key.startswith("replace-with")}

    @router.get("/apps")
    async def apps():
        registered, errors = registry.scan()
        return {"data": [{"id": manifest.id, "title": manifest.title, "description": manifest.description,
                           "modes": list(manifest.modes), "profiles": {k: {"description": p.description, "loader": p.loader, "model": p.inputs.get("unet_name")} for k, p in manifest.profiles.items()},
                           "revision": revision} for manifest, _, revision in registered.values()], "diagnostics": errors}

    @router.get("/capabilities")
    async def capabilities():
        return {"apps": await apps(), "endpoints": {"t2i": "/v1/t2i", "i2i": "/v1/i2i", "upload": "/v1/uploads", "job": "/v1/jobs/{job_id}"},
                "defaults": {"app": "minimax-h3-image", "tier": "standard", "megapixels": 4, "expand_prompt": True},
                "parameters": GenerateRequest.model_json_schema(), "limits": {"max_megapixels": 20, "max_upload_bytes": settings.max_upload_bytes,
                    "source_formats": ["png", "jpeg", "webp"], "t2i_aspect_ratios": list(ASPECT_RATIOS), "i2i_aspect_ratio": "original"},
                "fixed_sampling": {"steps": 6, "cfg": 1, "sampler": "euler", "scheduler": "simple", "denoise": 1},
                "discovery": "App bundles in apps/*/app.json are re-read on each discovery or submission request. UI App mode alone does not register an API workflow."}

    @router.get("/readiness")
    async def readiness():
        info = await app.state.client.inventory()
        registered, errors = registry.scan()
        checks = []
        for manifest, graphs, _ in registered.values():
            for tier, profile in manifest.profiles.items():
                missing = set()
                for mode, graph in graphs.items():
                    model_node = manifest.modes[mode].model_node
                    for node_id, node in graph.items():
                        kind = profile.loader if node_id == model_node else node["class_type"]
                        values = profile.inputs if node_id == model_node else node["inputs"]
                        if kind not in info:
                            missing.add("node:" + kind)
                            continue
                        fields = info[kind].get("input", {}).get("required", {})
                        for key in ("unet_name", "clip_name", "vae_name"):
                            options = fields.get(key, [[]])[0]
                            if key in values and isinstance(options, list) and values[key] not in options:
                                missing.add("file:" + str(values[key]))
                checks.append({"app": manifest.id, "tier": tier, "ready": not missing, "missing": sorted(missing)})
        return {"ready": bool(checks) and not errors and all(x["ready"] for x in checks), "checks": checks, "diagnostics": errors,
                "note": "Checks node/file availability, not GPU capacity. GGUF H3 Image also requires the packaged author compatibility patch."}

    @router.post("/uploads", status_code=201)
    async def upload(request: Request):
        chunks, total = [], 0
        async for chunk in request.stream():
            total += len(chunk)
            if total > settings.max_upload_bytes:
                raise ServiceError("image_too_large", "Image uploads are limited to 25 MiB.", 413)
            chunks.append(chunk)
        saved = save_upload(b"".join(chunks), settings, app.state.store)
        return {"id": saved["id"], "width": saved["width"], "height": saved["height"], "sha256": saved["sha256"]}

    def public_job(job, request):
        base = settings.public_base_url or str(request.base_url).rstrip("/")
        result = job["result"]
        if result:
            result = {"effective_prompt": result["effective_prompt"], "images": [
                {**{k: v for k, v in image.items() if k != "path"}, "url": f"{base}/v1/jobs/{job['id']}/images/{index}"}
                for index, image in enumerate(result["images"])]}
        return {"id": job["id"], "status": job["status"], "created": job["created"], "updated": job["updated"],
                "request": job["request"], "workflow_revision": job["metadata"]["revision"], "comfy_prompt_id": job["prompt_id"],
                "result": result, "error": job["error"], "poll_url": f"{base}/v1/jobs/{job['id']}"}

    async def submit(mode, body, request, idempotency_key):
        if app.state.runner.task is None or app.state.runner.task.done():
            raise ServiceError("worker_unavailable", "The job worker is unavailable; inspect the service log.", 503)
        if idempotency_key is not None and (not idempotency_key.strip() or len(idempotency_key) > 128):
            raise ServiceError("invalid_idempotency_key", "Idempotency-Key must be 1-128 characters.", 422)
        values = body.normalized(mode)
        upload = resolve_image(values["image"], settings, app.state.store) if mode == "i2i" else None
        if upload:
            values["image"] = upload["id"]
        identity = {**values, "image": upload["sha256"] if upload else None}
        fingerprint = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
        existing = app.state.store.idempotent_job(idempotency_key, fingerprint)
        if existing:
            return public_job(existing, request)
        graph, resolved, metadata = registry.assemble(values)
        job, created = app.state.store.create_job(resolved, graph, metadata, fingerprint, idempotency_key, settings.max_pending_jobs)
        if created:
            await app.state.runner.enqueue(job["id"])
        return public_job(job, request)

    @router.post("/t2i", status_code=202)
    async def t2i(body: GenerateRequest, request: Request, idempotency_key: str | None = Header(default=None)):
        return await submit("t2i", body, request, idempotency_key)

    @router.post("/i2i", status_code=202)
    async def i2i(body: GenerateRequest, request: Request, idempotency_key: str | None = Header(default=None)):
        return await submit("i2i", body, request, idempotency_key)

    @router.get("/jobs")
    async def jobs(request: Request):
        return {"data": [public_job(job, request) for job in app.state.store.jobs()]}

    @router.get("/jobs/{job_id}")
    async def job(job_id: str, request: Request):
        current = app.state.store.get_job(job_id)
        current = await app.state.runner.reconcile(current)
        return public_job(current, request)

    @router.get("/jobs/{job_id}/images/{index}")
    async def image(job_id: str, index: int):
        current = app.state.store.get_job(job_id)
        if current["status"] != "succeeded" or index < 0 or index >= len(current["result"]["images"]):
            raise ServiceError("image_not_found", "The job has no image at this index.", 404)
        path = Path(current["result"]["images"][index]["path"])
        if not path.is_file():
            raise ServiceError("image_not_found", "The stored output file is missing.", 404)
        return FileResponse(path)

    skill_dir = Path(__file__).parent / "skills" / "sif-image-generation"

    @router.get("/skill.zip")
    async def skill_package():
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in skill_dir.rglob("*"):
                if path.is_file() and "__pycache__" not in path.parts:
                    archive.write(path, "sif-image-generation/" + path.relative_to(skill_dir).as_posix())
        return Response(buffer.getvalue(), media_type="application/zip", headers={"Content-Disposition": 'attachment; filename="sif-image-generation.zip"'})

    @router.get("/skill")
    async def skill_entrypoint():
        return Response((skill_dir / "SKILL.md").read_text(encoding="utf-8"), media_type="text/markdown")

    app.include_router(router)
    return app


app = create_app()
