"""Password-only management endpoints. API keys cannot administer the service."""
from fastapi import Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, SecretStr, StrictBool

from .models import ServiceError


class Login(BaseModel):
    model_config = ConfigDict(extra="forbid")
    password: SecretStr = Field(min_length=1, max_length=128)


class PasswordChange(BaseModel):
    model_config = ConfigDict(extra="forbid")
    current_password: SecretStr = Field(min_length=1, max_length=128)
    new_password: SecretStr = Field(min_length=12, max_length=128)


class KeyCreate(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    name: str = Field(min_length=1, max_length=80)


class ResolveUnknown(BaseModel):
    model_config = ConfigDict(extra="forbid")
    confirmed_backend_finished: StrictBool


def mount_admin(app):
    @app.post("/auth/login")
    async def login(body: Login, request: Request):
        token = app.state.auth.login(body.password.get_secret_value(), request.client.host if request.client else "local")
        response = JSONResponse(app.state.auth.account())
        response.set_cookie("sif_session", token, max_age=app.state.auth.session_seconds, httponly=True,
                            secure=request.url.scheme == "https", samesite="strict", path="/")
        return response

    @app.get("/auth/me")
    async def me():
        return app.state.auth.account()

    @app.post("/auth/logout")
    async def logout(request: Request):
        app.state.auth.logout(request.cookies.get("sif_session"))
        response = JSONResponse({"ok": True})
        response.delete_cookie("sif_session", path="/")
        return response

    @app.post("/auth/password")
    async def change_password(body: PasswordChange):
        app.state.auth.change_password(body.current_password.get_secret_value(), body.new_password.get_secret_value())
        response = JSONResponse({"ok": True, "login_required": True})
        response.delete_cookie("sif_session", path="/")
        return response

    @app.get("/admin/keys")
    async def keys():
        return {"data": app.state.auth.keys()}

    @app.post("/admin/keys", status_code=201)
    async def create_key(body: KeyCreate):
        return app.state.auth.create_key(body.name)

    @app.delete("/admin/keys/{key_id}")
    async def revoke_key(key_id: str):
        app.state.auth.revoke_key(key_id)
        return {"ok": True}

    @app.get("/admin/overview")
    async def overview(request: Request):
        base = str(request.base_url).rstrip("/")
        service = app.state.service
        return {"service": "sif", "worker_alive": app.state.runner.task is not None and not app.state.runner.task.done(),
                "account": app.state.auth.account(), "queue": service.queue(base),
                "recent_jobs": [service.public_job(job, base) for job in app.state.store.jobs(limit=12)],
                "active_keys": sum(key["revoked"] is None for key in app.state.auth.keys()),
                "mcp_url": (service.settings.public_base_url or base) + "/mcp"}

    @app.get("/admin/readiness")
    async def readiness():
        return await app.state.service.readiness()

    @app.post("/admin/jobs/{job_id}/resolve")
    async def resolve(job_id: str, body: ResolveUnknown):
        job = app.state.store.get_job(job_id)
        if job["status"] != "unknown" or not body.confirmed_backend_finished:
            raise ServiceError("confirmation_required", "Confirm in ComfyUI that this unresolved render has finished before releasing the queue.", 409)
        current = await app.state.runner.reconcile(job)
        if current["status"] == "unknown":
            app.state.store.update(job_id, "failed", error={"code": "operator_resolved", "message": "Administrator confirmed the backend render ended and released the queue."})
        await app.state.runner.enqueue(job_id)
        return {"ok": True}
