"""Capability regression tests use synthetic files and an in-memory ComfyUI double."""
import base64
import hashlib
import importlib.util
import io
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image

from comfy_image_service.media import image_info
from comfy_image_service.models import GenerateRequest, canvas_size
from comfy_image_service.registry import Registry
from comfy_image_service.tests import test_access_mcp_queue
from comfy_image_service.tests.test_service import FakeComfy, SOURCE, ServiceFixture, png


class OrderedComfy(FakeComfy):
    async def upload(self, path):
        await super().upload(path)
        return f"uploaded/reference-{len(self.uploads)}.png"


class H3Tests(ServiceFixture):
    backend_type = OrderedComfy
    rpc = test_access_mcp_queue.MCPTests.rpc

    def upload(self, color="red", size=(96, 64)):
        buffer = io.BytesIO()
        Image.new("RGB", size, color).save(buffer, format="PNG")
        return self.client.post("/v1/uploads", headers=self.auth, content=buffer.getvalue()).json()["id"]

    def test_sixteen_separate_references_reach_conditioning_and_expansion_in_order(self):
        references = [self.upload((index * 15, 50, 100)) for index in range(16)]
        response = self.client.post("/v1/i2i", headers=self.auth, json={
            "prompt": "Compose all sixteen people in a group photograph.", "images": references,
            "width": 2048, "height": 1152, "expand_prompt": True,
            "prompt_expansion": {"thinking": False, "temperature": 0.7, "max_length": 4096,
                                 "instructions": "Preserve each person's identity and clothing."}})
        self.assertEqual(response.status_code, 202, response.text)
        job = self.finished(response.json()["id"])
        self.assertEqual(job["status"], "succeeded", job)
        self.assertEqual(job["request"]["images"], references)
        self.assertEqual((job["request"]["width"], job["request"]["height"]), (2048, 1152))
        graph = self.fake.graphs[-1]
        self.assertEqual(len(self.fake.uploads), 16)
        for index in range(1, 17):
            link = graph["32"]["inputs"][f"images.image_{index}"]
            self.assertEqual(graph["34"]["inputs"][f"images.image{index}"], link)
            self.assertEqual(graph[link[0]]["inputs"]["image"], f"uploaded/reference-{index}.png")
        self.assertEqual(graph["34"]["inputs"]["max_length"], 4096)
        self.assertEqual(graph["34"]["inputs"]["sampling_mode.temperature"], 0.7)
        self.assertFalse(graph["34"]["inputs"]["thinking"])
        self.assertIn("<Picture 16>", graph["39"]["inputs"]["value"])
        self.assertIn("2048:1152", graph["39"]["inputs"]["value"])

    def test_vision_only_reference_mode_and_custom_canvas_do_not_resize_sources(self):
        reference = self.upload(size=(96, 64))
        response = self.client.post("/v1/i2i", headers=self.auth, json={
            "prompt": "Use the reference palette for a square poster.", "images": [reference],
            "aspect_ratio": "1:1", "megapixels": 4, "reference_mode": "vision", "expand_prompt": False})
        job = self.finished(response.json()["id"])
        graph = self.fake.graphs[-1]
        self.assertNotIn("vae", graph["32"]["inputs"])
        self.assertEqual(graph["32"]["inputs"]["width"], 2048)
        self.assertEqual(graph["32"]["inputs"]["height"], 2048)
        self.assertEqual(Image.open(io.BytesIO(self.fake.uploads[0])).size, (96, 64))
        self.assertEqual(job["result"]["effective_prompt"], job["request"]["prompt"])

    def test_original_ratio_uses_first_reference_and_twenty_mp_rounding_is_bounded(self):
        references = [self.upload(size=(96, 64)), self.upload(size=(64, 96))]
        response = self.client.post("/v1/i2i", headers=self.auth, json={
            "prompt": "Edit <Picture 1> with the colors of <Picture 2>.", "images": references,
            "megapixels": 20, "expand_prompt": False})
        job = self.finished(response.json()["id"])
        width, height = job["request"]["width"], job["request"]["height"]
        self.assertLessEqual(width * height, 20 * 1024 * 1024)
        self.assertAlmostEqual(width / height, 1.5, delta=0.02)
        self.assertEqual((width % 32, height % 32), (0, 0))

    def test_serial_variants_have_distinct_persisted_seeds_and_complete_results(self):
        for output_format, extension in (("png", "png"), ("jpeg", "jpg"), ("webp", "webp")):
            with self.subTest(output_format=output_format):
                response = self.client.post("/v1/t2i", headers=self.auth, json={
                    "prompt": "A red teapot", "n": 3, "seed": 9007199254740991,
                    "output_format": output_format, "output_quality": 80, "expand_prompt": False})
                job = self.finished(response.json()["id"])
                self.assertEqual(job["status"], "succeeded", job)
                self.assertEqual(job["request"]["seeds"], [9007199254740991, 0, 1])
                graph = self.fake.graphs[-1]
                samplers = [node for node in graph.values() if node["class_type"] == "KSampler"]
                self.assertEqual(len(samplers), 3)
                self.assertEqual([node["inputs"]["seed"] for node in samplers], [9007199254740991, 0, 1])
                for sampler in samplers:
                    self.assertEqual(sampler["inputs"]["steps"], 6)
                    self.assertEqual(sampler["inputs"]["cfg"], 1)
                self.assertEqual([image["seed"] for image in job["result"]["images"]], [9007199254740991, 0, 1])
                for index, image in enumerate(job["result"]["images"]):
                    self.assertEqual(image["format"], extension)
                    self.assertNotIn("path", image)
                    result = self.client.get(f"/v1/jobs/{job['id']}/images/{index}", headers=self.auth)
                    self.assertEqual(image_info(result.content)[2], extension)
                encoded = self.client.get(f"/v1/jobs/{job['id']}/result?response_format=b64_json", headers=self.auth).json()
                self.assertEqual(len(encoded["result"]["images"]), 3)
                self.assertNotIn("url", encoded["result"]["images"][0])
                self.assertEqual(image_info(base64.b64decode(encoded["result"]["images"][0]["b64_json"]))[2], extension)

    def test_validation_rejects_conflicting_or_unsupported_controls_before_submission(self):
        reference = self.upload()
        bad = [
            ("i2i", {"images": [reference] * 17}),
            ("i2i", {"image": reference, "images": [reference]}),
            ("i2i", {"images": []}), ("i2i", {"images": [""]}),
            ("t2i", {"images": [reference]}),
            ("t2i", {"width": 2048}), ("t2i", {"width": 2049, "height": 1152}),
            ("t2i", {"width": 8192, "height": 8192}),
            ("t2i", {"width": 2048, "height": 1152, "megapixels": 4}),
            ("t2i", {"width": 2048, "height": 1152, "aspect_ratio": "16:9"}),
            ("t2i", {"aspect_ratio": "4:1"}), ("t2i", {"aspect_ratio": "NaN:1"}),
            ("t2i", {"aspect_ratio": "1:0"}), ("t2i", {"n": 11}),
            ("t2i", {"expand_prompt": False, "prompt_expansion": {"thinking": True}}),
            ("t2i", {"negative_prompt": "blur"}), ("t2i", {"mask": reference}),
        ]
        for mode, params in bad:
            with self.subTest(params=params):
                response = self.client.post("/v1/" + mode, headers=self.auth, json={"prompt": "A teapot", **params})
                self.assertEqual(response.status_code, 422, response.text)
        self.assertEqual(self.fake.graphs, [])

    def test_reference_order_is_part_of_idempotency_and_single_image_alias_is_compatible(self):
        references = [self.upload("red"), self.upload("blue")]
        headers = {**self.auth, "Idempotency-Key": "ordered-references"}
        body = {"prompt": "Use <Picture 1> and <Picture 2>.", "images": references}
        first = self.client.post("/v1/i2i", headers=headers, json=body).json()
        duplicate = self.client.post("/v1/i2i", headers=headers, json=body).json()
        self.assertEqual(first["id"], duplicate["id"])
        self.assertEqual(self.client.post("/v1/i2i", headers=headers, json={**body, "images": references[::-1]}).status_code, 409)
        headers["Idempotency-Key"] = "single-alias"
        old = self.client.post("/v1/i2i", headers=headers, json={"prompt": "Edit it.", "image": references[0]}).json()
        new = self.client.post("/v1/i2i", headers=headers, json={"prompt": "Edit it.", "images": references[:1]}).json()
        self.assertEqual(old["id"], new["id"])

    def test_pre_upgrade_fingerprint_and_snapshot_can_be_replayed(self):
        old_request = {"app": "minimax-h3-image", "tier": "standard", "prompt": "A teapot", "megapixels": 4.0,
                       "aspect_ratio": "1:1", "expand_prompt": True, "seed": None, "image": None, "mode": "t2i"}
        fingerprint = hashlib.sha256(json.dumps(old_request, sort_keys=True).encode()).hexdigest()
        graph, values, metadata = Registry(self.settings.apps_dir).assemble(GenerateRequest(prompt="A teapot").normalized("t2i"))
        metadata.pop("output_nodes")
        metadata.pop("image_bindings")
        values.pop("seeds")
        job, _ = self.app.state.store.create_job(values, graph, metadata, fingerprint, "before-upgrade", 100)
        response = self.client.post("/v1/t2i", headers={**self.auth, "Idempotency-Key": "before-upgrade"}, json={"prompt": "A teapot"})
        self.assertEqual(response.status_code, 202, response.text)
        self.assertEqual(response.json()["id"], job["id"])
        self.assertEqual(self.finished(job["id"])["status"], "succeeded")

    def test_new_result_and_wait_routes_require_keys(self):
        for path in ("/v1/jobs/unknown/result", "/v1/jobs/unknown/wait"):
            self.assertEqual(self.client.get(path).status_code, 401)

    def test_mcp_exposes_and_accepts_multi_reference_and_output_controls(self):
        tools = self.rpc("tools/list")["result"]["tools"]
        edit_schema = next(tool for tool in tools if tool["name"] == "image_to_image")["inputSchema"]
        self.assertIn("images", edit_schema["properties"])
        self.assertNotIn("image", edit_schema["required"])
        references = [self.upload("red"), self.upload("blue")]
        result = self.rpc("tools/call", {"name": "image_to_image", "arguments": {
            "prompt": "Combine <Picture 1> and <Picture 2>.", "images": references,
            "width": 2048, "height": 1152, "n": 2, "output_format": "webp", "idempotency_key": "mcp-multi"}})["result"]
        self.assertFalse(result.get("isError", False), result)
        job = self.finished(result["structuredContent"]["id"])
        self.assertEqual(len(job["result"]["images"]), 2)
        encoded = self.rpc("tools/call", {"name": "get_result", "arguments": {"job_id": job["id"], "response_format": "b64_json"}})["result"]["structuredContent"]
        self.assertIn("b64_json", encoded["result"]["images"][0])


class SizingTests(unittest.TestCase):
    def test_custom_ratios_and_mp_grid(self):
        for ratio in ("1:3", "3:1", "5:4", "2.39:1"):
            for mp in (0.1, 2.25, 4, 16, 20):
                width, height = canvas_size(GenerateRequest(prompt="x", aspect_ratio=ratio, megapixels=mp).normalized("t2i"))
                self.assertLessEqual(width * height, 20 * 1024 * 1024)
                self.assertEqual((width % 32, height % 32), (0, 0))
        self.assertEqual(canvas_size(GenerateRequest(prompt="x", aspect_ratio="16:9", megapixels=2.25).normalized("t2i")), (2048, 1152))

    def test_reference_dimensions_follow_exif_orientation(self):
        buffer = io.BytesIO()
        image = Image.new("RGB", (96, 64))
        exif = Image.Exif()
        exif[274] = 6
        image.save(buffer, format="JPEG", exif=exif)
        self.assertEqual(image_info(buffer.getvalue())[:2], (64, 96))


class CLITests(ServiceFixture):
    def test_portable_cli_uploads_ordered_files_and_waits_through_http(self):
        spec = importlib.util.spec_from_file_location("sif_portable_cli", SOURCE / "skills/sif-image-generation/scripts/generate.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        first, second = self.home / "first.png", self.home / "second.png"
        first.write_bytes(png())
        second.write_bytes(png())
        calls = []
        client = self.client
        headers = self.auth

        class LocalClient:
            def __init__(self, *args):
                pass

            def request(self, path, data=None, content_type=None, idempotency_key=None):
                calls.append(path)
                response = client.request("POST" if data else "GET", path, content=data, headers={
                    **headers, "Content-Type": content_type or "application/json",
                    **({"Idempotency-Key": idempotency_key} if idempotency_key else {})})
                response.raise_for_status()
                return response.json()

            def submit(self, mode, body, key):
                self.body = body
                job = self.request("/v1/" + mode, json.dumps(body).encode(), idempotency_key=key)
                return {**job, "status": "queued"}

        args = ["generate.py", "i2i", "--image", str(first), "--image", str(second),
                "--prompt", "Compose both references.", "--width", "2048", "--height", "1152",
                "--no-expand", "--n", "2", "--output-format", "webp"]
        with patch.object(sys, "argv", args), patch.dict("os.environ", {"SIF_API_KEY": "sk-test-client"}), \
                patch.object(module, "Client", LocalClient), patch.object(module, "download", return_value={"ok": True}), \
                patch.object(module.time, "sleep", side_effect=AssertionError("Normal waiting must use events")), \
                patch("sys.stdout", new_callable=io.StringIO), patch("sys.stderr", new_callable=io.StringIO):
            module.main()
        self.assertEqual(calls[:3], ["/v1/uploads", "/v1/uploads", "/v1/i2i"])
        self.assertTrue(any("/wait?" in path for path in calls))


if __name__ == "__main__":
    unittest.main()
