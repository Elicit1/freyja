import copy
import unittest
from unittest.mock import AsyncMock, Mock, patch
from starlette.requests import Request
from pydantic import ValidationError
from h3_extensions import H3Options, apply_extensions, revision, require_options
from schemas import ImageGenerationRequest
from workflow_engine import WorkflowEngine
from test_video_reference import setup_model, BASE


class H3ExtensionsTest(unittest.TestCase):
    def test_union2_controls_are_routed_without_destroying_original_branches(self):
        expected={"CANNY":"Canny","POSE":"DWPreprocessor","DEPTH":"DepthAnythingV2Preprocessor",
                  "HED":"HEDPreprocessor","MLSD":"M-LSDPreprocessor","SCRIBBLE":"FakeScribblePreprocessor","GRAY":None,
                  "LAYOUT":None,"PREPARED":None}
        for kind,preprocessor in expected.items():
            for window in (False,):
                with self.subTest(kind=kind,window=window):
                    model,request,graph=self.setup_graph();original=copy.deepcopy(graph)
                    request.h3_options.control_net.enabled=True;request.h3_options.control_net.reference_id="v"
                    request.h3_options.control_net.control_type=kind;request.h3_options.context_windows.enabled=window
                    apply_extensions(graph,request,model,[{"reference_id":"v","filename":"color.mp4","control_filename":"gray.mp4","frames":362}])
                    self.assertEqual(graph["h3_patch"]["inputs"]["name"],"minimax_h3_fun_controlnet_union_2.0_pruned_int8_convrot.safetensors")
                    self.assertEqual(graph["h3_control"]["class_type"],"MiniMaxH3FunControlNetApply")
                    self.assertEqual(graph["h3_control"]["inputs"]["model_patch"],["h3_patch",0])
                    self.assertFalse(any(node["class_type"].startswith("Freyja") for node in graph.values()))
                    self.assertEqual(graph["h3_control_video"]["inputs"]["video"],"gray.mp4" if kind=="GRAY" else "color.mp4")
                    if preprocessor:
                        self.assertEqual(graph["h3_control_preprocess"]["class_type"],preprocessor)
                        self.assertEqual(graph["h3_control_preprocess"]["inputs"]["image"],["h3_control_video",0])
                    else:
                        self.assertNotIn("h3_control_preprocess",graph)
                        self.assertEqual(graph["h3_control"]["inputs"]["control_video"],["h3_control_video",0])
                    self.assertEqual(graph["145"],original["145"])  # Existing LoRA chain.

    def test_independent_pose_and_depth_inputs_keep_sources_weights_and_all_consumers(self):
        from h3_extensions import ControlOptions
        model, request, graph = self.setup_graph()
        original = copy.deepcopy(graph)
        request.h3_options.control_net = ControlOptions(enabled=True, controls=[
            {"reference_id":"pose", "control_type":"POSE", "strength":0.8},
            {"reference_id":"depth", "control_type":"DEPTH", "input_mode":"PREPROCESSED", "strength":0.4}])
        # A prepared Depth video needs no Depth extractor installed/configured.
        del model.h3_extensions["preprocessors"]["DEPTH"]
        apply_extensions(graph, request, model, [
            {"reference_id":"pose", "filename":"person.mp4", "frames":362},
            {"reference_id":"depth", "filename":"depth.mp4", "frames":362}])
        self.assertEqual("person.mp4", graph["h3_control_video"]["inputs"]["video"])
        self.assertEqual("depth.mp4", graph["h3_control_video_1"]["inputs"]["video"])
        self.assertEqual("DWPreprocessor", graph["h3_control_preprocess"]["class_type"])
        self.assertNotIn("h3_control_preprocess_1", graph)
        self.assertEqual(["h3_control_preprocess",0], graph["h3_control"]["inputs"]["control_video"])
        self.assertEqual(["h3_control_video_1",0], graph["h3_control_1"]["inputs"]["control_video"])
        self.assertEqual(["h3_control",0], graph["h3_control_1"]["inputs"]["model"])
        self.assertEqual(0.8, graph["h3_control"]["inputs"]["strength"])
        self.assertEqual(0.4, graph["h3_control_1"]["inputs"]["strength"])
        for consumer in model.h3_extensions["model_consumers"]:
            self.assertEqual(["h3_control_1",0], graph[consumer["id"]]["inputs"][consumer.get("input","model")])
        self.assertEqual(original["145"], graph["145"])
        self.assertEqual(1, sum(node["class_type"]=="ModelPatchLoader" for node in graph.values()))

    def test_prepared_inputs_and_disabled_rows_do_not_run_extractors_or_gray_conversion(self):
        from h3_extensions import ControlOptions
        model, request, graph = self.setup_graph()
        request.h3_options.control_net = ControlOptions(enabled=True, controls=[
            {"reference_id":"pose", "control_type":"POSE", "input_mode":"PREPROCESSED"},
            {"reference_id":"gray", "control_type":"GRAY", "input_mode":"PREPROCESSED"},
            {"enabled":False, "reference_id":"not-attached", "control_type":"DEPTH"}])
        apply_extensions(graph, request, model, [
            {"reference_id":"pose", "filename":"skeleton.mp4", "frames":362},
            {"reference_id":"gray", "filename":"already-gray.mp4", "frames":362}])
        self.assertFalse(any(key.startswith("h3_control_preprocess") for key in graph))
        self.assertEqual("already-gray.mp4", graph["h3_control_video_1"]["inputs"]["video"])
        self.assertNotIn("h3_control_2", graph)
        for rows in ([], [{"enabled":False}]):
            model, request, graph = self.setup_graph()
            request.h3_options.control_net = ControlOptions(enabled=True, controls=rows, reference_id="legacy")
            apply_extensions(graph, request, model, [])
            self.assertNotIn("h3_control", graph)
            self.assertNotIn("h3_patch", graph)

    def test_every_enabled_control_must_exist_and_cover_the_segment(self):
        from h3_extensions import ControlOptions
        model, request, graph = self.setup_graph()
        request.h3_options.control_net = ControlOptions(enabled=True, controls=[
            {"reference_id":"a", "control_type":"POSE"}, {"reference_id":"b", "control_type":"DEPTH"}])
        with self.assertRaisesRegex(ValueError,"CONTROL_SOURCE_MISSING"):
            require_options(request, model, BASE)
        videos=[{"reference_id":"a", "filename":"a.mp4", "frames":362}]
        with self.assertRaisesRegex(ValueError,"CONTROL_SOURCE_MISSING"):
            apply_extensions(graph, request, model, videos)
        videos.append({"reference_id":"b", "filename":"b.mp4", "frames":100})
        with self.assertRaisesRegex(ValueError,"CONTROL_RANGE_INVALID"):
            apply_extensions(graph, request, model, videos)
        for rows in ([{}]*4, [{"input_mode":"guess"}], [None], [{"strength":float("nan")} ]):
            with self.subTest(rows=rows), self.assertRaises(ValidationError):
                ControlOptions(enabled=True, controls=rows)

    def test_removed_context_lock_cannot_be_reenabled_by_an_old_snapshot(self):
        with self.assertRaises(ValidationError):H3Options.model_validate({"capability_revision":"test","length":124,"lock_context":True})

    def test_control_range_is_checked_and_gray_cannot_use_color_source_silently(self):
        model,request,graph=self.setup_graph();request.h3_options.control_net.enabled=True;request.h3_options.control_net.reference_id="v"
        with self.assertRaisesRegex(ValueError,"CONTROL_RANGE"):apply_extensions(graph,request,model,[{"reference_id":"v","filename":"short.mp4","frames":120}])
        request.h3_options.control_required_frames=360
        apply_extensions(graph,request,model,[{"reference_id":"v","filename":"valid.mp4","frames":360}])
        self.assertEqual(graph["h3_control_video"]["inputs"]["frame_load_cap"],362)
        request.h3_options.control_net.control_type="GRAY"
        with self.assertRaisesRegex(ValueError,"GRAY_SOURCE_MISSING"):apply_extensions(graph,request,model,[{"reference_id":"v","filename":"color.mp4","frames":360}])

    def setup_graph(self):
        model,config=setup_model();model.h3_extensions["status"]="VERIFIED"
        request=ImageGenerationRequest(model=model.model_name,prompt="本段人物继续行走",size="960x544",h3_options={"capability_revision":revision(model,BASE),"continuation_enabled":True,"length":362})
        return model,request,WorkflowEngine(config).get_template(model)

    def test_no_control_branch_without_opt_in(self):
        model,request,graph=self.setup_graph();apply_extensions(graph,request,model,[])
        self.assertNotIn("h3_control",graph);self.assertNotIn("h3_patch",graph)
        self.assertEqual(graph["136"]["inputs"]["length"],362)

    def test_context_reaches_scheduler_and_guider(self):
        model,request,graph=self.setup_graph();request.h3_options.context_windows.enabled=True
        apply_extensions(graph,request,model,[])
        self.assertEqual(graph["126"]["inputs"]["model"],["h3_windows",0]);self.assertEqual(graph["124"]["inputs"]["model"],["h3_windows",0])

    def test_control_source_is_explicit_and_uses_native_apply(self):
        model,request,graph=self.setup_graph();request.h3_options.control_net.enabled=True;request.h3_options.control_net.reference_id="video1"
        apply_extensions(graph,request,model,[{"reference_id":"video1","filename":"unique.mp4","frames":362}])
        self.assertEqual(graph["h3_control"]["class_type"],"MiniMaxH3FunControlNetApply")
        self.assertEqual(graph["h3_control_video"]["inputs"]["video"],"unique.mp4")
        self.assertEqual(graph["126"]["inputs"]["model"],["h3_control",0])

    def test_unaccepted_native_window_control_combination_cannot_use_legacy_adapter_flag(self):
        model,request,graph=self.setup_graph();request.h3_options.control_net.enabled=True;request.h3_options.context_windows.enabled=True
        model.h3_extensions["window_control_adapter"]=True
        with self.assertRaisesRegex(ValueError,"WINDOW_CONTROL_UNVERIFIED"):apply_extensions(graph,request,model,[])
        self.assertNotIn("h3_patch",graph)

    def test_reference_video_does_not_implicitly_enable_control(self):
        model,request,graph=self.setup_graph();apply_extensions(graph,request,model,[{"reference_id":"v","filename":"reference.mp4"}]);self.assertNotIn("h3_control",graph)

    def test_context_is_a_guide_not_a_numbered_reference(self):
        model,request,graph=self.setup_graph();request.h3_options.continuation={}
        from h3_extensions import ContinuationInput
        request.h3_options.continuation=ContinuationInput(video_url="https://archive.test/context.mp4",content_sha256="a"*64,frames=39)
        apply_extensions(graph,request,model,[],"context.mp4")
        self.assertEqual(graph["126"]["inputs"]["conditioning"],["h3_guide",0]);self.assertNotIn("ref_videos.ref_video_0",graph["136"]["inputs"])

    def test_native_guide_keeps_the_original_sampling_latent(self):
        from h3_extensions import ContinuationInput
        model,request,graph=self.setup_graph();original=copy.deepcopy(graph["125"]["inputs"]["latent_image"])
        request.h3_options.continuation=ContinuationInput(video_url="https://archive.test/context.mp4",content_sha256="a"*64,frames=39)
        apply_extensions(graph,request,model,[],"context.mp4")
        self.assertEqual(graph["h3_guide"]["class_type"],"MiniMaxH3AddGuide")
        self.assertEqual(graph["125"]["inputs"]["latent_image"],original)
        self.assertEqual(graph["h3_guide"]["inputs"]["audio"],["h3_context_video",2])

    def test_manual_status_does_not_block_but_stale_revision_still_fails(self):
        model,request,_=self.setup_graph();require_options(request,model,BASE)
        request.h3_options.capability_revision="stale"
        with self.assertRaisesRegex(ValueError,"REVISION"):require_options(request,model,BASE)
        model.h3_extensions["status"]="UNVERIFIED"
        request.h3_options.capability_revision=revision(model,BASE)
        require_options(request,model,BASE)

    def test_invalid_frame_grid_and_invented_options_rejected(self):
        for change in ({"length":360},{"surprise_override":True},{"context_windows":{"context_length":124,"context_overlap":124}}):
            with self.assertRaises(ValidationError):H3Options.model_validate(dict({"capability_revision":"test","length":362},**change))

    def test_template_mismatch_does_not_modify_another_model_branch(self):
        model,request,graph=self.setup_graph();graph["145"]["class_type"]="OtherLoader"
        with self.assertRaisesRegex(ValueError,"GRAPH_MISMATCH"):apply_extensions(graph,request,model,[])

    def test_length_and_duration_cannot_describe_different_segments(self):
        model,request,_=self.setup_graph();request.length=124
        with self.assertRaisesRegex(ValueError,"LENGTH_CONFLICT"):require_options(request,model,BASE)
        request.length=362;request.duration=60
        with self.assertRaisesRegex(ValueError,"DURATION_CONFLICT"):require_options(request,model,BASE)
        request.duration=15;require_options(request,model,BASE)

class H3AsyncSubmissionTests(unittest.IsolatedAsyncioTestCase):
    async def test_native_union2_does_not_require_private_nodes_and_only_offers_installed_preprocessors(self):
        from h3_extensions import inspect_backend
        model,_=setup_model()
        nodes={name:{"input":{}} for name in (model.h3_extensions["conditioning"]["class_type"],"MiniMaxH3AddGuide","MiniMaxH3FunControlNetApply","HEDPreprocessor")}
        nodes["ModelPatchLoader"]={"input":{"required":{"name":[[model.h3_extensions["control_patch"]]]}}}
        async def probe():
            response=Mock();response.json.return_value=nodes
            stats=Mock();stats.json.return_value={"system":{}}
            http=AsyncMock();http.get.side_effect=[response,stats]
            client=Mock();client.http_base="http://gpu";client.get_http_client=AsyncMock(return_value=http)
            return await inspect_backend(model,client)
        available=await probe()
        self.assertEqual(available["controlVersion"],"2.0");self.assertTrue(available["controlNet"])
        self.assertEqual(set(available["controlTypes"]),{"HED","LAYOUT","PREPARED","GRAY"})
        self.assertFalse(any(name.startswith("Freyja") for name in available["missingNodes"]))
        self.assertFalse(available["contextWindowsWithControlNet"])
        nodes.pop("MiniMaxH3FunControlNetApply")
        missing=await probe();self.assertFalse(missing["controlNet"])
        self.assertNotEqual(available["backendSignature"],missing["backendSignature"])

    async def test_missing_optional_control_nodes_do_not_block_accepted_guide_continuation(self):
        from h3_extensions import inspect_backend
        model,_=setup_model();model.h3_extensions["status"]="VERIFIED"
        nodes={model.h3_extensions["conditioning"]["class_type"]:{"input":{}},"MiniMaxH3AddGuide":{"input":{}}}
        response=Mock();response.json.return_value=nodes
        stats=Mock();stats.json.return_value={"system":{"comfyui_version":"test"}}
        http=AsyncMock();http.get.side_effect=[response,stats,response,stats]
        client=Mock();client.http_base="http://gpu";client.get_http_client=AsyncMock(return_value=http)
        probe=await inspect_backend(model,client);model.h3_extensions["verified_backend_signature"]=probe["backendSignature"]
        accepted=await inspect_backend(model,client)
        self.assertNotIn("status",accepted);self.assertTrue(accepted["continuation"]);self.assertFalse(accepted["controlNet"]);self.assertFalse(accepted["contextWindows"])
        self.assertIn("MiniMaxH3FunControlNetApply",accepted["missingNodes"])

    async def test_submission_returns_persistable_identity_and_duplicate_does_not_generate_again(self):
        import main
        model,config=setup_model();model.h3_extensions["status"]="VERIFIED"
        upstream=AsyncMock();upstream.http_base="http://compute.invalid";upstream.submit_prompt.return_value="prompt-original"
        pool=Mock();pool.get_client_for_node=AsyncMock(return_value=upstream)
        engine=Mock();engine.assemble_workflow.return_value={}
        redis=AsyncMock();redis.set.side_effect=[True,False]
        def request():return ImageGenerationRequest(model=model.model_name,size="960x544",prompt="one continuous shot",task_id="segment-original",h3_options={"capability_revision":revision(model,BASE),"continuation_enabled":True,"length":124})
        http=Request({"type":"http","headers":[(b"x-freyja-async-submit",b"true")]})
        with patch.object(main,"config",config),patch.object(main,"get_workflow_engine",return_value=engine),patch.object(main,"get_client_pool",return_value=pool),patch.object(main,"inspect_backend",AsyncMock(return_value={"status":"UNVERIFIED","continuation":True,"controlTypes":["CANNY"]})),patch.object(main.task_tracker,"_redis_client",redis),patch.object(main.task_tracker,"register",AsyncMock()) as register,patch.object(main.task_tracker,"find",AsyncMock(return_value=("prompt-original",model.node_id))):
            result=await main.generate_images(request(),http)
            self.assertEqual(result.execution["promptId"],"prompt-original");self.assertEqual(result.execution["nodeId"],model.node_id)
            redis.get.return_value=redis.set.await_args.args[1]
            duplicate=await main.generate_images(request(),http)
            self.assertEqual(duplicate.execution["promptId"],"prompt-original")
            upstream.submit_prompt.assert_awaited_once();upstream.wait_for_completion.assert_not_awaited()
            self.assertTrue(register.await_args.kwargs["strict"])


class H3DebugWorkflowTests(unittest.TestCase):
    def test_ui_graph_connections_are_bidirectional_and_optional_modules_start_bypassed(self):
        import json
        graph=json.loads((BASE.parent/"comfyUI_workflow/minimax_h3_continuation_control_debug.json").read_text(encoding="utf-8"))
        nodes={node["id"]:node for node in graph["nodes"]}
        for link_id,source,slot,target,index,_ in graph["links"]:
            self.assertEqual(nodes[target]["inputs"][index]["link"],link_id)
            self.assertIn(link_id,nodes[source]["outputs"][slot]["links"])
        for node_id in (200,201,205):self.assertEqual(nodes[node_id]["mode"],4)
        self.assertEqual(nodes[206]["mode"],2)
        self.assertEqual(nodes[201]["type"],"MiniMaxH3FunControlNetApply")
        self.assertEqual(nodes[205]["type"],"MiniMaxH3AddGuide")
        self.assertEqual(len(nodes[205]["outputs"]),1)
        self.assertFalse(any(node["type"].startswith("Freyja") for node in nodes.values()))


class H3MultiControlSubmissionTest(unittest.IsolatedAsyncioTestCase):
    async def test_upload_prepares_gray_per_source_and_prepared_pose_needs_no_pose_extractor(self):
        import main
        from test_render_input_validation import bind_contract
        from video_reference import workflow_revision
        model, config = setup_model()
        model.reference_video.max_videos = 3
        model.reference_video.max_clip_seconds = 6
        model.reference_video.max_total_clip_seconds = 15
        model.reference_video.max_decoded_frames = 144
        request = bind_contract(ImageGenerationRequest(model=model.model_name, prompt="<Video 1> <Video 2>",
            prompt_format="MINIMAX_H3_REF2VA_VIDEO_V1", size="960x544", length=124,
            reference_video_revision=workflow_revision(model,BASE), references=[
                {"referenceId":key,"referenceType":"REFERENCE_VIDEO","referenceVideo":"http://archive.test/"+key+".mp4","contentSha256":"a"*64}
                for key in ("pose","gray")],
            h3_options={"capability_revision":revision(model,BASE),"length":124,"control_net":{"enabled":True,"controls":[
                {"reference_id":"pose","control_type":"POSE","input_mode":"PREPROCESSED","strength":.8},
                {"reference_id":"gray","control_type":"GRAY","input_mode":"RAW","strength":.4}]}}))
        client=Mock(http_base="http://compute");client.check_health=AsyncMock(return_value=True)
        client.submit_prompt=AsyncMock(side_effect=ValueError("SUBMITTED"))
        pool=Mock();pool.get_client_for_node=AsyncMock(return_value=client)
        async def upload(client, binding, capability, hosts, **kwargs):
            return {"reference_id":binding.reference_id,"filename":binding.reference_id+".mp4","frames":124,"duration":5,"include_audio":False,
                    **({"control_filename":"gray-copy.mp4"} if kwargs.get("grayscale") else {})}
        upload_mock=AsyncMock(side_effect=upload)
        http=Request({"type":"http","method":"POST","path":"/v1/videos/generations","headers":[]})
        with patch.object(main,"config",config),patch.object(main,"get_client_pool",return_value=pool), \
             patch.object(main,"get_workflow_engine",return_value=WorkflowEngine(config)), \
             patch.object(main,"inspect_backend",new=AsyncMock(return_value={"controlNet":True,"controlTypes":["PREPARED","GRAY"]})), \
             patch.object(main,"upload_reference_video",new=upload_mock):
            with self.assertRaisesRegex(ValueError,"SUBMITTED"):
                await main._generate_images(request,http)
        self.assertEqual([False,True],[call.kwargs["grayscale"] for call in upload_mock.call_args_list])
        graph=client.submit_prompt.call_args.args[0]
        self.assertNotIn("h3_control_preprocess",graph)
        self.assertEqual("pose.mp4",graph["h3_control_video"]["inputs"]["video"])
        self.assertEqual("gray-copy.mp4",graph["h3_control_video_1"]["inputs"]["video"])
        self.assertEqual(["h3_control_1",0],graph["126"]["inputs"]["model"])


if __name__=="__main__":unittest.main()
