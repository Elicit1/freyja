import asyncio
import io
import zipfile
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, FastAPI, Header, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse, Response
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.staticfiles import StaticFiles
from starlette.datastructures import UploadFile

from .admin import mount_admin
from .auth import Auth
from .comfy import ComfyClient
from .config import Settings
from .http_auth import AuthenticationMiddleware, ResponseHeadersMiddleware
from .mcp_server import create_mcp
from .models import GenerateRequest, ServiceError
from .registry import Registry
from .retention import maintain_uploads
from .runner import Runner
from .service import ImageService
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
        app.state.auth = Auth(app.state.store)
        app.state.client = client or ComfyClient(settings)
        app.state.runner = Runner(settings, app.state.store, app.state.client)
        app.state.service = ImageService(settings, app.state.store, registry, app.state.runner, app.state.client)
        try:
            await app.state.runner.start()
            app.state.auth.initialize(settings.initial_password)
            async with app.state.mcp.session_manager.run():
                cleanup_stopped = asyncio.Event()
                cleanup_task = asyncio.create_task(maintain_uploads(app.state.store, app.state.service.storage, cleanup_stopped))
                try:
                    yield
                finally:
                    cleanup_stopped.set()
                    await cleanup_task
        finally:
            await app.state.runner.stop()
            await app.state.client.close()
            app.state.store.close()

    app = FastAPI(title="SIF Server", version="1.3.0", lifespan=lifespan,
                  description="Password-managed API keys, authenticated MCP tools, and a persisted serial t2i/i2i queue.")
    app.add_middleware(BodyLimitMiddleware, limit=(settings.max_upload_bytes + 2) // 3 * 4 + 65536)
    app.add_middleware(AuthenticationMiddleware, state=app.state)
    app.add_middleware(ResponseHeadersMiddleware)
    router = APIRouter(prefix="/v1")

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
                "queue": app.state.store.queue_summary()}

    @router.get("/apps")
    async def apps():
        return app.state.service.apps()

    @router.get("/capabilities")
    async def capabilities():
        return app.state.service.capabilities()

    @router.get("/readiness")
    async def readiness():
        return await app.state.service.readiness()

    @router.post("/uploads", status_code=201)
    async def upload(request: Request):
        if request.headers.get("content-type", "").startswith("multipart/form-data"):
            async with request.form(max_files=1, max_fields=10, max_part_size=settings.max_upload_bytes) as form:
                file = form.get("file")
                if not isinstance(file, UploadFile):
                    raise ServiceError("image_required", "Upload a file in the multipart file field.", 422)
                data = await file.read(settings.max_upload_bytes + 1)
                return await asyncio.to_thread(app.state.service.upload, data)
        chunks, total = [], 0
        async for chunk in request.stream():
            total += len(chunk)
            if total > settings.max_upload_bytes:
                raise ServiceError("image_too_large", "Image uploads are limited to 25 MiB.", 413)
            chunks.append(chunk)
        return await asyncio.to_thread(app.state.service.upload, b"".join(chunks))

    @router.post("/t2i", status_code=202)
    async def t2i(body: GenerateRequest, request: Request, idempotency_key: str | None = Header(default=None)):
        return await app.state.service.submit("t2i", body, idempotency_key, str(request.base_url))

    @router.post("/i2i", status_code=202)
    async def i2i(body: GenerateRequest, request: Request, idempotency_key: str | None = Header(default=None)):
        return await app.state.service.submit("i2i", body, idempotency_key, str(request.base_url))

    @router.get("/queue")
    async def queue(request: Request):
        return app.state.service.queue(str(request.base_url))

    @router.get("/jobs")
    async def jobs(request: Request):
        return {"data": [app.state.service.public_job(job, str(request.base_url)) for job in app.state.store.jobs()]}

    @router.get("/jobs/{job_id}")
    async def job(job_id: str, request: Request):
        return await app.state.service.job(job_id, str(request.base_url))

    @router.get("/jobs/{job_id}/wait")
    async def wait(job_id: str, timeout_seconds: float = Query(default=25, ge=1, le=600)):
        return await app.state.service.wait_for_job(job_id, timeout_seconds)

    @router.get("/jobs/{job_id}/result")
    async def result(job_id: str, request: Request, response_format: Literal["url", "b64_json"] = "url"):
        return await app.state.service.result(job_id, response_format, str(request.base_url))

    @router.get("/jobs/{job_id}/images/{index}")
    async def image(job_id: str, index: int):
        return FileResponse(app.state.service.image_path(job_id, index))

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
    mount_admin(app)
    static = Path(__file__).parent / "web"

    @app.get("/", include_in_schema=False)
    async def console():
        return FileResponse(static / "index.html", media_type="text/html")

    app.mount("/assets", StaticFiles(directory=static, check_dir=False))
    mcp, transport = create_mcp(app.state, settings)
    app.state.mcp = mcp
    app.mount("/", transport)
    return app


app = create_app()
