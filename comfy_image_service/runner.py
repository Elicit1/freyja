import asyncio
import contextlib
from pathlib import Path

import httpx

from .media import image_info
from .models import ServiceError


class Runner:
    def __init__(self, settings, store, client):
        self.settings, self.store, self.client = settings, store, client
        self.queue = asyncio.Queue()
        self.task = None

    async def start(self):
        # A crash during POST /prompt is ambiguous; do not submit the same job twice.
        for job in self.store.jobs(["preparing"], limit=100000):
            self.store.update(job["id"], "unknown", error={"code": "submission_unknown", "message": "The service stopped during submission. Inspect ComfyUI before submitting again."})
        for job in reversed(self.store.jobs(["queued", "running"], limit=100000)):
            await self.queue.put(job["id"])
        self.task = asyncio.create_task(self.work())

    async def stop(self):
        if self.task:
            self.task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self.task

    async def enqueue(self, job_id):
        await self.queue.put(job_id)

    async def work(self):
        while True:
            job_id = await self.queue.get()
            try:
                job = self.store.get_job(job_id)
                if job["status"] == "queued":
                    if not self.store.claim(job_id):
                        continue
                    await self.submit(job)
                    job = self.store.get_job(job_id)
                if job["status"] == "running":
                    entry = await self.client.wait(job["prompt_id"])
                    await self.finish(job, entry)
            except asyncio.CancelledError:
                raise
            except Exception as error:
                current = self.store.get_job(job_id)
                uncertain = current["status"] == "preparing" and isinstance(error, (httpx.HTTPError, OSError))
                status = "unknown" if uncertain or getattr(error, "code", "") == "backend_timeout" else "failed"
                prompt_id = getattr(error, "prompt_id", None)
                self.store.update(job_id, status, prompt_id=prompt_id,
                                  error={"code": getattr(error, "code", "backend_error"), "message": str(error)})
            finally:
                self.queue.task_done()

    async def submit(self, job):
        graph = job["graph"]
        image = job["request"].get("image")
        if image:
            upload = self.store.upload(image)
            uploaded_name = await self.client.upload(upload["path"])
            binding = job["metadata"]["image_binding"]
            graph[binding["node"]]["inputs"][binding["input"]] = uploaded_name
        prompt_id = await self.client.submit(graph, job["id"])
        self.store.update(job["id"], "running", prompt_id=prompt_id)

    async def reconcile(self, job):
        if job["status"] == "unknown" and job["prompt_id"]:
            try:
                entry = await self.client.history(job["prompt_id"])
                if entry:
                    try:
                        await self.finish(job, entry)
                    except ServiceError as error:
                        self.store.update(job["id"], "failed", error={"code": error.code, "message": error.detail})
            except httpx.HTTPError:
                pass
        return self.store.get_job(job["id"])

    async def finish(self, job, entry):
        status = entry.get("status", {})
        if status.get("status_str") != "success":
            raise ServiceError("render_failed", "ComfyUI execution failed: " + str(status.get("messages", [])), 502)
        outputs = entry.get("outputs", {})
        descriptors = outputs.get(job["metadata"]["output_node"], {}).get("images", [])
        if not descriptors:
            raise ServiceError("missing_output", "The configured SaveImage node produced no images.", 502)
        directory = self.settings.data_dir / "outputs" / job["id"]
        directory.mkdir(parents=True, exist_ok=True)
        images = []
        for index, descriptor in enumerate(descriptors):
            data = await self.client.image(descriptor)
            width, height, extension = image_info(data)
            path = directory / f"{index}.{extension}"
            temporary = path.with_suffix(path.suffix + ".tmp")
            temporary.write_bytes(data)
            temporary.replace(path)
            images.append({"path": str(path.resolve()), "width": width, "height": height, "format": extension})
        preview = outputs.get(job["metadata"]["prompt_preview_node"], {}).get("text", [])
        self.store.update(job["id"], "succeeded", result={"images": images, "effective_prompt": preview[0] if preview else None})
