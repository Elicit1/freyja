import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from starlette.testclient import TestClient
import main
from task_tracker import TaskTracker


class TaskRecoveryTests(unittest.TestCase):
    def test_unknown_task_does_not_offer_download(self):
        with patch.object(main.task_tracker, "find", new_callable=AsyncMock, return_value=None):
            client = TestClient(main.app)
            result = client.get("/v1/tasks/missing").json()
            self.assertEqual(result["status"], "UNKNOWN")
            self.assertNotIn("video_url", result)
            self.assertEqual(client.get("/v1/tasks/missing/output").status_code, 409)

    def test_completed_video_is_cached_without_resubmitting_generation(self):
        upstream = AsyncMock()
        upstream.fetch_asset_bytes.return_value = b"completed-video"
        state = {"status": "SUCCESS", "task_id": "render-1", "prompt_id": "prompt-1",
                 "node_id": "node-1", "assets": [{"filename": "result.mp4", "type": "output"}]}
        async def snapshot(_):
            return upstream, dict(state)
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(main.config.server, "output_dir", directory), \
                patch("main.tracked_task_state", side_effect=snapshot):
            client = TestClient(main.app)
            response = client.get("/v1/tasks/render-1").json()
            self.assertEqual(response["status"], "SUCCESS")
            self.assertTrue(response["video_url"].endswith("/v1/tasks/render-1/output"))
            for _ in range(2):
                result = client.get("/v1/tasks/render-1/output")
                self.assertEqual(result.status_code, 200)
                self.assertEqual(result.content, b"completed-video")
            upstream.fetch_asset_bytes.assert_awaited_once()
            self.assertEqual(len(list(Path(directory).glob("*.mp4"))), 1)

    def test_running_task_cannot_be_downloaded(self):
        state = {"status": "RUNNING", "assets": []}
        with patch("main.tracked_task_state", new_callable=AsyncMock, return_value=(AsyncMock(), state)):
            self.assertEqual(TestClient(main.app).get("/v1/tasks/render-1/output").status_code, 409)


class TrackerRetentionTests(unittest.IsolatedAsyncioTestCase):
    async def test_disconnection_keeps_exact_task_binding_until_expiry(self):
        tracker = TaskTracker()
        tracker._redis_client = None
        await tracker.register("prompt-1", "node-1", "render-1", "shot-1")
        await tracker.set_status("prompt-1", "render-1", "UNKNOWN")
        self.assertEqual(await tracker.find(task_id="render-1"), ("prompt-1", "node-1"))
        tracker._memory_tasks["render-1"]["expires_at"] = "0"
        self.assertIsNone(await tracker.find(task_id="render-1"))


if __name__ == "__main__":
    unittest.main()
