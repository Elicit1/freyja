import asyncio
import copy
import io
import json
import shutil
import sqlite3
import tempfile
import time
import unittest
import uuid
from pathlib import Path

import httpx
from fastapi.testclient import TestClient
from PIL import Image

from comfy_image_service.api import create_app
from comfy_image_service.comfy import ComfyClient
from comfy_image_service.config import Settings
from comfy_image_service.models import GenerateRequest, ServiceError
from comfy_image_service.registry import Registry
from comfy_image_service.store import Store


SOURCE = Path(__file__).resolve().parents[1]
PASSWORD = "test-admin-password-12345"
CONSOLE = {"X-SIF-Console": "1"}


def png():
    buffer = io.BytesIO()
    Image.new("RGB", (32, 32), "red").save(buffer, format="PNG")
    return buffer.getvalue()


class FakeComfy:
    def __init__(self):
        self.graphs, self.entries, self.uploads = [], {}, []

    async def close(self):pass

    async def upload(self, path):
        self.uploads.append(Path(path).read_bytes())
        return "uploaded/reference.png"

    async def submit(self, graph, client_id):
        graph = copy.deepcopy(graph)
        self.graphs.append(graph)
        nodes = list(graph.values())
        toggle = next(n for n in nodes if n["class_type"] == "PrimitiveBoolean")["inputs"]["value"]
        user = graph["43" if "43" in graph else "24"]["inputs"]["value"]
        outputs = {}
        for key, node in graph.items():
            if node["class_type"] == "SaveImage":
                outputs[key] = {"images": [{"filename": "test.png", "subfolder": "", "type": "output"}]}
            if node["class_type"] == "PreviewAny":
                outputs[key] = {"text": ["Expanded: " + user if toggle else user]}
        prompt_id = str(uuid.uuid4())
        self.entries[prompt_id] = {"status": {"status_str": "success"}, "outputs": outputs}
        return prompt_id

    async def wait(self, prompt_id, client_id=None):
        await asyncio.sleep(0)
        return self.entries[prompt_id]

    async def history(self, prompt_id):return self.entries.get(prompt_id)

    async def image(self, descriptor):return png()


class ServiceFixture(unittest.TestCase):
    backend_type = FakeComfy

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.home = Path(self.temp.name)
        shutil.copytree(SOURCE / "apps", self.home / "apps")
        self.fake = self.backend_type()
        self.settings = Settings(home=self.home, initial_password=PASSWORD, poll_seconds=0.001)
        self.app = create_app(self.settings, self.fake)
        self.context = TestClient(self.app, base_url="http://127.0.0.1:8011")
        self.client = self.context.__enter__()
        self.client.post("/auth/login", headers=CONSOLE, json={"password": PASSWORD}).raise_for_status()
        key = self.client.post("/admin/keys", headers=CONSOLE, json={"name": "test-agent"}).json()["key"]
        self.auth = {"Authorization": "Bearer " + key}

    def tearDown(self):
        self.context.__exit__(None, None, None)
        self.temp.cleanup()

    def finished(self, job_id):
        for _ in range(100):
            body = self.client.get("/v1/jobs/" + job_id, headers=self.auth).json()
            if body["status"] in {"succeeded", "failed", "unknown"}:return body
            time.sleep(0.01)
        self.fail("Job did not reach a terminal status")

class ServiceTests(ServiceFixture):
    def test_auth_and_validation_before_submission(self):
        self.assertEqual(self.client.get("/v1/apps").status_code, 401)
        self.assertEqual(self.client.post("/v1/t2i", headers=self.auth, json={"prompt":"x","tier":"standrand"}).status_code, 422)
        self.assertEqual(self.client.post("/v1/t2i", headers=self.auth, json={"prompt":"x","megapixels":20.01}).status_code, 422)
        self.assertEqual(self.client.post("/v1/t2i", headers=self.auth, json={"prompt":"x","steps":30}).status_code, 422)
        self.assertEqual(self.client.post("/v1/i2i", headers=self.auth, json={"prompt":"x"}).status_code, 422)
        self.assertEqual(self.client.post("/v1/uploads", headers=self.auth, content=b"invalid").status_code, 422)
        self.assertEqual(len(self.fake.graphs), 0)

    def test_all_six_mode_profile_combinations_and_download_auth(self):
        upload = self.client.post("/v1/uploads", headers=self.auth, content=png()).json()
        for mode in ("t2i", "i2i"):
            for tier, expected in [("pro","int8_convrot"),("standard","w4a8"),("fast","Q4_K_M")]:
                with self.subTest(mode=mode,tier=tier):
                    values = {"prompt":"A teapot","tier":tier,"megapixels":0.25,"expand_prompt":False,"seed":42}
                    if mode == "i2i":values["image"] = upload["id"]
                    response = self.client.post("/v1/" + mode, headers=self.auth, json=values)
                    self.assertEqual(response.status_code, 202, response.text)
                    job = self.finished(response.json()["id"])
                    self.assertEqual(job["status"], "succeeded", job)
                    graph = self.fake.graphs[-1]
                    model = graph["29" if mode == "i2i" else "1"]
                    self.assertIn(expected, model["inputs"]["unet_name"])
                    self.assertEqual(model["class_type"], "UnetLoaderGGUF" if tier == "fast" else "UNETLoader")
                    if mode == "i2i":self.assertEqual(graph["45"]["inputs"]["image"], "uploaded/reference.png")
                    self.assertEqual(job["result"]["effective_prompt"], "A teapot")
                    image_path = f"/v1/jobs/{job['id']}/images/0"
                    self.assertEqual(self.client.get(image_path).status_code, 401)
                    self.assertEqual(self.client.get(image_path, headers=self.auth).content, png())

    def test_idempotency_reuses_one_job_and_rejects_changed_parameters(self):
        headers = {**self.auth,"Idempotency-Key":"same-request"}
        first = self.client.post("/v1/t2i", headers=headers, json={"prompt":"teapot"}).json()
        second = self.client.post("/v1/t2i", headers=headers, json={"prompt":"teapot"}).json()
        self.assertEqual(first["id"], second["id"])
        self.finished(first["id"])
        self.assertEqual(len(self.fake.graphs), 1)
        self.assertEqual(self.client.post("/v1/t2i", headers=headers, json={"prompt":"different"}).status_code, 409)

    def test_expansion_defaults_on_and_seed_is_persisted(self):
        job = self.client.post("/v1/t2i", headers=self.auth, json={"prompt":"red teapot"}).json()
        finished = self.finished(job["id"])
        self.assertTrue(finished["request"]["expand_prompt"])
        self.assertEqual(finished["request"]["seed"], job["request"]["seed"])
        self.assertEqual(finished["result"]["effective_prompt"], "Expanded: red teapot")

    def test_idempotent_replay_survives_app_removal(self):
        headers = {**self.auth,"Idempotency-Key":"existing-job"}
        first = self.client.post("/v1/t2i", headers=headers, json={"prompt":"x"}).json()
        (self.home / "apps/minimax-h3-image/app.json").rename(self.home / "apps/minimax-h3-image/app.disabled")
        second = self.client.post("/v1/t2i", headers=headers, json={"prompt":"x"})
        self.assertEqual(second.status_code, 202)
        self.assertEqual(second.json()["id"], first["id"])

    def test_app_discovery_is_hot_and_queued_graph_is_a_snapshot(self):
        values = GenerateRequest(prompt="x").normalized("t2i")
        registry = Registry(self.home / "apps")
        graph, _, metadata = registry.assemble(values)
        bundle = self.home / "apps/minimax-h3-image"
        copied = self.home / "apps/another"
        shutil.copytree(bundle, copied)
        manifest = json.loads((copied / "app.json").read_text())
        manifest["id"] = "another"
        (copied / "app.json").write_text(json.dumps(manifest))
        apps = self.client.get("/v1/apps", headers=self.auth).json()
        self.assertEqual({x["id"] for x in apps["data"]}, {"minimax-h3-image","another"})
        changed = json.loads((bundle / "t2i.api.json").read_text())
        changed["24"]["inputs"]["value"] = "changed"
        (bundle / "t2i.api.json").write_text(json.dumps(changed))
        self.assertEqual(graph["24"]["inputs"]["value"], "x")
        self.assertNotEqual(metadata["revision"], registry.get(values["app"])[2])

    def test_template_paths_cannot_escape_bundle(self):
        p = self.home / "apps/minimax-h3-image/app.json"
        manifest = json.loads(p.read_text())
        manifest["modes"]["t2i"]["template"] = "../../outside.json"
        p.write_text(json.dumps(manifest))
        apps, errors = Registry(self.home / "apps").scan()
        self.assertEqual(apps, {})
        self.assertIn("within", errors[0]["error"])

    def test_json_body_limit(self):
        response = self.client.post("/v1/t2i", headers=self.auth, content=b'{"prompt":"' + b'x' * (36*1024*1024) + b'"}')
        self.assertEqual(response.status_code, 413, response.text[:200])


class PersistenceTests(unittest.TestCase):
    def test_sql_write_failure_never_returns_a_fake_job(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Store(directory)
            store.close()
            with self.assertRaises(sqlite3.Error):store.create_job({}, {}, {}, "x", None, 10)

    def test_jobs_survive_reopen(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Store(directory)
            job, _ = store.create_job({"prompt":"x"}, {"node":{}}, {}, "x", None, 10)
            store.update(job["id"], "running", prompt_id="backend-id")
            store.close()
            reopened = Store(directory)
            self.assertEqual(reopened.get_job(job["id"])["prompt_id"], "backend-id")
            reopened.close()


class ComfyTransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_partially_invalid_outputs_preserve_backend_id(self):
        def respond(request):return httpx.Response(200, json={"prompt_id":"accepted-id","node_errors":{"62":{"errors":["missing input"]}}})
        client = ComfyClient(Settings(home=Path('.')), httpx.MockTransport(respond))
        try:
            with self.assertRaises(ServiceError) as caught:await client.submit({}, "client")
            self.assertEqual(caught.exception.prompt_id, "accepted-id")
        finally:await client.close()

    async def test_submit_is_never_retried_after_lost_response(self):
        calls = []
        def respond(request):
            calls.append(request)
            raise httpx.ReadTimeout("response lost")
        client = ComfyClient(Settings(home=Path('.')), httpx.MockTransport(respond))
        try:
            with self.assertRaises(httpx.ReadTimeout):await client.submit({}, "client")
            self.assertEqual(len(calls), 1)
        finally:await client.close()


if __name__ == "__main__":unittest.main()
