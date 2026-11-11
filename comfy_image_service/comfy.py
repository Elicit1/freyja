import asyncio
import mimetypes
import time
from pathlib import Path

import httpx

from .models import ServiceError


class ComfyClient:
    def __init__(self, settings, transport=None):
        self.settings = settings
        self.http = httpx.AsyncClient(base_url=settings.comfy_url, timeout=60, trust_env=False, transport=transport)

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
        return body["prompt_id"]

    async def history(self, prompt_id):
        response = await self.http.get("/history/" + prompt_id)
        response.raise_for_status()
        return response.json().get(prompt_id)

    async def wait(self, prompt_id):
        started = time.monotonic()
        while time.monotonic() - started < self.settings.timeout_seconds:
            try:
                entry = await self.history(prompt_id)
                if entry:
                    return entry
            except httpx.HTTPError:
                # Reads can recover without ever submitting the workflow again.
                pass
            await asyncio.sleep(self.settings.poll_seconds)
        raise ServiceError("backend_timeout", "ComfyUI did not finish before the polling deadline; it may still be running. Check this job again; do not blindly resubmit.", 504)

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
