import asyncio
import contextlib
from pathlib import Path

import httpx

from .media import encode_output
from .models import ServiceError
from .worker_lock import WorkerLock


class Runner:
    def __init__(self, settings, store, client):
        self.settings, self.store, self.client = settings, store, client
        self.wake = asyncio.Event()
        self.reconcile_lock = asyncio.Lock()
        self.lock = WorkerLock(settings.data_dir)
        self.task = None

    async def start(self):
        self.lock.acquire()
        # A crash during POST /prompt is ambiguous; do not submit the same job twice.
        for job in self.store.jobs(["preparing"], limit=100000):
            self.store.update(job["id"], "unknown", error={"code": "submission_unknown", "message": "The service stopped during submission. Inspect ComfyUI before submitting again."})
        self.task = asyncio.create_task(self.work())

    async def stop(self):
        try:
            if self.task:
                self.task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await self.task
        finally:
            self.lock.release()

    async def enqueue(self, job_id):
        self.wake.set()

    async def idle(self):
        with contextlib.suppress(asyncio.TimeoutError):
            await asyncio.wait_for(self.wake.wait(), timeout=max(0.05, self.settings.poll_seconds))

    async def work(self):
        while True:
            self.wake.clear()
            # A timeout/lost submit response does not prove the GPU stopped. Keep later jobs waiting.
            for uncertain in self.store.jobs(["unknown"], limit=100000):
                await self.reconcile(uncertain)
            if self.store.jobs(["unknown"], limit=1):
                await self.idle()
                continue
            job = self.store.next_job()
            if not job:
                await self.idle()
                continue
            job_id = job["id"]
            try:
                if job["status"] == "queued":
                    if not self.store.claim(job_id):
                        continue
                    await self.submit(job)
                    job = self.store.get_job(job_id)
                if job["status"] == "running":
                    entry = await self.client.wait(job["prompt_id"], client_id=job["id"])
                    await self.finish(job, entry)
            except asyncio.CancelledError:
                raise
            except Exception as error:
                current = self.store.get_job(job_id)
                uncertain = current["status"] == "preparing" and isinstance(error, (httpx.HTTPError, OSError))
                prompt_id = getattr(error, "prompt_id", None)
                status = "unknown" if uncertain or prompt_id or getattr(error, "code", "") == "backend_timeout" else "failed"
                self.store.update(job_id, status, prompt_id=prompt_id,
                                  error={"code": getattr(error, "code", "backend_error"), "message": str(error)})

    async def submit(self, job):
        graph = job["graph"]
        images = job["request"].get("images") or ([job["request"]["image"]] if job["request"].get("image") else [])
        bindings = job["metadata"].get("image_bindings") or [job["metadata"].get("image_binding")]
        if images and (len(images) != len(bindings) or any(binding is None for binding in bindings)):
            raise ServiceError("invalid_snapshot", "Reference image bindings do not match the persisted images.", 500)
        for image, binding in zip(images, bindings):
            upload = self.store.upload(image)
            uploaded_name = await self.client.upload(upload["path"])
            graph[binding["node"]]["inputs"][binding["input"]] = uploaded_name
        prompt_id = await self.client.submit(graph, job["id"])
        self.store.update(job["id"], "running", prompt_id=prompt_id)

    async def reconcile(self, job):
        async with self.reconcile_lock:
            return await self._reconcile(self.store.get_job(job["id"]))

    async def _reconcile(self, job):
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
        output_nodes = job["metadata"].get("output_nodes") or [job["metadata"]["output_node"]]
        descriptors = []
        for sample_index, node_id in enumerate(output_nodes):
            sample_images = outputs.get(node_id, {}).get("images", [])
            if not sample_images:
                raise ServiceError("missing_output", f"The configured SaveImage node {node_id} produced no images.", 502)
            descriptors.extend((sample_index, descriptor) for descriptor in sample_images)
        directory = self.settings.data_dir / "outputs" / job["id"]
        directory.mkdir(parents=True, exist_ok=True)
        images = []
        for index, (sample_index, descriptor) in enumerate(descriptors):
            data = await self.client.image(descriptor)
            data, width, height, extension = await asyncio.to_thread(
                encode_output, data, job["request"].get("output_format", "png"), job["request"].get("output_quality", 95))
            path = directory / f"{index}.{extension}"
            temporary = path.with_suffix(path.suffix + ".tmp")
            temporary.write_bytes(data)
            temporary.replace(path)
            seeds = job["request"].get("seeds") or [job["request"].get("seed")]
            images.append({"path": str(path.resolve()), "width": width, "height": height, "format": extension,
                           "seed": seeds[sample_index]})
        preview = outputs.get(job["metadata"]["prompt_preview_node"], {}).get("text", [])
        self.store.update(job["id"], "succeeded", result={"images": images, "effective_prompt": preview[0] if preview else None})
