"""Waiting tests modify synthetic task state only; no image workflow is submitted."""
import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from comfy_image_service.config import Settings
from comfy_image_service.models import ServiceError
from comfy_image_service.service import ImageService
from comfy_image_service.store import Store
from comfy_image_service.tests.test_service import ServiceFixture


def synthetic_job(store):
    return store.create_job({"prompt": "Synthetic waiting test", "mode": "t2i", "tier": "fast"}, {},
                            {"revision": "test"}, "test", None, 100)[0]


class WaitingTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        settings = Settings(home=Path(self.temp.name))
        self.store = Store(settings.data_dir)
        self.service = ImageService(settings, self.store, None, None, None)
        self.service.wait_progress_seconds = 60
        self.job = synthetic_job(self.store)

    async def asyncTearDown(self):
        self.assertFalse(self.store._job_watchers)
        self.store.close()
        self.temp.cleanup()

    async def test_committed_state_changes_wake_waiters_without_database_polling(self):
        reports = []

        async def progress(elapsed, message):
            reports.append((elapsed, message))

        with patch.object(self.store, "get_job", wraps=self.store.get_job) as reads:
            waiting = asyncio.create_task(self.service.wait_for_job(self.job["id"], 1, progress))
            await asyncio.sleep(0.02)
            self.assertEqual(reads.call_count, 1)
            await asyncio.to_thread(self.store.update, self.job["id"], "running", prompt_id="already-running")
            await asyncio.sleep(0.02)
            self.assertEqual(reads.call_count, 2)
            self.store.update(self.job["id"], "succeeded", result={"effective_prompt": "test", "images": []})
            result = await asyncio.wait_for(waiting, 0.3)
        self.assertFalse(result["wait_timed_out"])
        self.assertEqual(result["next_action"], "download")
        self.assertEqual([message.rsplit(": ", 1)[-1].split(";")[0] for _, message in reports], ["queued", "running", "succeeded"])
        self.assertEqual(len({elapsed for elapsed, _ in reports}), 3)

    async def test_deadline_preserves_the_same_pending_job(self):
        result = await self.service.wait_for_job(self.job["id"], 0.02)
        self.assertEqual(result["id"], self.job["id"])
        self.assertTrue(result["wait_timed_out"])
        self.assertEqual(result["next_action"], "wait_for_job")
        self.assertEqual(self.store.get_job(self.job["id"])["status"], "queued")
        self.assertIsNone(self.store.get_job(self.job["id"])["prompt_id"])
        self.assertEqual(len(self.store.jobs()), 1)

    async def test_completed_failed_and_unknown_jobs_return_immediately(self):
        for status, action in (("succeeded", "download"), ("failed", "inspect_error"), ("unknown", "inspect_backend")):
            self.store.update(self.job["id"], status)
            result = await asyncio.wait_for(self.service.wait_for_job(self.job["id"], 60), 0.2)
            self.assertEqual(result["status"], status)
            self.assertFalse(result["wait_timed_out"])
            self.assertEqual(result["next_action"], action)

    async def test_cancelling_one_waiter_does_not_cancel_another_or_the_job(self):
        first = asyncio.create_task(self.service.wait_for_job(self.job["id"], 1))
        second = asyncio.create_task(self.service.wait_for_job(self.job["id"], 1))
        await asyncio.sleep(0)
        first.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await first
        self.assertEqual(self.store.get_job(self.job["id"])["status"], "queued")
        self.assertEqual(len(self.store._job_watchers), 1)
        self.store.update(self.job["id"], "failed", error={"code": "synthetic", "message": "test state"})
        result = await asyncio.wait_for(second, 0.2)
        self.assertEqual(result["status"], "failed")

    async def test_completion_during_a_progress_callback_is_not_lost(self):
        async def progress(elapsed, message):
            if ": queued" in message:
                self.store.update(self.job["id"], "succeeded")
        result = await asyncio.wait_for(self.service.wait_for_job(self.job["id"], 60, progress), 0.2)
        self.assertEqual(result["status"], "succeeded")

    async def test_invalid_ids_and_deadlines_leave_no_subscriptions(self):
        with self.assertRaises(ServiceError):
            await self.service.wait_for_job("missing", 1)
        for timeout in (0, -1, 601, float("inf"), float("nan")):
            with self.assertRaises(ServiceError):
                await self.service.wait_for_job(self.job["id"], timeout)


async def idle_worker(self):
    await asyncio.Event().wait()


class WaitingMCPTests(ServiceFixture):
    def setUp(self):
        # Keep the real authentication and MCP stack, but disable all rendering execution.
        self.worker_patch = patch("comfy_image_service.runner.Runner.work", idle_worker)
        self.worker_patch.start()
        self.addCleanup(self.worker_patch.stop)
        super().setUp()

    def messages(self, body, headers=None):
        response = self.client.post("/mcp", headers={**self.auth, "Accept": "application/json, text/event-stream",
                                                     "MCP-Protocol-Version": "2025-11-25", **(headers or {})}, json=body)
        self.assertEqual(response.status_code, 200, response.text[:300])
        self.assertTrue(response.headers["content-type"].startswith("text/event-stream"))
        return [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith("data: ") and line[6:].strip()]

    def test_tool_discovery_and_wait_stream_include_real_status_and_final_result(self):
        discovered = self.messages({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        tools = next(message["result"]["tools"] for message in discovered if message.get("id") == 1)
        tool = next(tool for tool in tools if tool["name"] == "wait_for_job")
        self.assertNotIn("ctx", tool["inputSchema"]["properties"])
        job = synthetic_job(self.app.state.store)

        async def finish_later():
            await asyncio.sleep(0.05)
            self.app.state.store.update(job["id"], "failed", error={"code": "synthetic", "message": "test state"})

        self.client.portal.start_task_soon(finish_later)
        messages = self.messages({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {
            "name": "wait_for_job", "arguments": {"job_id": job["id"], "timeout_seconds": 1}, "_meta": {"progressToken": "wait-test"}}})
        progress = [message for message in messages if message.get("method") == "notifications/progress"]
        self.assertGreaterEqual(len(progress), 2)
        self.assertIn(": queued", progress[0]["params"]["message"])
        self.assertIn(": failed", progress[-1]["params"]["message"])
        self.assertTrue(all(a["params"]["progress"] < b["params"]["progress"] for a, b in zip(progress, progress[1:])))
        result = next(message["result"]["structuredContent"] for message in messages if message.get("id") == 2)
        self.assertEqual(result["id"], job["id"])
        self.assertEqual(result["status"], "failed")
        self.assertFalse(result["wait_timed_out"])
        self.assertEqual(self.fake.graphs, [])

    def test_wait_requires_a_key_and_unknown_state_does_not_submit_anything(self):
        job = synthetic_job(self.app.state.store)
        self.app.state.store.update(job["id"], "unknown")
        request = {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {
            "name": "wait_for_job", "arguments": {"job_id": job["id"]}}}
        self.assertEqual(self.client.post("/mcp", json=request).status_code, 401)
        messages = self.messages(request)
        result = next(message["result"]["structuredContent"] for message in messages if message.get("id") == 3)
        self.assertEqual(result["next_action"], "inspect_backend")
        self.assertFalse(result["wait_timed_out"])
        self.assertEqual(self.fake.graphs, [])
