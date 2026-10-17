import asyncio
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comfy_client import ComfyAsyncClient, ComfyExecutionError


class CompletionEventTests(unittest.IsolatedAsyncioTestCase):
    async def test_null_executed_output_waits_for_final_video(self):
        prompt_id = "prompt-123"
        asset = {"filename": "FaceRefine_00001_.mp4", "subfolder": "MiniMax-H3", "type": "output"}
        ws = MagicMock()
        ws.recv = AsyncMock(side_effect=[
            json.dumps({"type": "executed", "data": {"prompt_id": prompt_id, "output": None}}),
            json.dumps({"type": "execution_success", "data": {"prompt_id": prompt_id}}),
        ])
        connection = MagicMock()
        connection.__aenter__ = AsyncMock(return_value=ws)
        connection.__aexit__ = AsyncMock(return_value=None)
        client = ComfyAsyncClient("http://comfyui:8188", "ws://comfyui:8188/ws")

        with patch("comfy_client.websockets.connect", return_value=connection), \
             patch.object(client, "_get_completed_history_assets", new_callable=AsyncMock, side_effect=[[], [asset]]) as snapshot:
            result = await client.wait_for_completion(prompt_id, "client-123")

        self.assertEqual(result, [asset])
        self.assertEqual(ws.recv.await_count, 2)
        self.assertEqual(snapshot.await_count, 2)

    async def test_disconnect_reconnects_without_resubmitting_prompt(self):
        client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
        asset = {"filename": "reconnected.mp4"}
        client._wait_completion_connection = AsyncMock(side_effect=[OSError("disconnected"), [asset]])
        client._get_completed_history_assets = AsyncMock(return_value=[])
        client.submit_prompt = AsyncMock()
        with patch("comfy_client.asyncio.sleep", new_callable=AsyncMock):
            self.assertEqual(await client.wait_for_completion("original", "client", 2), [asset])
        client.submit_prompt.assert_not_called()
        self.assertEqual(client._wait_completion_connection.await_count, 2)
        calls = client._wait_completion_connection.await_args_list
        self.assertEqual(calls[0].args[:2], calls[1].args[:2])
        self.assertLess(calls[1].args[2], calls[0].args[2])

    async def test_reconnect_failures_never_reset_total_deadline(self):
        client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
        client.recovery_check_interval = 0.005
        client._wait_completion_connection = AsyncMock(side_effect=OSError("offline"))
        client._get_completed_history_assets = AsyncMock(return_value=[])
        started = asyncio.get_running_loop().time()
        with self.assertRaises(TimeoutError):
            await client.wait_for_completion("original", "client", 0.2)
        self.assertLess(asyncio.get_running_loop().time() - started, 1.0)
        self.assertGreater(client._wait_completion_connection.await_count, 1)

    async def test_explicit_execution_failure_is_not_retried(self):
        client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
        client._wait_completion_connection = AsyncMock(side_effect=ComfyExecutionError("failed"))
        with self.assertRaises(ComfyExecutionError):
            await client.wait_for_completion("original", "client", 2)
        client._wait_completion_connection.assert_awaited_once()

    async def test_cancelled_wait_does_not_reconnect(self):
        client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
        client._wait_completion_connection = AsyncMock(side_effect=asyncio.CancelledError())
        with self.assertRaises(asyncio.CancelledError):
            await client.wait_for_completion("original", "client", 2)
        client._wait_completion_connection.assert_awaited_once()

    async def test_status_uses_terminal_evidence_and_never_promotes_partial_outputs(self):
        for history, queue, expected in [
            ({"status": {"completed": False}, "outputs": {"92": {"images": [{"filename": "partial.mp4"}]}}},
             {"queue_running": [[0, "original"]]}, "RUNNING"),
            (None, {"queue_pending": [[0, "original"]]}, "QUEUED"),
            (None, {}, "UNKNOWN"),
            ({"status": {"status_str": "error"}}, {}, "FAILED"),
            ({"status": {"messages": [["execution_interrupted", {}]]}}, {}, "CANCELLED"),
        ]:
            with self.subTest(expected=expected):
                client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
                responses = [MagicMock(), MagicMock()]
                responses[0].json.return_value = {"original": history} if history else {}
                responses[1].json.return_value = queue
                http = MagicMock()
                http.get = AsyncMock(side_effect=responses)
                client.get_http_client = AsyncMock(return_value=http)
                result = await client.task_state("original")
                self.assertEqual(result["status"], expected)
                self.assertEqual(result["assets"], [])


if __name__ == "__main__":
    unittest.main()
