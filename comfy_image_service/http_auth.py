"""Authenticate every API/MCP request, separately from browser administrator sessions."""
from urllib.parse import urlsplit

from starlette.requests import Request
from starlette.responses import JSONResponse

from .models import ServiceError


class AuthenticationMiddleware:
    public_files = {"/", "/assets/app.js", "/assets/styles.css"}

    def __init__(self, app, state):
        self.app, self.state = app, state

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        request = Request(scope)
        path, method = scope["path"], scope["method"]
        try:
            if path in self.public_files and method in {"GET", "HEAD"}:
                pass
            elif path == "/auth/login" and method == "POST":
                self.require_console_request(request)
            elif path.startswith("/admin/") or path in {"/auth/logout", "/auth/me", "/auth/password"}:
                self.state.auth.require_session(request.cookies.get("sif_session"))
                if method not in {"GET", "HEAD"}:
                    self.require_console_request(request)
            else:
                header = request.headers.get("authorization", "").split(" ", 1)
                token = header[1] if len(header) == 2 and header[0].lower() == "bearer" else ""
                scope.setdefault("state", {})["api_key"] = self.state.auth.require_key(token)
        except ServiceError as error:
            response = JSONResponse({"error": {"code": error.code, "message": error.detail}},
                                    status_code=error.status_code, headers={"Cache-Control": "no-store"})
            await response(scope, receive, send)
            return
        await self.app(scope, receive, send)

    @staticmethod
    def require_console_request(request):
        origin = request.headers.get("origin")
        expected = urlsplit(str(request.url))
        if request.headers.get("x-sif-console") != "1" or (origin and origin != f"{expected.scheme}://{expected.netloc}"):
            raise ServiceError("invalid_origin", "Use the SIF console from the same origin.", 403)


class ResponseHeadersMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        async def secure_send(message):
            if message["type"] == "http.response.start":
                headers = list(message.get("headers", []))
                headers.extend([(b"cache-control", b"no-store"), (b"x-content-type-options", b"nosniff"),
                                (b"referrer-policy", b"no-referrer"),
                                (b"content-security-policy", b"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")])
                message["headers"] = headers
            await send(message)
        await self.app(scope, receive, secure_send)
