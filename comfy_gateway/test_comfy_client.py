"""Regression tests for ComfyUI completion when its WebSocket event is lost."""

import asyncio
import json
import unittest
from unittest.mock import AsyncMock, Mock, patch

from comfy_client import ComfyAsyncClient


class _SilentWebSocket:
    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def recv(self):
        raise asyncio.TimeoutError


class _SuccessWebSocket(_SilentWebSocket):
    async def recv(self):
        return json.dumps({"type": "execution_success", "data": {"prompt_id": "prompt-1"}})


class TestComfyHistoryRecovery(unittest.IsolatedAsyncioTestCase):
    async def test_completed_history_recovers_missing_websocket_event(self):
        client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
        asset = {"filename": "result.mp4", "type": "output"}
        client._get_completed_history_assets = AsyncMock(side_effect=[[], [asset]])
        clock = Mock()
        clock.time.side_effect = [0.0, 11.0]

        with patch("comfy_client.websockets.connect", return_value=_SilentWebSocket()), \
             patch("comfy_client.asyncio.get_event_loop", return_value=clock):
            result = await client.wait_for_completion("prompt-1", "client-1")

        self.assertEqual(result, [asset])
        self.assertEqual(client._get_completed_history_assets.await_count, 2)

    async def test_cached_result_is_found_before_websocket_subscription(self):
        client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
        asset = {"filename": "cached.mp4", "type": "output"}
        client._get_completed_history_assets = AsyncMock(return_value=[asset])

        with patch("comfy_client.websockets.connect") as connect:
            result = await client.wait_for_completion("prompt-1", "client-1")

        self.assertEqual(result, [asset])
        connect.assert_not_called()

    async def test_execution_success_event_completes_without_executing_event(self):
        client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
        asset = {"filename": "result.mp4", "type": "output"}
        client._poll_history_fallback = AsyncMock(return_value=[asset])
        client._get_completed_history_assets = AsyncMock(return_value=[])

        with patch("comfy_client.websockets.connect", return_value=_SuccessWebSocket()):
            result = await client.wait_for_completion("prompt-1", "client-1")

        self.assertEqual(result, [asset])
        client._poll_history_fallback.assert_awaited_once_with("prompt-1")

    async def test_unfinished_history_does_not_return_assets(self):
        client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
        http_client = Mock()
        response = Mock(status_code=200)
        response.json.return_value = {
            "prompt-1": {
                "status": {"completed": False},
                "outputs": {"92": {"images": [{"filename": "partial.mp4"}]}},
            }
        }
        http_client.get = AsyncMock(return_value=response)
        client.get_http_client = AsyncMock(return_value=http_client)

        self.assertEqual(await client._get_completed_history_assets("prompt-1"), [])


if __name__ == "__main__":
    unittest.main()
