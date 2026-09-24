"""
FastAPI HTTP Endpoint Integration Tests.
Verifies OpenAPI routing, model listing, input validation, and OpenAI standard response envelopes.
"""

import unittest
from starlette.testclient import TestClient
from main import app


class TestGatewayAPI(unittest.TestCase):

    def setUp(self):
        self.client = TestClient(app)

    def test_root_and_health(self):
        """Test GET / and GET /health"""
        resp = self.client.get("/")
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertIn("available_models", data)
        self.assertIn("flux-2-klein-9b", data["available_models"])
        self.assertIn("minimax-h3-fl2va", data["available_models"])
        self.assertIn("minimax-h3-ref2va", data["available_models"])

        resp_health = self.client.get("/health")
        self.assertEqual(resp_health.status_code, 200)

    def test_list_models(self):
        """Test GET /v1/models conforms to OpenAI spec"""
        resp = self.client.get("/v1/models")
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertEqual(data.get("object"), "list")
        model_ids = [m["id"] for m in data.get("data", [])]
        self.assertIn("flux-2-klein-9b", model_ids)
        self.assertIn("minimax-h3-fl2va", model_ids)
        self.assertIn("minimax-h3-ref2va", model_ids)

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
