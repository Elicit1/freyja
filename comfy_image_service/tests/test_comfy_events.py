"""ComfyUI WebSocket completion protocol with synthetic frames and mocked HTTP history."""
import asyncio
import json
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from unittest.mock import patch

import httpx
from websockets.asyncio.server import serve

from comfy_image_service.comfy import ComfyClient
from comfy_image_service.config import Settings
from comfy_image_service.models import ServiceError


class ComfyEventTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.connections = asyncio.Queue()
        self.requests = []
        self.available = False
        self.history_read = asyncio.Event()
        self.waiters = []
        self.entry = {"status": {"status_str": "success", "completed": True}, "outputs": {}}

        async def handler(socket):
            await self.connections.put(socket)
            await socket.wait_closed()

        self.server = await serve(handler, "127.0.0.1", 0)
        port = self.server.sockets[0].getsockname()[1]

        def respond(request):
            self.requests.append((request.method, request.url.path))
            self.assertEqual(request.method, "GET", "Event tests must never submit a prompt")
            self.assertEqual(request.url.path, "/history/target")
            self.history_read.set()
            return httpx.Response(200, json={"target": self.entry} if self.available else {})

        settings = Settings(home=Path("."), comfy_url=f"http://127.0.0.1:{port}", timeout_seconds=1)
        self.client = ComfyClient(settings, httpx.MockTransport(respond))

    async def asyncTearDown(self):
        for waiter in self.waiters:
            if not waiter.done():
                waiter.cancel()
        await asyncio.gather(*self.waiters, return_exceptions=True)
        await self.client.close()
        self.server.close()
        await self.server.wait_closed()

    def wait(self, client_id="persisted-job-id"):
        waiter = asyncio.create_task(self.client.wait("target", client_id=client_id))
        self.waiters.append(waiter)
        return waiter

    async def socket(self):
        socket = await asyncio.wait_for(self.connections.get(), 0.5)
        await asyncio.wait_for(self.history_read.wait(), 0.5)
        self.history_read.clear()
        self.assertEqual(parse_qs(urlsplit(socket.request.path).query)["clientId"], ["persisted-job-id"])
        return socket

    @staticmethod
    async def send(socket, kind, data):
        await socket.send(json.dumps({"type": kind, "data": data}))

    async def test_idle_wait_does_not_poll_and_ignores_previews_and_unrelated_events(self):
        waiter = self.wait()
        socket = await self.socket()
        for raw in (b"binary-preview", "invalid-json", "[]", '{"data":null}'):
            await socket.send(raw)
        await self.send(socket, "execution_success", {"prompt_id": "another-job"})
        await self.send(socket, "executing", {"prompt_id": "target", "node": "12"})
        await self.send(socket, "progress", {"prompt_id": "target", "value": 1, "max": 6})
        await asyncio.sleep(0.04)  # Observe an idle synthetic connection, not a render.
        self.assertFalse(waiter.done())
        self.assertEqual(len(self.requests), 1)
        self.available = True
        await self.send(socket, "executing", {"prompt_id": "target", "node": None})
        self.assertEqual(await asyncio.wait_for(waiter, 0.3), self.entry)
        self.assertEqual(len(self.requests), 2)

    async def test_success_before_history_persistence_waits_for_final_event(self):
        waiter = self.wait()
        socket = await self.socket()
        await self.send(socket, "execution_success", {"prompt_id": "target"})
        await asyncio.wait_for(self.history_read.wait(), 0.3)
        self.assertFalse(waiter.done())
        self.available = True
        await self.send(socket, "executing", {"prompt_id": "target", "node": None})
        self.assertEqual(await asyncio.wait_for(waiter, 0.3), self.entry)
        self.assertEqual(len(self.requests), 3)

    async def test_cached_result_is_found_after_subscription_without_a_new_event(self):
        self.available = True
        waiter = self.wait()
        await self.socket()
        self.assertEqual(await asyncio.wait_for(waiter, 0.3), self.entry)
        self.assertEqual(len(self.requests), 1)

    async def test_reconnection_uses_original_client_and_history_without_resubmission(self):
        waiter = self.wait()
        first = await self.socket()
        self.available = True  # The backend completed while the connection was lost.
        await first.close()
        await self.socket()
        self.assertEqual(await asyncio.wait_for(waiter, 0.3), self.entry)
        self.assertEqual(len(self.requests), 2)
        self.assertTrue(all(method == "GET" for method, _ in self.requests))

    async def test_execution_error_and_interruption_are_terminal_backend_evidence(self):
        for kind in ("execution_error", "execution_interrupted"):
            with self.subTest(kind=kind):
                waiter = self.wait()
                socket = await self.socket()
                await self.send(socket, kind, {"prompt_id": "target", "exception_message": "synthetic error"})
                result = await asyncio.wait_for(waiter, 0.3)
                self.assertEqual(result["status"]["status_str"], "error")
                self.assertEqual(result["status"]["messages"][0][0], kind)
                self.history_read.clear()

    async def test_silent_connection_times_out_without_periodic_history_reads(self):
        object.__setattr__(self.client.settings, "timeout_seconds", 0.1)
        waiter = self.wait()
        await self.socket()
        with self.assertRaises(ServiceError) as caught:
            await waiter
        self.assertEqual(caught.exception.code, "backend_timeout")
        self.assertEqual(len(self.requests), 2)  # Subscribe snapshot plus one final recovery snapshot.

    async def test_unavailable_socket_recovers_only_an_already_completed_history(self):
        self.available = True
        with patch("comfy_image_service.comfy.connect", side_effect=OSError("synthetic socket outage")):
            self.assertEqual(await self.client.wait("target", client_id="persisted-job-id"), self.entry)
        self.assertEqual(len(self.requests), 1)

    async def test_wait_cancellation_closes_the_socket_and_never_interrupts_or_resubmits(self):
        waiter = self.wait()
        socket = await self.socket()
        waiter.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await waiter
        await asyncio.wait_for(socket.wait_closed(), 0.3)
        self.assertEqual(self.requests, [("GET", "/history/target")])

    async def test_resumed_wait_requires_the_original_client_identity(self):
        with self.assertRaises(ValueError):
            await self.client.wait("target")
        self.assertEqual(self.requests, [])
