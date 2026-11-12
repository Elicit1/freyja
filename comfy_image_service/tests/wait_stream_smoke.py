"""Real HTTP/SSE client check against a temporary instance, with rendering disabled."""
import asyncio
import json
import shutil
import socket
import tempfile
import threading
import time
from pathlib import Path
from unittest.mock import patch

import httpx2
import uvicorn
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

from comfy_image_service.api import create_app
from comfy_image_service.config import Settings
from comfy_image_service.tests.test_service import PASSWORD, SOURCE, FakeComfy
from comfy_image_service.tests.test_waiting import idle_worker, synthetic_job


async def check_client(base, key, app, job):
    reports = []
    first_report = asyncio.Event()

    async def progress(value, total, message):
        reports.append({"progress": value, "message": message})
        first_report.set()

    async def complete_after_first_streamed_event():
        # Completion depends on receiving progress while the HTTP tool call is still open.
        await asyncio.wait_for(first_report.wait(), 4)
        app.state.store.update(job["id"], "failed", error={"code": "synthetic", "message": "Non-rendering stream check"})

    async with httpx2.AsyncClient(headers={"Authorization": "Bearer " + key}, trust_env=False) as http:
        async with Client(streamable_http_client(base + "/mcp", http_client=http), read_timeout_seconds=8) as client:
            tools = await client.list_tools()
            assert "wait_for_job" in {tool.name for tool in tools.tools}
            completion = asyncio.create_task(complete_after_first_streamed_event())
            try:
                result = await client.call_tool("wait_for_job", {"job_id": job["id"], "timeout_seconds": 5},
                                                progress_callback=progress)
                await completion
            finally:
                if not completion.done():
                    completion.cancel()
                    await asyncio.gather(completion, return_exceptions=True)
            assert not result.is_error
            assert result.structured_content["status"] == "failed"
            assert result.structured_content["wait_timed_out"] is False
            assert len(reports) >= 2
            assert ": queued" in reports[0]["message"]
            assert ": failed" in reports[-1]["message"]
    return {"official_sdk_http_sse": "passed", "progress_received_before_completion": True,
            "progress_notifications": len(reports), "render_submissions": 0}


def main():
    with tempfile.TemporaryDirectory() as directory, patch("comfy_image_service.runner.Runner.work", idle_worker):
        home = Path(directory)
        shutil.copytree(SOURCE / "apps", home / "apps")
        fake = FakeComfy()
        app = create_app(Settings(home=home, initial_password=PASSWORD), fake)
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        base = "http://127.0.0.1:" + str(listener.getsockname()[1])
        server = uvicorn.Server(uvicorn.Config(app, log_level="error"))
        thread = threading.Thread(target=lambda: server.run(sockets=[listener]), daemon=True)
        thread.start()
        try:
            for _ in range(200):
                if server.started:
                    break
                time.sleep(0.025)
            assert server.started
            key = app.state.auth.create_key("Temporary stream verification")["key"]
            job = synthetic_job(app.state.store)
            result = asyncio.run(check_client(base, key, app, job))
            assert not fake.graphs
            output = SOURCE / "data" / "verification" / "sif-wait-stream.json"
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(result, indent=2), encoding="utf-8")
            print(json.dumps(result))
        finally:
            server.should_exit = True
            thread.join(timeout=10)
            listener.close()


if __name__ == "__main__":
    main()
