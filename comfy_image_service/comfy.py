import asyncio
import json
import mimetypes
from pathlib import Path
from urllib.parse import urlencode, urlsplit, urlunsplit

import httpx
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed, WebSocketException

from .models import ServiceError


class ComfyClient:
    def __init__(self, settings, transport=None):
        self.settings = settings
        self.http = httpx.AsyncClient(base_url=settings.comfy_url, timeout=60, trust_env=False, transport=transport)
        self._prompt_clients = {}

    async def close(self):
        await self.http.aclose()

    async def upload(self, path):
        path = Path(path)
        with path.open("rb") as stream:
            response = await self.http.post("/upload/image", files={"image": (path.name, stream, mimetypes.guess_type(path.name)[0])},
                                            data={"type": "input", "overwrite": "false"})
        response.raise_for_status()
        body = response.json()
        return "/".join(x for x in (body.get("subfolder"), body["name"]) if x)

    async def inventory(self):
        response = await self.http.get("/object_info")
        response.raise_for_status()
        return response.json()

    async def submit(self, graph, client_id):
        # Do not retry this POST: a lost response may still have queued a render.
        response = await self.http.post("/prompt", json={"prompt": graph, "client_id": client_id})
        body = response.json()
        if response.status_code != 200 or body.get("node_errors"):
            # ComfyUI can accept some output nodes while rejecting others. Preserve its ID.
            error = ServiceError("workflow_rejected", "ComfyUI rejected workflow outputs: " + str(body.get("node_errors") or body.get("error")), 502)
            error.prompt_id = body.get("prompt_id")
            raise error
        self._prompt_clients[body["prompt_id"]] = client_id
        return body["prompt_id"]

    async def history(self, prompt_id):
        response = await self.http.get("/history/" + prompt_id)
        response.raise_for_status()
        return response.json().get(prompt_id)

    async def _completed_history(self, prompt_id):
        try:
            entry = await self.history(prompt_id)
        except httpx.HTTPError:
            return None
        if entry:
            status = entry.get("status", {})
            if status.get("completed") is True or ("completed" not in status and status.get("status_str") in {"success", "error"}):
                return entry
        return None

    def _ws_url(self, client_id):
        base = urlsplit(self.settings.comfy_url)
        return urlunsplit(("wss" if base.scheme == "https" else "ws", base.netloc,
                           base.path.rstrip("/") + "/ws", urlencode({"clientId": client_id}), ""))

    async def wait(self, prompt_id, client_id=None):
        # Use the durable SIF job ID after a process restart; never create a new prompt.
        client_id = client_id or self._prompt_clients.get(prompt_id)
        if not client_id:
            raise ValueError("The original ComfyUI client ID is required to resume event-driven waiting.")
        try:
            return await asyncio.wait_for(self._wait_events(prompt_id, client_id), self.settings.timeout_seconds)
        except (asyncio.TimeoutError, OSError, WebSocketException) as error:
            # One final bounded snapshot covers a completion missed while disconnected.
            try:
                entry = await asyncio.wait_for(self._completed_history(prompt_id), min(5.0, self.settings.timeout_seconds))
                if entry:
                    return entry
            except asyncio.TimeoutError:
                pass
            raise ServiceError("backend_timeout", "ComfyUI completion is unconfirmed after the WebSocket wait. The existing prompt may still be running; do not resubmit it.", 504) from error
        finally:
            self._prompt_clients.pop(prompt_id, None)

    async def _wait_events(self, prompt_id, client_id):
        # The library backs off transient connection failures; there is no timed HTTP polling.
        async for socket in connect(self._ws_url(client_id), proxy=None,
                                    open_timeout=min(10.0, self.settings.timeout_seconds),
                                    ping_interval=20, ping_timeout=20, close_timeout=1,
                                    max_size=self.settings.max_output_bytes):
            try:
                # Subscribe first, then read once: a cached/fast completion cannot be lost.
                entry = await self._completed_history(prompt_id)
                if entry:
                    return entry
                async for raw in socket:
                    if not isinstance(raw, str):  # Binary preview frames are not completion events.
                        continue
                    try:
                        message = json.loads(raw)
                    except (ValueError, TypeError):
                        continue
                    if not isinstance(message, dict):
                        continue
                    data = message.get("data")
                    if not isinstance(data, dict) or data.get("prompt_id") != prompt_id:
                        continue
                    kind = message.get("type")
                    finished = kind == "executing" and "node" in data and data["node"] is None
                    if kind not in {"execution_success", "execution_error", "execution_interrupted"} and not finished:
                        continue
                    entry = await self._completed_history(prompt_id)
                    if entry:
                        return entry
                    if kind in {"execution_error", "execution_interrupted"}:
                        return {"status": {"status_str": "error", "completed": True, "messages": [[kind, data]]}, "outputs": {}}
                    if finished:
                        raise ServiceError("backend_timeout", "ComfyUI sent its final event but the persisted history is unavailable. Inspect the existing prompt; do not resubmit.", 504)
                    # execution_success can precede history persistence. The final executing
                    # event with node=null is emitted after ComfyUI writes its history.
            except ConnectionClosed:
                continue  # Reconnect with the same client ID and check history once after subscribing.
            finally:
                await socket.close()
        raise ServiceError("backend_timeout", "The ComfyUI event stream ended without a confirmed result.", 504)

    async def image(self, descriptor):
        params = {key: descriptor.get(key, "") for key in ("filename", "subfolder", "type")}
        chunks, total = [], 0
        async with self.http.stream("GET", "/view", params=params, timeout=180) as response:
            response.raise_for_status()
            async for chunk in response.aiter_bytes():
                total += len(chunk)
                if total > self.settings.max_output_bytes:
                    raise ServiceError("output_too_large", "ComfyUI output exceeded the download size limit.", 502)
                chunks.append(chunk)
        return b"".join(chunks)
