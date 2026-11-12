import asyncio
import base64
import io
import json
import logging
import tempfile
import threading
import time
import unittest
from pathlib import Path

import httpx

from comfy_image_service.auth import Auth
from comfy_image_service.config import Settings
from comfy_image_service.models import GenerateRequest, ServiceError
from comfy_image_service.registry import Registry
from comfy_image_service.runner import Runner
from comfy_image_service.store import Store
from comfy_image_service.tests.test_service import CONSOLE, PASSWORD, SOURCE, FakeComfy, ServiceFixture, png


logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("mcp").setLevel(logging.WARNING)


class AccessTests(ServiceFixture):
    def test_every_data_endpoint_requires_an_api_key_even_with_an_admin_cookie(self):
        for path in ("/health", "/v1/apps", "/v1/capabilities", "/v1/queue", "/v1/jobs", "/v1/skill", "/v1/skill.zip", "/docs", "/openapi.json", "/mcp"):
            with self.subTest(path=path):
                self.assertEqual(self.client.get(path).status_code, 401)
        self.assertEqual(self.client.post("/v1/t2i", json={"prompt": "x"}).status_code, 401)
        self.assertEqual(self.client.get("/health", headers={"Authorization": "Bearer legacy-config-key"}).status_code, 401)
        self.assertEqual(self.client.get("/").status_code, 200)
        self.assertEqual(self.client.get("/assets/app.js").status_code, 200)

    def test_key_is_returned_once_stored_hashed_and_can_be_revoked(self):
        response = self.client.post("/admin/keys", headers=CONSOLE, json={"name": "Agent A"})
        self.assertEqual(response.status_code, 201)
        key = response.json()
        self.assertTrue(key["key"].startswith("sk-"))
        listing = self.client.get("/admin/keys").text
        self.assertNotIn(key["key"], listing)
        row = dict(self.app.state.store.db.execute("SELECT * FROM api_keys WHERE id=?", (key["id"],)).fetchone())
        self.assertNotIn(key["key"], json.dumps(row))
        headers = {"Authorization": "Bearer " + key["key"]}
        self.assertEqual(self.client.get("/v1/capabilities", headers=headers).status_code, 200)
        self.assertEqual(self.client.delete("/admin/keys/" + key["id"], headers=CONSOLE).status_code, 200)
        self.assertEqual(self.client.get("/health", headers=headers).status_code, 401)
        self.assertEqual(self.client.post("/mcp", headers=headers, json={}).status_code, 401)

    def test_api_keys_cannot_manage_account_and_console_requests_reject_foreign_origins(self):
        self.client.cookies.clear()
        self.assertEqual(self.client.get("/admin/keys", headers=self.auth).status_code, 401)
        self.assertEqual(self.client.post("/auth/login", json={"password": PASSWORD}).status_code, 403)
        self.assertEqual(self.client.post("/auth/login", headers={**CONSOLE, "Origin": "https://foreign.example"}, json={"password": PASSWORD}).status_code, 403)

    def test_password_change_invalidates_sessions_but_preserves_issued_keys(self):
        new = "a-different-local-password"
        rejected = self.client.post("/auth/password", headers=CONSOLE, json={"current_password": "wrong", "new_password": new})
        self.assertEqual(rejected.status_code, 401)
        changed = self.client.post("/auth/password", headers=CONSOLE, json={"current_password": PASSWORD, "new_password": new})
        self.assertEqual(changed.status_code, 200)
        self.assertEqual(self.client.get("/admin/keys").status_code, 401)
        self.assertEqual(self.client.post("/auth/login", headers=CONSOLE, json={"password": PASSWORD}).status_code, 401)
        self.assertEqual(self.client.post("/auth/login", headers=CONSOLE, json={"password": new}).status_code, 200)
        self.assertEqual(self.client.get("/v1/capabilities", headers=self.auth).status_code, 200)
        raw = self.app.state.store.db.execute("SELECT password_hash FROM admin_account").fetchone()[0]
        self.assertNotIn(new, raw)

    def test_failed_login_is_rate_limited_and_session_expires(self):
        self.client.cookies.clear()
        for _ in range(5):
            self.assertEqual(self.client.post("/auth/login", headers=CONSOLE, json={"password": "incorrect"}).status_code, 401)
        self.assertEqual(self.client.post("/auth/login", headers=CONSOLE, json={"password": PASSWORD}).status_code, 429)
        with self.app.state.store.db:
            self.app.state.store.db.execute("UPDATE admin_sessions SET expires=0")
        self.assertEqual(self.client.get("/auth/me").status_code, 401)


class BootstrapTests(unittest.TestCase):
    def test_bootstrap_password_rotates_until_the_user_sets_a_persistent_password(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Store(directory)
            auth = Auth(store)
            with self.assertLogs("sif.bootstrap", level="WARNING") as logs:
                auth.initialize()
            password = logs.output[0].split("password: ", 1)[1]
            self.assertGreaterEqual(len(password), 12)
            self.assertTrue(auth.account()["must_change_password"])
            auth.login(password, "test")
            store.close()
            reopened = Store(directory)
            auth = Auth(reopened)
            with self.assertLogs("sif.bootstrap", level="WARNING") as new_logs:
                auth.initialize()
            fresh_password = new_logs.output[0].split("password: ", 1)[1]
            self.assertNotEqual(fresh_password, password)
            auth.login(fresh_password, "test")
            chosen_password = "user-chosen-persistent-password"
            auth.change_password(fresh_password, chosen_password)
            with self.assertNoLogs("sif.bootstrap", level="WARNING"):
                auth.initialize("do-not-overwrite-this-password")
            auth.login(chosen_password, "test")
            self.assertNotIn(chosen_password, json.dumps([tuple(row) for row in reopened.db.execute("SELECT * FROM admin_account")]))
            reopened.close()


class MCPTests(ServiceFixture):
    def rpc(self, method, params=None, request_id=1):
        body = {"jsonrpc": "2.0", "id": request_id, "method": method}
        if params is not None:
            body["params"] = params
        response = self.client.post("/mcp", headers={**self.auth, "Accept": "application/json, text/event-stream", "MCP-Protocol-Version": "2025-11-25"}, json=body)
        self.assertEqual(response.status_code, 200, response.text[:500])
        return response.json()

    def test_protocol_discovery_and_t2i_i2i_return_durable_jobs(self):
        initialized = self.rpc("initialize", {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "sif-test", "version": "1"}})
        self.assertIn("result", initialized)
        tools = self.rpc("tools/list")["result"]["tools"]
        names = {tool["name"] for tool in tools}
        self.assertEqual(names, {"get_capabilities", "get_readiness", "text_to_image", "image_to_image", "upload_image", "get_job", "get_queue", "get_result"})
        params = {"prompt": "A red teapot", "tier": "fast", "megapixels": 0.25, "seed": 42, "idempotency_key": "mcp-t2i"}
        result = self.rpc("tools/call", {"name": "text_to_image", "arguments": params})["result"]
        self.assertFalse(result.get("isError", False), result)
        job = result["structuredContent"]
        self.assertFalse(job["request"]["expand_prompt"])
        finished = self.finished(job["id"])
        self.assertEqual(finished["status"], "succeeded")
        replay = self.rpc("tools/call", {"name": "text_to_image", "arguments": params})["result"]["structuredContent"]
        self.assertEqual(job["id"], replay["id"])
        upload = self.rpc("tools/call", {"name": "upload_image", "arguments": {"data_base64": base64.b64encode(png()).decode()}})["result"]["structuredContent"]
        edit = self.rpc("tools/call", {"name": "image_to_image", "arguments": {"prompt": "Edit <Picture 1>. Make it blue.", "image": upload["id"], "idempotency_key": "mcp-i2i"}})["result"]["structuredContent"]
        self.assertEqual(self.finished(edit["id"])["status"], "succeeded")
        fetched = self.rpc("tools/call", {"name": "get_result", "arguments": {"job_id": edit["id"]}})["result"]["structuredContent"]
        self.assertTrue(fetched["result"]["images"][0]["url"].endswith("/images/0"))
        self.assertEqual(len(self.fake.graphs), 2)

    def test_invalid_parameters_do_not_submit_and_mcp_methods_require_a_key(self):
        bad = self.rpc("tools/call", {"name": "text_to_image", "arguments": {"prompt": "x", "tier": "invalid", "megapixels": 21, "idempotency_key": "bad"}})
        self.assertTrue("error" in bad or bad.get("result", {}).get("isError"), bad)
        self.assertEqual(self.fake.graphs, [])
        for method in ("GET", "POST", "DELETE"):
            response = self.client.request(method, "/mcp", json={"jsonrpc": "2.0", "method": "notifications/initialized"})
            self.assertEqual(response.status_code, 401)


class GatedComfy(FakeComfy):
    def __init__(self):
        super().__init__()
        self.released = set()
        self.active = 0
        self.max_active = 0
        self.timeout_next = False

    async def submit(self, graph, client_id):
        prompt_id = await super().submit(graph, client_id)
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        return prompt_id

    async def wait(self, prompt_id):
        if self.timeout_next:
            self.timeout_next = False
            raise ServiceError("backend_timeout", "test polling timeout", 504)
        while prompt_id not in self.released:
            await asyncio.sleep(0.005)
        self.active -= 1
        return self.entries[prompt_id]

    async def history(self, prompt_id):
        return self.entries.get(prompt_id) if prompt_id in self.released else None


class QueueTests(ServiceFixture):
    backend_type = GatedComfy

    def wait_for(self, condition):
        for _ in range(200):
            if condition():
                return
            time.sleep(0.01)
        self.fail("Queue condition timed out")

    def test_concurrent_clients_share_a_strict_fifo_serial_queue(self):
        jobs = [self.client.post("/v1/t2i", headers=self.auth, json={"prompt": str(index), "seed": index}).json() for index in range(3)]
        self.wait_for(lambda: len(self.fake.graphs) == 1)
        self.assertEqual(self.client.get("/v1/jobs/" + jobs[1]["id"], headers=self.auth).json()["queue_position"], 1)
        self.assertEqual(self.client.get("/v1/jobs/" + jobs[2]["id"], headers=self.auth).json()["queue_position"], 2)
        self.assertEqual(len(self.fake.graphs), 1)
        for index, job in enumerate(jobs):
            self.wait_for(lambda: len(self.fake.entries) == index + 1)
            self.fake.released.add(list(self.fake.entries)[index])
            self.assertEqual(self.finished(job["id"])["status"], "succeeded")
        self.assertEqual(self.fake.max_active, 1)
        prompts = [graph["24"]["inputs"]["value"] for graph in self.fake.graphs]
        self.assertEqual(prompts, ["0", "1", "2"])

    def test_timeout_blocks_the_next_render_until_backend_completion_is_known(self):
        self.fake.timeout_next = True
        first = self.client.post("/v1/t2i", headers=self.auth, json={"prompt": "first"}).json()
        second = self.client.post("/v1/t2i", headers=self.auth, json={"prompt": "second"}).json()
        self.assertEqual(self.finished(first["id"])["status"], "unknown")
        time.sleep(0.1)
        self.assertEqual(len(self.fake.graphs), 1)
        queue = self.client.get("/v1/queue", headers=self.auth).json()
        self.assertEqual((queue["blocked"], queue["waiting"]), (1, 1))
        self.fake.active -= 1
        self.fake.released.add(next(iter(self.fake.entries)))
        self.wait_for(lambda: len(self.fake.graphs) == 2)
        self.fake.released.add(list(self.fake.entries)[1])
        self.assertEqual(self.finished(second["id"])["status"], "succeeded")
        self.assertEqual(self.fake.max_active, 1)


class RestartQueueTests(unittest.IsolatedAsyncioTestCase):
    async def test_restart_finishes_existing_render_before_submitting_waiting_jobs(self):
        with tempfile.TemporaryDirectory() as directory:
            settings = Settings(home=Path(directory), poll_seconds=0.005)
            store = Store(settings.data_dir)
            registry = Registry(SOURCE / "apps")
            fake = GatedComfy()
            ids = []
            for index in range(3):
                graph, values, metadata = registry.assemble(GenerateRequest(prompt=str(index)).normalized("t2i"))
                job, _ = store.create_job(values, graph, metadata, str(index), None, 100)
                ids.append(job["id"])
            runner = Runner(settings, store, fake)
            await runner.start()
            for _ in range(100):
                if fake.entries:
                    break
                await asyncio.sleep(0.005)
            await runner.stop()
            self.assertEqual(store.get_job(ids[0])["status"], "running")
            restarted = Runner(settings, store, fake)
            await restarted.start()
            second_worker = Runner(settings, store, fake)
            try:
                with self.assertRaises(RuntimeError):
                    await second_worker.start()
                await asyncio.sleep(0.03)
                self.assertEqual(len(fake.graphs), 1)
                for index in range(3):
                    for _ in range(100):
                        if len(fake.entries) > index:
                            break
                        await asyncio.sleep(0.005)
                    fake.released.add(list(fake.entries)[index])
                    for _ in range(100):
                        if store.get_job(ids[index])["status"] == "succeeded":
                            break
                        await asyncio.sleep(0.005)
                    self.assertEqual(store.get_job(ids[index])["status"], "succeeded")
                self.assertEqual(fake.max_active, 1)
            finally:
                await restarted.stop()
                store.close()
