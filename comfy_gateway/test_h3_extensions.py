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

    def test_control_source_is_explicit_and_window_aware(self):
        model,request,graph=self.setup_graph();request.h3_options.control_net.enabled=True;request.h3_options.control_net.reference_id="video1";request.h3_options.context_windows.enabled=True
        apply_extensions(graph,request,model,[{"reference_id":"video1","filename":"unique.mp4"}])
        self.assertEqual(graph["h3_control"]["class_type"],"FreyjaMiniMaxH3FunControlNetApply")
        self.assertEqual(graph["h3_control_video"]["inputs"]["video"],"unique.mp4")
        self.assertEqual(graph["h3_windows"]["inputs"]["model"],["h3_control",0])

    def test_reference_video_does_not_implicitly_enable_control(self):
        model,request,graph=self.setup_graph();apply_extensions(graph,request,model,[{"reference_id":"v","filename":"reference.mp4"}]);self.assertNotIn("h3_control",graph)

    def test_context_is_a_guide_not_a_numbered_reference(self):
        model,request,graph=self.setup_graph();request.h3_options.continuation={}
        from h3_extensions import ContinuationInput
        request.h3_options.continuation=ContinuationInput(video_url="https://archive.test/context.mp4",content_sha256="a"*64,frames=39)
        apply_extensions(graph,request,model,[],"context.mp4")
        self.assertEqual(graph["126"]["inputs"]["conditioning"],["h3_guide",0]);self.assertNotIn("ref_videos.ref_video_0",graph["136"]["inputs"])

    def test_locked_context_reaches_the_sampling_latent(self):
        from h3_extensions import ContinuationInput
        model,request,graph=self.setup_graph();request.h3_options.lock_context=True
        request.h3_options.continuation=ContinuationInput(video_url="https://archive.test/context.mp4",content_sha256="a"*64,frames=39)
        apply_extensions(graph,request,model,[],"context.mp4")
        self.assertEqual(graph["h3_guide"]["class_type"],"FreyjaH3Continuation")
        self.assertEqual(graph["125"]["inputs"]["latent_image"],["h3_guide",1])

    def test_revision_and_status_cannot_be_bypassed(self):
        model,request,_=self.setup_graph();require_options(request,model,BASE)
        request.h3_options.capability_revision="stale"
        with self.assertRaisesRegex(ValueError,"REVISION"):require_options(request,model,BASE)
        model.h3_extensions["status"]="UNVERIFIED"
        with self.assertRaisesRegex(ValueError,"UNVERIFIED"):require_options(request,model,BASE)

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
    async def test_missing_optional_control_nodes_do_not_block_accepted_guide_continuation(self):
        from h3_extensions import backend_acceptance
        model,_=setup_model();model.h3_extensions["status"]="VERIFIED"
        nodes={model.h3_extensions["conditioning"]["class_type"]:{"input":{}},"MiniMaxH3AddGuide":{"input":{}}}
        response=Mock();response.json.return_value=nodes
        stats=Mock();stats.json.return_value={"system":{"comfyui_version":"test"}}
        http=AsyncMock();http.get.side_effect=[response,stats,response,stats]
        client=Mock();client.http_base="http://gpu";client.get_http_client=AsyncMock(return_value=http)
        probe=await backend_acceptance(model,client);model.h3_extensions["verified_backend_signature"]=probe["backendSignature"]
        accepted=await backend_acceptance(model,client)
        self.assertEqual(accepted["status"],"VERIFIED");self.assertFalse(accepted["controlNet"]);self.assertFalse(accepted["contextWindows"])
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
        with patch.object(main,"config",config),patch.object(main,"get_workflow_engine",return_value=engine),patch.object(main,"get_client_pool",return_value=pool),patch.object(main,"backend_acceptance",AsyncMock(return_value={"status":"VERIFIED","controlTypes":["CANNY"]})),patch.object(main.task_tracker,"_redis_client",redis),patch.object(main.task_tracker,"register",AsyncMock()) as register,patch.object(main.task_tracker,"find",AsyncMock(return_value=("prompt-original",model.node_id))):
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


if __name__=="__main__":unittest.main()
