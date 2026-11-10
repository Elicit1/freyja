"""
FastAPI HTTP Endpoint Integration Tests.
Verifies OpenAPI routing, model listing, input validation, and OpenAI standard response envelopes.
"""

import unittest
import os
from unittest.mock import AsyncMock, Mock, patch
from starlette.testclient import TestClient
from main import app


class TestGatewayAPI(unittest.TestCase):
    def test_dynamic_workflow_catalog_is_read_only_and_never_probes_compute(self):
        with patch("main.get_client_pool") as pool:
            response = self.client.get("/v1/dynamic-workflows/capabilities")
        self.assertEqual(200, response.status_code)
        models = response.json()["models"]
        base = next(m for m in models if m["modelCode"] == "minimax-h3-ref-turbo8")
        self.assertEqual(["BASE", "UPSCALE", "FACE"], base["stages"])
        self.assertEqual(64, len(base["revision"]))
        self.assertTrue(next(m for m in models if m["modelCode"] == "minimax-h3-ref-turbo8-face-refine")["legacyFixedFace"])
        pool.assert_not_called()


    def setUp(self):
        self.token = "gateway-test-token-0123456789"
        environment = patch.dict(os.environ, {"GATEWAY_API_KEY": self.token})
        environment.start()
        self.addCleanup(environment.stop)
        self.client = TestClient(app, headers={"Authorization": "Bearer " + self.token})

    def test_authentication_blocks_work_before_compute_access(self):
        client = TestClient(app)
        with patch("main.get_client_pool") as pool:
            for path in ("/v1/images/generations", "/v1/tasks/task/cancel", "/v1/interrupt"):
                self.assertEqual(client.post(path, json={}).status_code, 401)
            self.assertEqual(client.get("/v1/models").status_code, 401)
            self.assertEqual(client.get("/v1/tasks/task/output").status_code, 401)
            self.assertEqual(client.post("/v1/interrupt", headers={"Authorization": "Bearer wrong"}).status_code, 401)
            pool.assert_not_called()
            pool.return_value.interrupt_all = AsyncMock(return_value={})
            self.assertEqual(self.client.post("/v1/interrupt").status_code, 200)
            pool.return_value.interrupt_all.assert_awaited_once()
        for token in ("", "too-short"):
            with patch.dict(os.environ, {"GATEWAY_API_KEY": token}):
                self.assertEqual(self.client.get("/v1/models").status_code, 503)

    def test_custom_validator_error_is_a_serializable_422(self):
        response = self.client.post("/v1/images/generations", json={
            "prompt": "test", "references": [], "extra_body": {"references": "invalid"}
        })
        self.assertEqual(response.status_code, 422)
        self.assertIn("must be a list", response.json()["detail"][0]["msg"])

    def test_direct_backend_overrides_never_contact_the_target(self):
        for routing in ({"target_backend": "http://untrusted.invalid"},
                        {"extra_body": {"backend_url": "http://untrusted.invalid"}}):
            with patch("main.get_client_pool") as pool:
                response = self.client.post("/v1/images/generations", json={"prompt": "test", **routing})
                self.assertEqual(response.status_code, 400)
                pool.return_value.get_client_by_url.assert_not_called()
                pool.return_value.get_client_for_node.assert_not_called()

    def test_root_and_health(self):
        """Test GET / and GET /health"""
        resp = self.client.get("/")
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertIn("available_models", data)
        self.assertIn("flux-2-klein-9b", data["available_models"])
        self.assertIn("minimax-h3-fl2va", data["available_models"])
        self.assertIn("minimax-h3-ref2va", data["available_models"])

        pool=Mock();pool.check_all_health=AsyncMock(return_value={"default":True})
        with patch("main.get_client_pool",return_value=pool):
            resp_health = self.client.get("/health")
        self.assertEqual(resp_health.status_code, 200)
        self.assertEqual(resp_health.json()["status"],"healthy")

    def test_list_models(self):
        """Test GET /v1/models conforms to OpenAI spec"""
        resp = self.client.get("/v1/models")
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertEqual(data.get("object"), "list")
        model_ids = [m["id"] for m in data.get("data", [])]
        self.assertIn("flux-2-klein-9b", model_ids)
        self.assertIn("minimax-h3-fl2va", model_ids)
        self.assertIn("minimax-h3-fl2va-turbo4", model_ids)
        self.assertIn("minimax-h3-fl2va-turbo8", model_ids)
        self.assertIn("minimax-h3-ref-turbo8", model_ids)
        self.assertIn("minimax-h3-ref-turbo8-face-refine", model_ids)
        self.assertIn("minimax-h3-ref2va", model_ids)

    def test_h3_capabilities_reuse_backend_inspection_without_extra_health_probes(self):
        import main
        model=main.config.get_exact_model("minimax-h3-ref-turbo8")
        client=Mock();client.check_health=AsyncMock()
        pool=Mock();pool.get_client_for_node=AsyncMock(return_value=client)
        for missing,healthy in (([],True),(["backend-unreachable"],False)):
            inspect=AsyncMock(return_value={"missingNodes":missing,"continuation":healthy})
            with patch.object(main,"get_client_pool",return_value=pool),patch.object(main,"inspect_backend",inspect):
                response=self.client.get(f"/v1/models/{model.model_name}/capabilities")
            self.assertEqual(response.status_code,200)
            self.assertEqual(response.json()["healthy"],healthy)
            inspect.assert_awaited_once()
        client.check_health.assert_not_awaited()

    def test_videos_generations_endpoint(self):
        """Test /v1/videos/generations endpoint validation"""
        resp = self.client.post("/v1/videos/generations", json={"model": "minimax-h3-fl2va"})
        self.assertEqual(resp.status_code, 422)  # Missing prompt

    def test_validation_error_format(self):
        """Test invalid requests return 400 with standard OpenAI error structure"""
        # Missing prompt
        resp = self.client.post("/v1/images/generations", json={"model": "flux-2-klein-9b"})
        self.assertEqual(resp.status_code, 422)  # Pydantic unprocessable entity

        # Invalid model
        resp2 = self.client.post("/v1/images/generations", json={
            "model": "non-existent-model-xyz",
            "prompt": "hello world"
        })
        # Should return 400 with OpenAI formatted error
        self.assertEqual(resp2.status_code, 400)
        err = resp2.json()
        self.assertIn("error", err)
        self.assertEqual(err["error"]["type"], "invalid_request_error")
        self.assertIn("not supported", err["error"]["message"])


if __name__ == "__main__":
    unittest.main()
