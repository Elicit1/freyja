import hashlib
import json
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import yaml
from pydantic import ValidationError
from starlette.requests import Request

from config import GatewayConfig, NodeConfig
from comfy_client import ComfyAsyncClient
from reference_media import upload_reference_video
from reference_prompt import ReferenceBinding
from schemas import ImageGenerationRequest
from video_reference import ReferenceVideoCapability, workflow_revision, require_verified
from workflow_engine import WorkflowEngine


BASE = Path(__file__).resolve().parent


def setup_model(verified=True):
    node = NodeConfig(**yaml.safe_load((BASE / "nodes/machine_default.yaml").read_text(encoding="utf-8")))
    model = next(m for m in node.models if m.model_name == "minimax-h3-ref-turbo8")
    model.node_id = node.node_id
    model.reference_video = ReferenceVideoCapability(
        min_clip_seconds=0.1, max_clip_seconds=5, max_total_clip_seconds=5,
        max_decoded_frames=120, max_decode_width=768, max_decode_height=768,
        max_mixed_assets=12, supports_video_audio=True)
    if verified:
        model.reference_video.verified_backend = node.http_url
        model.reference_video.verified_revision = workflow_revision(model, BASE)
        model.reference_video.status = "VERIFIED"
    config = GatewayConfig(models=[model], nodes={node.node_id: node}, model_to_nodes={model.model_name: [node.node_id]})
    return model, config


def binding(audio=False, digest="a" * 64):
    return {"referenceId": "video1", "referenceType": "REFERENCE_VIDEO",
            "referenceVideo": "https://archive.test/clip.mp4", "contentSha256": digest, "includeAudio": audio}


def request(model, audio=False, **extra):
    return ImageGenerationRequest(model=model.model_name, size="960x544", prompt="Reference <Video 1>",
                                  references=[binding(audio)], node_id=model.node_id,
                                  prompt_format="MINIMAX_H3_REF2VA_VIDEO_V1",
                                  reference_video_revision=model.reference_video.verified_revision, **extra)


class VideoReferenceAssemblyTests(unittest.TestCase):
    def test_frames_reach_conditioning_and_actual_output(self):
        model, config = setup_model()
        engine = WorkflowEngine(config)
        for audio in (False, True):
            workflow = engine.assemble_workflow(request(model, audio), uploaded_ref_videos=[{
                "reference_id": "video1", "filename": "task1/unique.mp4", "include_audio": audio}])
            self.assertEqual(workflow["136"]["inputs"]["ref_videos.ref_video_0"], ["136_ref_vid_0", 0])
            self.assertEqual(workflow["136_ref_vid_0"]["class_type"], "VHS_LoadVideo")
            self.assertEqual(workflow["136_ref_vid_0"]["inputs"]["video"], "task1/unique.mp4")
            self.assertEqual("ref_video_audios.ref_video_audio_0" in workflow["136"]["inputs"], audio)
            if audio:
                self.assertEqual(workflow["136"]["inputs"]["ref_video_audios.ref_video_audio_0"], ["136_ref_vid_0", 2])
            self.assertEqual(workflow["124"]["inputs"]["steps"], 8)
        empty = engine.assemble_workflow(ImageGenerationRequest(model=model.model_name, size="960x544", prompt="text only"))
        self.assertFalse(any(key.startswith("136_ref_vid_") for key in empty))
        self.assertNotIn("ref_videos.ref_video_0", empty["136"]["inputs"])

    def test_unverified_and_changed_adapter_are_rejected(self):
        model, config = setup_model(False)
        with self.assertRaisesRegex(ValueError, "UNVERIFIED"):
            WorkflowEngine(config).assemble_workflow(request(model))
        model, config = setup_model()
        model.reference_video.decode_fps = 30
        with self.assertRaisesRegex(ValueError, "WORKFLOW_MISMATCH"):
            WorkflowEngine(config).assemble_workflow(request(model))

    def test_missing_upload_and_model_partial_match_fail(self):
        model, config = setup_model()
        with self.assertRaisesRegex(ValueError, "missing uploaded"):
            WorkflowEngine(config).assemble_workflow(request(model))
        req = request(model)
        req.model = "minimax-h3-ref-turbo8-unknown"
        with self.assertRaisesRegex(ValueError, "not supported"):
            WorkflowEngine(config).assemble_workflow(req)

    def test_backend_change_and_unknown_contract_version_require_acceptance(self):
        model, _ = setup_model()
        with self.assertRaisesRegex(ValueError, "backend has not been accepted"):
            require_verified(model, BASE, "http://another-node:8188")
        with self.assertRaises(ValidationError):
            ReferenceVideoCapability(schema_version=2)

    def test_top_level_reference_conflicts_and_local_video_paths_fail(self):
        with self.assertRaises(ValidationError):
            ImageGenerationRequest(prompt="test", references=[binding()], extra_body={"references": [binding(True)]})
        req = ImageGenerationRequest(prompt="test", references=[{**binding(), "referenceVideo": "F:/secret.mp4"}])
        with self.assertRaisesRegex(ValueError, "HTTP"):
            req.get_all_ref_videos()

    def test_disconnected_output_fails_instead_of_silently_ignoring_video(self):
        model, config = setup_model()
        engine = WorkflowEngine(config)
        template = engine.get_template(model)
        template["130"]["inputs"] = {}
        engine._template_cache[str((BASE / model.template_file).resolve())] = template
        with self.assertRaisesRegex(ValueError, "disconnected from output"):
            engine.assemble_workflow(request(model), uploaded_ref_videos=[{"filename": "clip.mp4", "include_audio": False}])

    def test_empty_video_selection_removes_template_demo_loader(self):
        model, config = setup_model()
        engine = WorkflowEngine(config)
        template = engine.get_template(model)
        template["sample_video"] = {"class_type": "VHS_LoadVideo", "inputs": {"video": "demo.mp4"}}
        template["136"]["inputs"]["ref_videos.ref_video_0"] = ["sample_video", 0]
        engine._template_cache[str((BASE / model.template_file).resolve())] = template
        result = engine.assemble_workflow(ImageGenerationRequest(model=model.model_name, size="960x544", prompt="no video"))
        self.assertNotIn("sample_video", result)
        self.assertNotIn("ref_videos.ref_video_0", result["136"]["inputs"])


class VideoTransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_digest_and_redirect_host_are_checked_before_upload(self):
        model, _ = setup_model()
        client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
        client.upload_file_to_comfy = AsyncMock()
        client._http_client = httpx.AsyncClient(transport=httpx.MockTransport(lambda req: httpx.Response(200, content=b"different bytes")))
        try:
            with self.assertRaisesRegex(ValueError, "SOURCE_CHANGED"):
                await upload_reference_video(client, ReferenceBinding.model_validate(binding()), model.reference_video, {"archive.test"})
            client.upload_file_to_comfy.assert_not_called()
        finally:
            await client.close()
        client._http_client = httpx.AsyncClient(transport=httpx.MockTransport(lambda req: httpx.Response(302, headers={"location": "http://internal.test/secret"})))
        try:
            with self.assertRaisesRegex(ValueError, "SOURCE_INVALID"):
                await upload_reference_video(client, ReferenceBinding.model_validate(binding()), model.reference_video, {"archive.test"})
            client.upload_file_to_comfy.assert_not_called()
        finally:
            await client.close()

    async def test_verified_decoding_uses_unique_names_and_preserves_input_subfolder(self):
        model, _ = setup_model()
        raw = b"prepared clip bytes"
        client = ComfyAsyncClient("http://comfy:8188", "ws://comfy:8188/ws")
        client._http_client = httpx.AsyncClient(transport=httpx.MockTransport(lambda req: httpx.Response(200, content=raw)))
        client.upload_file_to_comfy = AsyncMock(return_value="subfolder/unique.mp4")
        process = MagicMock(returncode=0)
        process.communicate = AsyncMock(return_value=(json.dumps({"streams": [
            {"codec_type": "video", "width": 384, "height": 256, "nb_read_frames": "96", "r_frame_rate": "24/1"}],
            "format": {"duration": "4"}}).encode(), b""))
        try:
            with patch("reference_media.asyncio.create_subprocess_exec", new_callable=AsyncMock, return_value=process):
                for _ in range(2):
                    result = await upload_reference_video(client, ReferenceBinding.model_validate(binding(digest=hashlib.sha256(raw).hexdigest())), model.reference_video, {"archive.test"})
                    self.assertEqual(result["filename"], "subfolder/unique.mp4")
            calls = client.upload_file_to_comfy.await_args_list
            self.assertNotEqual(calls[0].args[1], calls[1].args[1])
            self.assertTrue(calls[0].kwargs["preserve_subfolder"])
        finally:
            await client.close()

    async def test_unverified_request_does_not_upload_any_media(self):
        import main
        model, config = setup_model(False)
        client = AsyncMock()
        pool = MagicMock()
        pool.get_client_for_node = AsyncMock(return_value=client)
        scope = {"type": "http", "method": "POST", "path": "/v1/videos/generations", "headers": [], "scheme": "http"}
        with patch.object(main, "config", config), patch.object(main, "get_client_pool", return_value=pool), \
                patch.object(main, "get_workflow_engine", return_value=WorkflowEngine(config)):
            with self.assertRaisesRegex(ValueError, "UNVERIFIED"):
                await main.generate_images(request(model), Request(scope))
        client.load_and_upload_ref_image.assert_not_called()
        client.load_and_upload_media.assert_not_called()
        client.upload_file_to_comfy.assert_not_called()


if __name__ == "__main__":
    unittest.main()
