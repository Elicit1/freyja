"""Shared image operations used by the REST API, console, and MCP tools."""
import asyncio
import hashlib
import json
import math
from pathlib import Path

from .media import resolve_image, save_upload
from .models import ASPECT_RATIOS, GenerateRequest, ServiceError


class ImageService:
    wait_progress_seconds = 10.0

    def __init__(self, settings, store, registry, runner, client):
        self.settings, self.store, self.registry, self.runner, self.client = settings, store, registry, runner, client

    def apps(self):
        registered, errors = self.registry.scan()
        return {"data": [{"id": m.id, "title": m.title, "description": m.description,
                          "modes": list(m.modes), "profiles": {k: {"description": p.description, "loader": p.loader, "model": p.inputs.get("unet_name")} for k, p in m.profiles.items()},
                          "revision": revision} for m, _, revision in registered.values()], "diagnostics": errors}

    def capabilities(self):
        return {"service": "sif", "apps": self.apps(),
                "endpoints": {"t2i": "/v1/t2i", "i2i": "/v1/i2i", "upload": "/v1/uploads", "job": "/v1/jobs/{job_id}", "mcp": "/mcp"},
                "defaults": {"app": "minimax-h3-image", "tier": "standard", "megapixels": 4, "expand_prompt": True},
                "mcp_defaults": {"expand_prompt": False}, "parameters": GenerateRequest.model_json_schema(),
                "waiting": {"tool": "wait_for_job", "default_timeout_seconds": 25, "max_timeout_seconds": 600,
                            "transport": "Streamable HTTP / SSE", "client_sleep_required": False},
                "limits": {"max_megapixels": 20, "max_upload_bytes": self.settings.max_upload_bytes,
                           "source_formats": ["png", "jpeg", "webp"], "t2i_aspect_ratios": list(ASPECT_RATIOS), "i2i_aspect_ratio": "original"},
                "fixed_sampling": {"steps": 6, "cfg": 1, "sampler": "euler", "scheduler": "simple", "denoise": 1},
                "queue": self.store.queue_summary(),
                "discovery": "App bundles in apps/*/app.json are re-read on discovery and submission."}

    async def readiness(self):
        info = await self.client.inventory()
        registered, errors = self.registry.scan()
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
        return {"ready": bool(checks) and not errors and all(x["ready"] for x in checks), "checks": checks,
                "diagnostics": errors, "note": "Checks installed nodes/files, not GPU capacity."}

    def upload(self, data):
        saved = save_upload(data, self.settings, self.store)
        return {k: saved[k] for k in ("id", "width", "height", "sha256")}

    def public_job(self, job, base_url=""):
        base = (self.settings.public_base_url or base_url or "http://127.0.0.1:8011").rstrip("/")
        result = job["result"]
        if result:
            result = {"effective_prompt": result["effective_prompt"], "images": [
                {**{k: v for k, v in image.items() if k != "path"}, "url": f"{base}/v1/jobs/{job['id']}/images/{index}"}
                for index, image in enumerate(result["images"])]}
        return {"id": job["id"], "status": job["status"], "created": job["created"], "updated": job["updated"],
                "request": job["request"], "workflow_revision": job["metadata"]["revision"], "comfy_prompt_id": job["prompt_id"],
                "result": result, "error": job["error"], "queue_position": self.store.queue_position(job["id"]),
                "poll_url": f"{base}/v1/jobs/{job['id']}"}

    async def submit(self, mode, body, idempotency_key=None, base_url=""):
        if self.runner.task is None or self.runner.task.done():
            raise ServiceError("worker_unavailable", "The job worker is unavailable; inspect the service log.", 503)
        if idempotency_key is not None and (not idempotency_key.strip() or len(idempotency_key) > 128):
            raise ServiceError("invalid_idempotency_key", "Idempotency-Key must be 1–128 characters.", 422)
        values = body.normalized(mode)
        upload = resolve_image(values["image"], self.settings, self.store) if mode == "i2i" else None
        if upload:
            values["image"] = upload["id"]
        identity = {**values, "image": upload["sha256"] if upload else None}
        fingerprint = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
        existing = self.store.idempotent_job(idempotency_key, fingerprint)
        if existing:
            return self.public_job(existing, base_url)
        graph, resolved, metadata = self.registry.assemble(values)
        job, created = self.store.create_job(resolved, graph, metadata, fingerprint, idempotency_key, self.settings.max_pending_jobs)
        if created:
            await self.runner.enqueue(job["id"])
        return self.public_job(job, base_url)

    async def job(self, job_id, base_url=""):
        return self.public_job(await self.runner.reconcile(self.store.get_job(job_id)), base_url)

    async def wait_for_job(self, job_id, timeout_seconds=25, progress=None):
        """Wait for committed job-state events; never submit, retry, or cancel a render."""
        if not math.isfinite(timeout_seconds) or not 0 < timeout_seconds <= 600:
            raise ServiceError("invalid_wait_timeout", "Wait timeout must be greater than zero and at most 600 seconds.", 422)
        loop = asyncio.get_running_loop()
        started = loop.time()
        deadline = started + timeout_seconds
        previous, last_report = None, started - self.wait_progress_seconds
        progress_value = -1.0
        with self.store.watch_jobs() as changed:
            while True:
                changed.clear()
                job = self.public_job(self.store.get_job(job_id))
                now = loop.time()
                terminal = job["status"] in {"succeeded", "failed", "unknown"}
                signature = (job["status"], job["queue_position"])
                if progress and (signature != previous or now - last_report >= self.wait_progress_seconds):
                    progress_value = max(progress_value + 0.001, now - started)
                    message = "Job " + job["id"] + ": " + job["status"]
                    if job["queue_position"] is not None:
                        message += "; queue position " + str(job["queue_position"])
                    # Elapsed wait time is monotonic; it is not a fabricated sampling percentage.
                    await progress(progress_value, message)
                    last_report = loop.time()
                previous = signature
                remaining = deadline - loop.time()
                if terminal or remaining <= 0:
                    action = {"succeeded": "download", "failed": "inspect_error", "unknown": "inspect_backend"}.get(job["status"], "wait_for_job")
                    return {**job, "wait_timed_out": not terminal, "next_action": action}
                try:
                    await asyncio.wait_for(changed.wait(), timeout=min(remaining, self.wait_progress_seconds))
                except asyncio.TimeoutError:
                    pass

    def image_path(self, job_id, index):
        job = self.store.get_job(job_id)
        if job["status"] != "succeeded" or index < 0 or index >= len(job["result"]["images"]):
            raise ServiceError("image_not_found", "The job has no image at this index.", 404)
        path = Path(job["result"]["images"][index]["path"])
        if not path.is_file():
            raise ServiceError("image_not_found", "The stored output file is missing.", 404)
        return path

    def queue(self, base_url=""):
        jobs = list(reversed(self.store.jobs(["queued", "preparing", "running", "unknown"], limit=100000)))
        return {**self.store.queue_summary(), "jobs": [self.public_job(job, base_url) for job in jobs]}
