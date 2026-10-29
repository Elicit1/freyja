import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from starlette.requests import Request
import main
from schemas import ImageGenerationRequest


class DeferredVideoOutputTests(unittest.IsolatedAsyncioTestCase):
    async def generate(self, deferred):
        model = SimpleNamespace(model_name="test-video", handler="standard", node_id="node1", timeout_seconds=1800, mappings={})
        node = SimpleNamespace(node_id="node1", http_url="http://compute.invalid")
        config = Mock()
        config.get_model.return_value = model
        config.get_node_for_model.return_value = node
        config.server.base_url = "http://testserver"
        config.server.output_dir = "unused"
        config.server.timeout_seconds = 1800
        upstream = AsyncMock()
        upstream.http_base = node.http_url
        upstream.submit_prompt.return_value = "original-prompt"
        upstream.wait_for_completion.return_value = [{"filename": "result.mp4", "type": "output"}]
        upstream.fetch_asset_bytes.return_value = b"original-video"
        pool = Mock()
        pool.get_client_for_node = AsyncMock(return_value=upstream)
        engine = Mock()
        engine.assemble_workflow.return_value = {}
        request = ImageGenerationRequest(model="test-video", prompt="video", task_id="render-original", response_format="url")
        http = Request({"type": "http", "headers": [(b"x-freyja-deferred-video", b"true" if deferred else b"false")],
                        "scheme": "http", "server": ("testserver", 80), "root_path": "", "path": "/v1/videos/generations",
                        "app": main.app, "router": main.app.router})
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            config.server.output_dir = directory
            with patch.object(main, "config", config), patch("main.get_workflow_engine", return_value=engine), \
                    patch("main.get_client_pool", return_value=pool), \
                    patch.object(main.task_tracker, "register", new_callable=AsyncMock), \
                    patch.object(main.task_tracker, "set_status", new_callable=AsyncMock) as mark:
                result = await main.generate_videos(request, http)
        upstream.submit_prompt.assert_awaited_once()
        mark.assert_awaited_once_with("original-prompt", "render-original", "SUCCESS")
        return result, upstream

    async def test_deferred_response_returns_exact_task_url_before_downloading(self):
        result, upstream = await self.generate(True)
        self.assertEqual(result.data[0].url, "http://testserver/v1/tasks/render-original/output")
        upstream.fetch_asset_bytes.assert_not_awaited()

    async def test_existing_json_clients_still_receive_a_ready_file(self):
        result, upstream = await self.generate(False)
        self.assertTrue(result.data[0].url.startswith("http://testserver/outputs/"))
        upstream.fetch_asset_bytes.assert_awaited_once_with("result.mp4", "", "output")


if __name__ == "__main__":
    unittest.main()
