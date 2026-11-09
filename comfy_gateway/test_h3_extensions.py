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


if __name__=="__main__":unittest.main()
