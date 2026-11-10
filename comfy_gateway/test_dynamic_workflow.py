import copy
import unittest
from unittest.mock import AsyncMock, patch
from starlette.requests import Request
from pydantic import ValidationError
from dynamic_workflow import DynamicStage, UpscaleTiling, UpscaleContext, describe, validate_stage_nodes, stage_nodes, require_stage, installed_latent_models
from schemas import ImageGenerationRequest
from test_video_reference import setup_model, BASE
from test_continuation_modes import assert_acyclic
from workflow_engine import WorkflowEngine
from test_render_input_validation import bind_contract
from video_reference import workflow_revision


class DynamicWorkflowTests(unittest.TestCase):
    def test_model_choices_come_from_the_installed_node_schema(self):
        for field in ([["h3.safetensors", "h3.safetensors", "", None]],
                      ["COMBO", {"options": ["h3.safetensors"]}]):
            nodes = {"MiniMaxH3LatentUpscaleWithModel": {}, "LatentUpscaleModelLoader": {
                "input": {"required": {"model_name": field}}}}
            self.assertEqual(installed_latent_models(nodes), ["h3.safetensors"])
            del nodes["MiniMaxH3LatentUpscaleWithModel"]
            self.assertEqual(installed_latent_models(nodes), [])

    def graph(self, stage, model_name="minimax-h3-ref-turbo8", **kwargs):
        model, cfg = setup_model(model_name)
        if model.handler == "minimax_h3_fl2va":
            model.reference_video = None
        request = ImageGenerationRequest(model=model_name, prompt="A dancer", size="1920x1088" if stage == "UPSCALE" else "960x544", length=124,
            dynamic_workflow={"stage": stage, "revision": describe(model, BASE)["revision"],
                **({"source_url": "http://minio/source.mp4", "source_sha256": "a"*64} if stage != "BASE" else {}),
                "scale": 2 if stage == "UPSCALE" else 1, **kwargs})
        if request.dynamic_workflow.save_latent: request.task_id = "second-pass-execution"
        engine = WorkflowEngine(cfg)
        before = engine.get_template(model)
        graph = engine.assemble_workflow(request, uploaded_ref_images=["person.png"], dynamic_source_filename="source.mp4", dynamic_latent_filename="base.safetensors" if request.dynamic_workflow.source_latent else None,
            dynamic_context_filename="previous-upscale.safetensors" if request.dynamic_workflow.latent_context else None)
        self.assertEqual(before, engine.get_template(model), "stages must never mutate cached templates")
        assert_acyclic(graph)
        return model, request, graph

    def test_second_pass_context_pins_enhanced_tail_and_saves_restored_latent_in_every_memory_mode(self):
        for memory in ("FULL", "TEMPORAL", "SPATIAL"):
            with self.subTest(memory=memory):
                model, request, first = self.graph("UPSCALE", component={"memory":memory}, save_latent=True)
                _, _, following = self.graph("UPSCALE", component={"memory":memory}, save_latent=True,
                    latent_context={"url":"http://minio/previous.safetensors","content_sha256":"c"*64,"frames":22})
                save = following["dynamic_save_latent"]["inputs"]
                self.assertEqual("SECOND_PASS",save["stage"])
                self.assertEqual(first["dynamic_save_latent"]["inputs"]["compatibility"],save["compatibility"])
                self.assertEqual(["dynamic_restore",0],save["latent"])
                self.assertEqual(["dynamic_lift",0],following["dynamic_continue"]["inputs"]["latent"])
                sampler=next(n for n in following.values() if n["class_type"]=="SamplerCustomAdvanced")
                self.assertEqual(["dynamic_continue",0],sampler["inputs"]["latent_image"])
                decoders=[n for n in following.values() if n["class_type"]=="VAEDecode"]
                self.assertTrue(any(["dynamic_restore",0] in n["inputs"].values() for n in decoders))
                self.assertEqual(["dynamic_source",2],following["130"]["inputs"]["audio"])
                self.assertFalse(any(n["class_type"]=="FreyjaH3Continue" for n in following.values()))
                _, _, changed = self.graph("UPSCALE", component={"memory":memory},save_latent=True,denoise=.3)
                self.assertEqual(save["compatibility"],changed["dynamic_save_latent"]["inputs"]["compatibility"],"partial retakes may change denoise")
                cap=describe(model,BASE)
                self.assertTrue(cap["freyjaUpscale"]["secondPassContinuity"])
                required=stage_nodes(request.dynamic_workflow,cap)
                self.assertIn("FreyjaH3SaveLatent",required)

    def test_second_pass_rejects_missing_save_wrong_stage_and_invalid_overlap(self):
        context={"url":"http://minio/previous.safetensors","content_sha256":"c"*64,"frames":22}
        for options in ({"component":{},"latent_context":context},
                        {"save_latent":True},
                        {"component":{},"save_latent":True,"latent_context":{**context,"frames":23}},
                        {"component":{},"save_latent":True,"latent_context":{**context,"frames":124}}):
            with self.subTest(options=options),self.assertRaises((ValidationError,ValueError)):self.graph("UPSCALE",**options)
        with self.assertRaises(ValidationError):self.graph("FACE",save_latent=True)

    def test_second_pass_compatibility_survives_shorter_final_segment_and_new_transport_urls(self):
        model,request,first=self.graph("UPSCALE",component={"memory":"SPATIAL"},save_latent=True)
        request.length=141
        request.prompt="another action"
        request.dynamic_workflow.latent_context=UpscaleContext(
            url="http://minio/new-signature",content_sha256="c"*64,frames=39)
        # Use the same test configuration without modifying the first graph.
        _,cfg=setup_model()
        following=WorkflowEngine(cfg).assemble_workflow(request,dynamic_source_filename="new-source.mp4",dynamic_context_filename="previous.safetensors")
        self.assertEqual(first["dynamic_save_latent"]["inputs"]["compatibility"],following["dynamic_save_latent"]["inputs"]["compatibility"])
        self.assertEqual(39,following["dynamic_continue"]["inputs"]["frames"])
        assert_acyclic(following)

    def test_base_has_only_one_sampler_and_no_enhancement_nodes(self):
        _, _, graph = self.graph("BASE")
        self.assertEqual(1, sum(n["class_type"] == "SamplerCustomAdvanced" for n in graph.values()))
        self.assertFalse(any(k.startswith("dynamic_") for k in graph))

    def test_native_upscale_loads_original_av_without_vae_encode_and_keeps_soundtrack(self):
        model, request, graph = self.graph("UPSCALE", component={}, source_size="960x544",
            source_latent={"url":"http://minio/base.safetensors","content_sha256":"b"*64})
        self.assertEqual("FreyjaH3LoadLatent",graph["dynamic_encode"]["class_type"])
        self.assertFalse(any(n["class_type"] in {"FreyjaH3UpscaleEncode","H3InjectVideoLatent","ImageScale"} for n in graph.values()))
        self.assertEqual(["dynamic_source",2],graph["130"]["inputs"]["audio"])
        from h3_continuation import compatibility_key
        _, cfg = setup_model()
        base = WorkflowEngine(cfg).assemble_workflow(ImageGenerationRequest(model=model.model_name,prompt="base",size="960x544",length=124))
        self.assertEqual(compatibility_key(base,model.h3_extensions,"960x544"),graph["dynamic_encode"]["inputs"]["compatibility"])
        required=stage_nodes(request.dynamic_workflow,describe(model,BASE))
        self.assertIn("FreyjaH3LoadLatent",required);self.assertNotIn("FreyjaH3UpscaleEncode",required)

    def test_old_node_package_rejects_native_upscale_with_update_instructions(self):
        model, request, _ = self.graph("UPSCALE", component={}, source_size="960x544",
            source_latent={"url":"http://minio/base.safetensors","content_sha256":"b"*64})
        cap = describe(model, BASE)
        nodes = {name:{} for name in stage_nodes(request.dynamic_workflow, cap)}
        del nodes["FreyjaH3LoadLatent"]
        with self.assertRaisesRegex(ValueError, "DYNAMIC_NODES_MISSING: FreyjaH3LoadLatent.*custom_nodes/freyja_h3_continuation"):
            validate_stage_nodes(request.dynamic_workflow, cap, nodes)

    def test_native_source_requires_matching_size_and_upscale_component(self):
        latent={"url":"http://minio/base.safetensors","content_sha256":"b"*64}
        for changes in ({"source_latent":latent},{"source_size":"960x544"},
                        {"source_latent":latent,"source_size":"961x544","component":{}},
                        {"source_latent":latent,"source_size":"960x544"}):
            with self.subTest(changes=changes),self.assertRaises(ValidationError):self.graph("UPSCALE",**changes)

    def test_upscale_is_a_standalone_single_sampler_with_source_audio(self):
        _, _, graph = self.graph("UPSCALE", denoise=.2)
        self.assertEqual(1, sum(n["class_type"] == "SamplerCustomAdvanced" for n in graph.values()))
        scale = graph["dynamic_scale"]["inputs"]
        self.assertEqual((1920, 1088), (scale["width"], scale["height"]))
        self.assertEqual(124, graph["dynamic_frames"]["inputs"]["expected_frames"])
        self.assertEqual(.2, graph["124"]["inputs"]["denoise"])
        self.assertEqual(["dynamic_source", 2], graph["130"]["inputs"]["audio"])
        self.assertNotIn("121", graph)
        self.assertFalse(any(n["class_type"] == "H3FaceTrackCrop" for n in graph.values()))

    def test_freyja_uses_source_encode_latent_lift_and_native_windows(self):
        model, request, graph = self.graph("UPSCALE", component={"memory":"TEMPORAL"})
        self.assertNotIn("dynamic_scale", graph)
        self.assertNotIn("dynamic_inject", graph)
        self.assertEqual("FreyjaH3UpscaleEncode", graph["dynamic_encode"]["class_type"])
        self.assertEqual("MiniMaxH3LatentUpscaleWithModel", graph["dynamic_lift"]["class_type"])
        self.assertEqual(model.default_upscale_model, graph["dynamic_upscale_model"]["inputs"]["model_name"])
        self.assertEqual("MiniMaxH3ContextWindows", graph["dynamic_windows"]["class_type"])
        self.assertEqual(2, graph["124"]["inputs"]["steps"])
        self.assertFalse(graph["dynamic_windows"]["inputs"]["freenoise"])
        self.assertEqual(["dynamic_source",2],graph["130"]["inputs"]["audio"])
        for binding in model.h3_extensions["model_consumers"]:
            self.assertEqual(["dynamic_windows",0],graph[binding["id"]]["inputs"][binding.get("input","model")])

    def test_own_spatial_tiles_do_not_change_numbered_references_or_require_external_plugin(self):
        model, request, graph = self.graph("UPSCALE", component={"memory":"SPATIAL"})
        tile=graph["dynamic_tiles"]
        self.assertEqual("FreyjaH3TileModel",tile["class_type"])
        self.assertEqual(["dynamic_lift",0],tile["inputs"]["latent"])
        self.assertNotIn("dynamic_windows",graph)
        self.assertFalse(any(n["class_type"]=="MiniMaxH3TemporalWindow" for n in graph.values()))
        cap=describe(model,BASE)
        nodes={name:{} for name in stage_nodes(request.dynamic_workflow,cap)}
        validate_stage_nodes(request.dynamic_workflow,cap,nodes)
        del nodes["FreyjaH3TileModel"]
        with self.assertRaisesRegex(ValueError,"DYNAMIC_NODES_MISSING"):validate_stage_nodes(request.dynamic_workflow,cap,nodes)

    def test_learned_lift_preserves_explicit_weight_and_exact_1080_output(self):
        model, request, graph=self.graph("UPSCALE",component={"method":"H3_LEARNED","latent_model":"h3.safetensors"},output_width=1920,output_height=1080)
        self.assertEqual("MiniMaxH3LatentUpscaleWithModel",graph["dynamic_lift"]["class_type"])
        self.assertEqual("FreyjaH3FinishSize",graph["dynamic_finish"]["class_type"])
        self.assertEqual(1080,graph["dynamic_output_frames"]["inputs"]["expected_height"])
        cap=describe(model,BASE);nodes={name:{} for name in stage_nodes(request.dynamic_workflow,cap)}
        nodes["LatentUpscaleModelLoader"]={"input":{"required":{"model_name":[["h3.safetensors"]]}}}
        validate_stage_nodes(request.dynamic_workflow,cap,nodes)
        nodes["LatentUpscaleModelLoader"]["input"]["required"]["model_name"]=[["other.safetensors"]]
        validate_stage_nodes(request.dynamic_workflow,cap,nodes)

    def test_automatic_upscale_uses_configured_default_and_tracks_config_changes(self):
        model, cfg = setup_model()
        original_revision = describe(model, BASE)["revision"]
        model.default_upscale_model = "configured.safetensors"
        capability = describe(model, BASE)
        self.assertNotEqual(original_revision, capability["revision"])
        self.assertEqual(["H3_LEARNED"], capability["freyjaUpscale"]["methods"])
        request = ImageGenerationRequest(model=model.model_name, prompt="test", size="1920x1088", length=124,
            dynamic_workflow={"stage":"UPSCALE", "revision":capability["revision"],
                "source_url":"http://minio/source.mp4", "source_sha256":"a"*64, "scale":2, "component":{}})
        graph = WorkflowEngine(cfg).assemble_workflow(request, dynamic_source_filename="source.mp4")
        self.assertEqual("configured.safetensors", graph["dynamic_upscale_model"]["inputs"]["model_name"])
        self.assertFalse(any(n["class_type"] == "FreyjaH3LatentResize" for n in graph.values()))

    def test_reject_removed_external_tiles_bad_windows_and_forged_crop(self):
        for bad in ({"tile_width":255},{"tile_height":513},{"overlap":512},{"window_frames":55},{"overlap_frames":56}):
            with self.subTest(bad=bad),self.assertRaises(ValidationError):UpscaleTiling(**bad)
        with self.assertRaisesRegex(ValidationError,"EXTERNAL_TILING_REMOVED"):self.graph("UPSCALE",tiling={})
        with self.assertRaisesRegex(ValidationError,"COMPONENT_UPSCALE_ONLY"):self.graph("BASE",component={})
        with self.assertRaises(ValidationError):self.graph("UPSCALE",component={"method":"LATENT_BICUBIC"})
        with self.assertRaisesRegex(ValueError,"CROP_INVALID"):self.graph("UPSCALE",component={},output_width=1280,output_height=720)
        with self.assertRaisesRegex(ValueError,"STEPS_EXCEED"):self.graph("UPSCALE",component={"steps":32})

    def test_face_is_a_standalone_single_sampler_without_base_sampling(self):
        _, _, graph = self.graph("FACE", denoise=.35)
        self.assertNotIn("125", graph)
        self.assertNotIn("122", graph)
        self.assertEqual(1, sum(n["class_type"] == "SamplerCustomAdvanced" for n in graph.values()))
        self.assertEqual(["dynamic_frames", 0], graph["dynamic_face_157"]["inputs"]["images"])
        self.assertEqual(.35, graph["dynamic_face_162"]["inputs"]["denoise"])
        self.assertEqual(["dynamic_source", 2], graph["130"]["inputs"]["audio"])
        face_ref = graph["dynamic_face_158"]["inputs"]["ref_images.ref_image_0"]
        self.assertEqual("person.png", graph[face_ref[0]]["inputs"]["image"])

    def test_all_reference_base_variants_support_standalone_stages(self):
        for model in ("minimax-h3-ref2va", "minimax-h3-ref", "minimax-h3-ref-turbo8"):
            for stage in ("BASE", "UPSCALE", "FACE"):
                with self.subTest(model=model, stage=stage): self.graph(stage, model)

    def test_tiled_upscale_supports_only_reference_model_bindings(self):
        for name in ("minimax-h3-ref2va", "minimax-h3-ref", "minimax-h3-ref-turbo8",
                     "pika-h3-flex-ref2va"):
            with self.subTest(model=name):
                _, _, graph = self.graph("UPSCALE", name, component={"memory":"SPATIAL"})
                self.assertEqual(1, sum(n["class_type"] == "SamplerCustomAdvanced" for n in graph.values()))
                self.assertEqual("FreyjaH3TileModel", graph["dynamic_tiles"]["class_type"])

    def test_fl2va_catalog_and_submission_reject_both_enhancements(self):
        for name in ("minimax-h3-fl2va", "minimax-h3-fl2va-turbo4", "minimax-h3-fl2va-turbo8", "pika-h3-flex-fl2va"):
            model, _ = setup_model(name)
            capability = describe(model, BASE)
            self.assertFalse(capability["supported"]); self.assertEqual([], capability["stages"])
            self.assertFalse(capability["freyjaUpscale"]["supported"])
            for stage in ("UPSCALE", "FACE"):
                request = ImageGenerationRequest(model=name, prompt="motion", size="960x544", length=124,
                    dynamic_workflow={"stage":stage,"revision":"old-revision","source_url":"http://archive/source.mp4","source_sha256":"a"*64,"scale":2 if stage=="UPSCALE" else 1})
                with self.subTest(model=name,stage=stage), self.assertRaisesRegex(ValueError,"REF2VA"):
                    require_stage(request,model,BASE)

    def test_legacy_face_templates_keep_fixed_behavior_without_dynamic_request(self):
        model, cfg = setup_model("minimax-h3-ref-turbo8-face-refine")
        self.assertTrue(describe(model, BASE)["legacyFixedFace"])
        graph = WorkflowEngine(cfg).assemble_workflow(ImageGenerationRequest(model=model.model_name, prompt="person", size="960x544"))
        self.assertEqual(2, sum(n["class_type"] == "SamplerCustomAdvanced" for n in graph.values()))

    def test_reject_stale_revision_and_missing_sources(self):
        model, cfg = setup_model()
        req = ImageGenerationRequest(model=model.model_name, prompt="test", dynamic_workflow={"revision": "stale"})
        with self.assertRaisesRegex(ValueError, "REVISION_CHANGED"): WorkflowEngine(cfg).assemble_workflow(req)
        with self.assertRaises(ValidationError): DynamicStage(stage="FACE", revision="x")
        with self.assertRaises(ValidationError): DynamicStage(stage="BASE", revision="x", source_url="http://x")

    def test_reject_bad_strength_and_unsupported_scale(self):
        for value in (0, -.1, .61, float("nan")):
            with self.assertRaises(ValidationError): DynamicStage(revision="x", denoise=value)
        with self.assertRaises(ValidationError): DynamicStage(revision="x", scale=3)

    def test_full_stage_rejects_continuation_patch_and_large_canvas(self):
        model, cfg = setup_model()
        req = ImageGenerationRequest(model=model.model_name, prompt="test", length=124, size="4096x4096",
            dynamic_workflow={"stage":"FACE", "revision":describe(model,BASE)["revision"], "source_url":"http://x", "source_sha256":"a"*64})
        with self.assertRaisesRegex(ValueError,"TOO_LARGE"): WorkflowEngine(cfg).assemble_workflow(req)

    def test_real_render_contract_preserves_images_videos_and_audio_in_each_stage(self):
        model, cfg = setup_model()
        for stage, component in (("UPSCALE", None), ("UPSCALE", {}), ("UPSCALE", {"memory":"SPATIAL"}), ("FACE", None)):
            for with_audio in (False, True):
                refs = [dict(referenceId="person", referenceType="UPLOAD", referenceImages=["person.png"]),
                        dict(referenceId="motion", referenceType="REFERENCE_VIDEO", referenceVideo="http://archive.test/motion.mp4", contentSha256="b"*64, includeAudio=with_audio)]
                if with_audio: refs.append(dict(referenceId="voice", referenceType="REFERENCE_AUDIO", referenceAudio="voice.wav"))
                request = bind_contract(ImageGenerationRequest(model=model.model_name, prompt="<Picture 1> follows <Video 1>" + (" <Audio 1> <Audio 2>" if with_audio else ""),
                    size="1920x1088" if stage=="UPSCALE" else "960x544",length=124,
                    prompt_format="MINIMAX_H3_REF2VA_VIDEO_V1",references=refs,
                    reference_video_revision=workflow_revision(model,BASE), dynamic_workflow={"stage":stage,"revision":describe(model,BASE)["revision"],
                        "source_url":"http://minio/source.mp4","source_sha256":"a"*64,"scale":2 if stage=="UPSCALE" else 1,"component":component}))
                graph=WorkflowEngine(cfg).assemble_workflow(request,uploaded_ref_images=["person.png"],uploaded_ref_audios=["voice.wav"] if with_audio else [],
                    uploaded_ref_videos=[{"filename":"motion.mp4","include_audio":with_audio}],dynamic_source_filename="source.mp4")
                self.assertEqual(["dynamic_source",2],graph["130"]["inputs"]["audio"])


class DynamicSubmissionSafetyTests(unittest.IsolatedAsyncioTestCase):
    async def test_second_pass_submission_uploads_current_base_and_previous_enhanced_latents_separately(self):
        import main
        from unittest.mock import Mock
        model,cfg=setup_model()
        request=ImageGenerationRequest(model=model.model_name,prompt="dancer",size="1920x1088",length=124,task_id="second-pass",
            dynamic_workflow={"stage":"UPSCALE","revision":describe(model,BASE)["revision"],"source_url":"http://minio/source.mp4",
                "source_sha256":"a"*64,"scale":2,"component":{},"save_latent":True,"source_size":"960x544",
                "source_latent":{"url":"http://minio/base","content_sha256":"b"*64},
                "latent_context":{"url":"http://minio/previous-upscale","content_sha256":"c"*64,"frames":22}})
        client=Mock(http_base="http://compute")
        client.get_http_client=AsyncMock(side_effect=AssertionError("Inventory must not be read"))
        client.submit_prompt=AsyncMock(side_effect=ValueError("RENDER_REACHED"))
        pool=Mock();pool.get_client_for_node=AsyncMock(return_value=client)
        http=Request({"type":"http","method":"POST","path":"/v1/images/generations","headers":[]})
        with patch.object(main,"config",cfg),patch.object(main,"get_client_pool",return_value=pool), \
             patch.object(main,"get_workflow_engine",return_value=WorkflowEngine(cfg)), \
             patch.object(main,"upload_reference_video",new=AsyncMock(return_value={"filename":"source.mp4"})), \
             patch("continuation_artifacts.upload_latent",new=AsyncMock(side_effect=["base.safetensors","previous.safetensors"])) as upload:
            with self.assertRaisesRegex(ValueError,"RENDER_REACHED"):await main._generate_images(request,http)
        self.assertEqual(2,upload.await_count)
        graph=client.submit_prompt.call_args.args[0]
        self.assertEqual("base.safetensors",graph["dynamic_encode"]["inputs"]["filename"])
        self.assertEqual("previous.safetensors",graph["dynamic_continue"]["inputs"]["filename"])
        self.assertEqual("SECOND_PASS",graph["dynamic_save_latent"]["inputs"]["stage"])
        client.get_http_client.assert_not_awaited()

    async def test_upscale_submits_default_model_without_node_or_weight_inventory_probe(self):
        import main
        from unittest.mock import Mock
        model, cfg = setup_model()
        request = ImageGenerationRequest(model=model.model_name, prompt="test", size="1920x1088", length=124,
            dynamic_workflow={"stage":"UPSCALE", "revision":describe(model, BASE)["revision"],
                "source_url":"http://minio/source.mp4", "source_sha256":"a"*64, "scale":2, "component":{}})
        client = Mock(http_base="http://compute")
        client.get_http_client = AsyncMock(side_effect=AssertionError("Inventory must not be read"))
        client.submit_prompt = AsyncMock(side_effect=ValueError("RENDER_REACHED"))
        pool = Mock(); pool.get_client_for_node = AsyncMock(return_value=client)
        http = Request({"type":"http", "method":"POST", "path":"/v1/images/generations", "headers":[]})
        with patch.object(main, "config", cfg), patch.object(main, "get_client_pool", return_value=pool), \
             patch.object(main, "get_workflow_engine", return_value=WorkflowEngine(cfg)), \
             patch.object(main, "upload_reference_video", new=AsyncMock(return_value={"filename":"source.mp4"})):
            with self.assertRaisesRegex(ValueError, "RENDER_REACHED"):
                await main._generate_images(request, http)
        client.get_http_client.assert_not_awaited()
        client.submit_prompt.assert_awaited_once()
        graph = client.submit_prompt.call_args.args[0]
        self.assertEqual(model.default_upscale_model, graph["dynamic_upscale_model"]["inputs"]["model_name"])

    async def test_preparation_failure_is_confirmed_before_submission_but_not_after(self):
        from main import generate_images
        request = ImageGenerationRequest(prompt="test", dynamic_workflow={"revision":"x"})
        http = Request({"type":"http","method":"POST","path":"/v1/images/generations","headers":[]})
        with patch("main._generate_images",new=AsyncMock(side_effect=RuntimeError("offline"))):
            with self.assertRaisesRegex(ValueError,"DYNAMIC_PREPARATION_FAILED"):
                await generate_images(request,http)
        async def uncertain(*args):
            args[1].state.submission_started=True
            raise RuntimeError("response lost")
        with patch("main._generate_images",new=uncertain):
            with self.assertRaisesRegex(RuntimeError,"response lost"):
                await generate_images(request,http)


if __name__ == "__main__": unittest.main()
