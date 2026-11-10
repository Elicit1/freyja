"""Completion waits on WebSocket events; snapshots never become a polling loop."""

import asyncio
import json
import unittest
import httpx
from unittest.mock import AsyncMock, Mock, patch

from comfy_client import ComfyAsyncClient


class MediaUploadTests(unittest.IsolatedAsyncioTestCase):
    async def test_local_paths_and_non_http_schemes_are_rejected(self):
        client = ComfyAsyncClient("http://comfy", "ws://comfy/ws")
        client.upload_file_to_comfy = AsyncMock()
        for source in (__file__, "/proc/self/environ", "file:///etc/passwd", "ftp://server/file"):
            with self.assertRaisesRegex(ValueError, "local paths are forbidden"):
                await client.load_and_upload_media(source)
        client.upload_file_to_comfy.assert_not_awaited()

    async def test_same_named_sources_and_repeated_data_uris_get_distinct_names(self):
        client = ComfyAsyncClient("http://comfy", "ws://comfy/ws")
        client._http_client = httpx.AsyncClient(transport=httpx.MockTransport(
            lambda request: httpx.Response(200, content=request.url.path.encode())))
        client.upload_file_to_comfy = AsyncMock(side_effect=lambda raw, name: name)
        try:
            names = []
            for source in ("https://archive.test/alice/reference.png", "https://archive.test/bob/reference.png",
                           "data:image/png;base64,QQ==", "data:image/png;base64,QQ=="):
                names.append(await client.load_and_upload_media(source))
            self.assertEqual(len(set(names)), 4)
            self.assertTrue(all(name.endswith(".png") for name in names))
            self.assertNotEqual(client.upload_file_to_comfy.await_args_list[0].args[0],
                                client.upload_file_to_comfy.await_args_list[1].args[0])
        finally:
            await client.close()


class _SilentWebSocket:
    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def recv(self):
        await asyncio.Event().wait()


class _SuccessWebSocket(_SilentWebSocket):
    async def recv(self):
        return json.dumps({"type": "execution_success", "data": {"prompt_id": "prompt-1"}})


class TestComfyHistoryRecovery(unittest.IsolatedAsyncioTestCase):
    async def test_disconnect_snapshot_recovers_completed_result_without_polling(self):
        client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
        asset = {"filename": "result.mp4", "type": "output"}
        client._get_completed_history_assets = AsyncMock(side_effect=[[], [asset]])
        with patch("comfy_client.websockets.connect", return_value=_SilentWebSocket()):
            result = await client.wait_for_completion("prompt-1", "client-1", timeout_override=0.01)

        self.assertEqual(result, [asset])
        self.assertEqual(client._get_completed_history_assets.await_count, 2)

    async def test_cached_result_is_found_before_websocket_subscription(self):
        client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
        asset = {"filename": "cached.mp4", "type": "output"}
        client._get_completed_history_assets = AsyncMock(return_value=[asset])

        with patch("comfy_client.websockets.connect") as connect:
            result = await client.wait_for_completion("prompt-1", "client-1")

        self.assertEqual(result, [asset])
        connect.assert_called_once()

    async def test_execution_success_event_completes_without_executing_event(self):
        client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
        asset = {"filename": "result.mp4", "type": "output"}
        client._get_completed_history_assets = AsyncMock(side_effect=[[], [asset]])

        with patch("comfy_client.websockets.connect", return_value=_SuccessWebSocket()):
            result = await client.wait_for_completion("prompt-1", "client-1")

        self.assertEqual(result, [asset])
        self.assertEqual(client._get_completed_history_assets.await_count, 2)

    async def test_silent_websocket_times_out_without_periodic_history_requests(self):
        client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
        client._get_completed_history_assets = AsyncMock(return_value=[])
        with patch("comfy_client.websockets.connect", return_value=_SilentWebSocket()):
            with self.assertRaises(TimeoutError):
                await client.wait_for_completion("prompt-1", "client-1", timeout_override=0.01)
        self.assertEqual(client._get_completed_history_assets.await_count, 2)

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
