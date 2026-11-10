import json
import time
import unittest
from unittest.mock import AsyncMock, Mock, patch
from comfy_client import ComfyAsyncClient
from task_tracker import TaskTracker
from render_progress import follow


class Socket:
    def __init__(self, messages): self.messages = iter(messages)
    async def __aenter__(self): return self
    async def __aexit__(self, *args): return False
    async def recv(self): return json.dumps(next(self.messages))


class RenderProgressTests(unittest.IsolatedAsyncioTestCase):
    async def test_only_the_exact_prompt_updates_node_and_step_progress(self):
        messages = [
            {"type":"progress","data":{"value":99,"max":100}},
            {"type":"executing","data":{"prompt_id":"other","node":"9"}},
            {"type":"progress","data":{"prompt_id":"other","node":"9","value":90,"max":100}},
            {"type":"execution_cached","data":{"prompt_id":"target","nodes":["1"]}},
            {"type":"executing","data":{"prompt_id":"target","node":"2"}},
            {"type":"progress","data":{"node":"2","value":3,"max":8}},
            {"type":"executing","data":{"prompt_id":"target","node":"3"}},
            {"type":"execution_success","data":{"prompt_id":"target"}},
        ]
        client = ComfyAsyncClient("http://unused", "ws://unused/ws")
        asset = {"filename":"result.mp4"}
        client._get_completed_history_assets = AsyncMock(side_effect=[[], [asset]])
        events = []
        async def record(prompt, data):
            self.assertEqual("target", prompt); events.append(dict(data))
        workflow = {"1":{"class_type":"Loader"},"2":{"class_type":"Sampler"},"3":{"class_type":"Decoder"}}
        with patch("comfy_client.websockets.connect", return_value=Socket(messages)), patch("render_progress.task_tracker.record_progress", side_effect=record):
            self.assertEqual([asset], await follow(client,"target","client",workflow,5))
        self.assertEqual(4,len(events))
        self.assertEqual((3,8,"Sampler"),(events[2]["value"],events[2]["max"],events[2]["nodeType"]))
        self.assertNotIn("value",events[-1]); self.assertEqual(2,events[-1]["completedNodes"])
        self.assertEqual({},client._progress_listeners)

    async def test_progress_is_prompt_scoped_expires_and_survives_redis_failure(self):
        with patch.object(TaskTracker,"_init_redis"):
            tracker = TaskTracker()
        tracker._redis_client = None
        tracker._redis_client = Mock(set=AsyncMock(side_effect=OSError("offline")),get=AsyncMock(side_effect=OSError("offline")))
        await tracker.record_progress("one",{"value":2,"max":8})
        self.assertIsNone(await tracker.progress("two"))
        sample = await tracker.progress("one"); sample["value"] = 99
        self.assertEqual(2,(await tracker.progress("one"))["value"])
        tracker._memory_progress["one"] = (time.time()-1,{"value":2})
        self.assertIsNone(await tracker.progress("one"))

    async def test_ordinary_async_render_returns_identity_without_requiring_enhancement_options(self):
        import main
        from schemas import ImageGenerationRequest
        from starlette.requests import Request
        from test_video_reference import setup_model
        model, config = setup_model()
        upstream = AsyncMock(); upstream.submit_prompt.return_value = "ordinary-prompt"
        pool = Mock(get_client_for_node=AsyncMock(return_value=upstream))
        engine = Mock(); engine.assemble_workflow.return_value = {}
        redis = AsyncMock(); redis.set.return_value = True
        request = ImageGenerationRequest(model=model.model_name, size="960x544", prompt="one shot", task_id="ordinary-task")
        http = Request({"type":"http","headers":[(b"x-freyja-async-submit",b"true")]})
        with patch.object(main,"config",config), patch.object(main,"get_workflow_engine",return_value=engine), patch.object(main,"get_client_pool",return_value=pool), patch.object(main.task_tracker,"_redis_client",redis), patch.object(main.task_tracker,"register",new=AsyncMock()), patch("render_progress.start") as start:
            result = await main.generate_images(request,http)
        self.assertEqual("ordinary-prompt",result.execution["promptId"])
        self.assertEqual("ordinary-task",result.execution["taskId"])
        start.assert_called_once(); upstream.wait_for_completion.assert_not_awaited()

    async def test_full_node_progress_does_not_change_authoritative_running_status(self):
        import main
        client = Mock(task_state=AsyncMock(return_value={"status":"RUNNING"}))
        pool = Mock(get_client_for_node=AsyncMock(return_value=client))
        with patch.object(main.task_tracker,"find",new=AsyncMock(return_value=("prompt","node"))), patch.object(main.task_tracker,"progress",new=AsyncMock(return_value={"value":8,"max":8})), patch.object(main,"config",Mock(get_node=Mock(return_value=Mock()))), patch.object(main,"get_client_pool",return_value=pool):
            _, state = await main.tracked_task_state("task")
        self.assertEqual("RUNNING",state["status"]); self.assertEqual(8,state["progress"]["value"])
        client.task_state.assert_awaited_once_with("prompt")
