import json
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comfy_client import ComfyAsyncClient


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
             patch.object(client, "_get_completed_history_assets", new_callable=AsyncMock, return_value=[]), \
             patch.object(client, "_poll_history_fallback", new_callable=AsyncMock, return_value=[asset]) as fallback:
            result = await client.wait_for_completion(prompt_id, "client-123")

        self.assertEqual(result, [asset])
        self.assertEqual(ws.recv.await_count, 2)
        fallback.assert_awaited_once_with(prompt_id)


if __name__ == "__main__":
    unittest.main()
