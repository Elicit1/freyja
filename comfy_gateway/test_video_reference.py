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
REF_MODELS = (
    "minimax-h3-ref2va", "minimax-h3-face-refine", "minimax-h3-ref",
    "minimax-h3-ref-turbo4-face-refine", "minimax-h3-ref-turbo8",
    "minimax-h3-ref-turbo8-face-refine",
)


def setup_model(verified=True, model_name="minimax-h3-ref-turbo8"):
    node = NodeConfig(**yaml.safe_load((BASE / "nodes/machine_default.yaml").read_text(encoding="utf-8")))
    model = next(m for m in node.models if m.model_name == model_name)
    model.node_id = node.node_id
    model.reference_video = ReferenceVideoCapability(
        max_videos=1, min_clip_seconds=0.1, max_clip_seconds=5, max_total_clip_seconds=5,
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
    def test_all_ref_models_declare_video_capability_without_claiming_online_acceptance(self):
        node = NodeConfig(**yaml.safe_load((BASE / "nodes/machine_default.yaml").read_text(encoding="utf-8")))
        models = {model.model_name: model for model in node.models
                  if model.handler in {"minimax_h3_ref2va", "minimax_h3_face_refine"}}
        self.assertEqual(set(models), set(REF_MODELS))
        for name, model in models.items():
            with self.subTest(model=name):
                self.assertIsNotNone(model.reference_video)
                self.assertEqual(model.reference_video.status, "UNVERIFIED")
                self.assertEqual(model.reference_video.max_videos, 3)

    def test_all_ref_models_bind_three_videos_through_each_pass_to_final_output(self):
        for name in REF_MODELS:
            with self.subTest(model=name):
                model, config = setup_model(model_name=name)
                model.reference_video.max_videos = 3
                model.reference_video.verified_revision = workflow_revision(model, BASE)
                refs = [{**binding(index == 1), "referenceId": f"video{index + 1}",
                         "referenceVideo": f"https://archive.test/{index}.mp4"} for index in range(3)]
                req = request(model)
                req.references = [ReferenceBinding.model_validate(ref) for ref in refs]
                req.prompt = "Reference <Video 1>, <Video 2>, <Video 3> and Picture 1"
                engine = WorkflowEngine(config)
                graph = engine.assemble_workflow(req, uploaded_ref_images=["portrait.png"],
                    uploaded_ref_audios=["voice.wav"], uploaded_ref_videos=[{
                        "reference_id": f"video{index + 1}", "filename": f"task/{index}.mp4",
                        "include_audio": index == 1} for index in range(3)])
                face = model.handler == "minimax_h3_face_refine"
                self.assertEqual(graph["130"]["inputs"]["images"], ["165" if face else "122", 0])
                self.assertEqual(graph["130"]["inputs"]["audio"], ["121", 0])
                self.assertEqual(graph["124"]["inputs"]["steps"], model.default_steps)
                self.assertEqual(sum(node["class_type"] == "VHS_LoadVideo" for node in graph.values()), 3)
                self.assertEqual(graph["136"]["inputs"]["ref_audios.ref_audio_0"], ["136_ref_aud_0", 0])
                for cond in (("136", "158") if face else ("136",)):
                    self.assertEqual(graph[cond]["inputs"]["prompt"], req.prompt)
                    image_loader = graph[cond]["inputs"]["ref_images.ref_image_0"][0]
                    self.assertEqual(graph[image_loader]["inputs"]["image"], "portrait.png")
                    for index in range(3):
                        loader = f"136_ref_vid_{index}"
                        self.assertEqual(graph[cond]["inputs"][f"ref_videos.ref_video_{index}"], [loader, 0])
                        self.assertEqual(graph[loader]["inputs"]["video"], f"task/{index}.mp4")
                        self.assertEqual(f"ref_video_audios.ref_video_audio_{index}" in graph[cond]["inputs"], index == 1)
                    self.assertEqual(graph[cond]["inputs"]["ref_video_audios.ref_video_audio_1"], ["136_ref_vid_1", 2])
                if face:
                    self.assertEqual(graph["162"]["inputs"]["steps"], engine.get_template(model)["162"]["inputs"]["steps"])
                    self.assertEqual(graph["158"]["inputs"]["ref_audios.ref_audio_0"], ["121", 0])
                empty = engine.assemble_workflow(ImageGenerationRequest(model=name, size="960x544", prompt="text only"))
                self.assertFalse(any(node["class_type"] == "VHS_LoadVideo" for node in empty.values()))
                for cond in (("136", "158") if face else ("136",)):
                    self.assertFalse(any(key.startswith(("ref_videos.", "ref_video_audios.")) for key in empty[cond]["inputs"]))

    def test_every_ref_variant_still_requires_its_own_acceptance(self):
        for name in REF_MODELS:
            with self.subTest(model=name):
                model, config = setup_model(False, name)
                with self.assertRaisesRegex(ValueError, "UNVERIFIED"):
                    WorkflowEngine(config).assemble_workflow(request(model))

    def test_face_refine_disconnected_second_pass_is_rejected(self):
        model, config = setup_model(model_name="minimax-h3-ref-turbo8-face-refine")
        engine = WorkflowEngine(config)
        template = engine.get_template(model)
        template["165"]["inputs"].pop("refined_crops")
        engine._template_cache[str((BASE / model.template_file).resolve())] = template
        with self.assertRaisesRegex(ValueError, "disconnected from output"):
            engine.assemble_workflow(request(model), uploaded_ref_videos=[{"filename": "clip.mp4", "include_audio": False}])

    def test_other_handlers_cannot_opt_in_by_copying_ref_configuration(self):
        model, _ = setup_model()
        model.handler = "standard"
        model.reference_video.verified_revision = workflow_revision(model, BASE)
        with self.assertRaisesRegex(ValueError, "handler has not been accepted"):
            require_verified(model, BASE)

    def test_multiple_videos_use_the_configured_model_limit_and_separate_inputs(self):
        model, config = setup_model()
        model.reference_video.max_videos = 2
        model.reference_video.verified_revision = workflow_revision(model, BASE)
        refs = [binding(), {**binding(True), "referenceId": "video2", "referenceVideo": "https://archive.test/second.mp4"}]
        req = ImageGenerationRequest(model=model.model_name, size="960x544", prompt="Reference <Video 1> and <Video 2>", references=refs,
                                     prompt_format="MINIMAX_H3_REF2VA_VIDEO_V1", reference_video_revision=model.reference_video.verified_revision)
        uploaded = [{"reference_id": "video1", "filename": "one.mp4", "include_audio": False}, {"reference_id": "video2", "filename": "two.mp4", "include_audio": True}]
        graph = WorkflowEngine(config).assemble_workflow(req, uploaded_ref_videos=uploaded)
        self.assertEqual(graph["136"]["inputs"]["ref_videos.ref_video_1"], ["136_ref_vid_1", 0])
        self.assertEqual(graph["136"]["inputs"]["ref_video_audios.ref_video_audio_1"], ["136_ref_vid_1", 2])
        self.assertEqual(graph["136_ref_vid_1"]["inputs"]["video"], "two.mp4")
        model.reference_video.max_videos = 1
        model.reference_video.verified_revision = workflow_revision(model, BASE)
        with self.assertRaisesRegex(ValueError, "too many videos"):
            WorkflowEngine(config).assemble_workflow(req, uploaded_ref_videos=uploaded)

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
            {"codec_type": "video", "width": 384, "height": 256, "nb_read_frames": "96", "r_frame_rate": "24/1"},
            {"codec_type": "audio"}],
            "format": {"duration": "4"}}).encode(), b""))
        try:
            with patch("reference_media.asyncio.create_subprocess_exec", new_callable=AsyncMock, return_value=process):
                for _ in range(2):
                    result = await upload_reference_video(client, ReferenceBinding.model_validate(binding(digest=hashlib.sha256(raw).hexdigest())), model.reference_video, {"archive.test"})
                    self.assertEqual(result["filename"], "subfolder/unique.mp4")
            calls = client.upload_file_to_comfy.await_args_list
            self.assertEqual(calls[0].args[0], raw)
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
