import copy
import unittest
from unittest.mock import AsyncMock, Mock

from h3_extensions import apply_extensions, inspect_backend, require_options, revision
from test_video_reference import BASE, REF_MODELS, request, setup_model
from workflow_engine import WorkflowEngine


class H3ControlCompatibilityTests(unittest.TestCase):
    def test_hifi_rejects_every_control_type_and_input_mode_before_assembly(self):
        model, config = setup_model("minimax-h3-ref2va")
        original = WorkflowEngine(config).get_template(model)
        for kind in ("POSE", "DEPTH", "CANNY", "HED", "MLSD", "SCRIBBLE", "LAYOUT", "GRAY", "PREPARED"):
            for mode in ("RAW", "PREPROCESSED"):
                for continuation in (False, True):
                    with self.subTest(kind=kind, mode=mode, continuation=continuation):
                        req = request(model, h3_options={
                            "capability_revision": revision(model, BASE), "length": 124,
                            "continuation_enabled": continuation,
                            "control_net": {"enabled": True, "controls": [{
                                "reference_id": "video1", "control_type": kind, "input_mode": mode}]}})
                        with self.assertRaisesRegex(ValueError, "H3_CONTROL_UNSUPPORTED: .*AdaLN"):
                            require_options(req, model, BASE)
                        with self.assertRaisesRegex(ValueError, "H3_CONTROL_UNSUPPORTED: .*AdaLN"):
                            WorkflowEngine(config).assemble_workflow(req)
                        graph = copy.deepcopy(original)
                        with self.assertRaisesRegex(ValueError, "H3_CONTROL_UNSUPPORTED: .*AdaLN"):
                            apply_extensions(graph, req, model, [])
                        self.assertEqual(original, graph)

    def test_hifi_preserves_reference_video_and_acceleration_without_control(self):
        model, config = setup_model("minimax-h3-ref2va")
        for continuation in (False, True):
            with self.subTest(continuation=continuation):
                req = request(model, h3_options={"capability_revision": revision(model, BASE),
                    "length": 124, "continuation_enabled": continuation})
                graph = WorkflowEngine(config).assemble_workflow(req, uploaded_ref_videos=[{
                    "reference_id": "video1", "filename": "reference.mp4", "include_audio": False}])
                self.assertEqual("minimax_h3_ref2va_dit_16g.safetensors", graph["127"]["inputs"]["unet_name"])
                self.assertEqual(["136_ref_vid_0", 0], graph["136"]["inputs"]["ref_videos.ref_video_0"])
                self.assertNotIn("h3_patch", graph)
                self.assertNotIn("h3_control", graph)
                for node_id in ("141", "142", "143"):
                    self.assertTrue(graph[node_id]["inputs"]["enabled"])
                self.assertEqual("EasyCache", graph["144"]["class_type"])
                self.assertEqual(["144", 0], graph["126"]["inputs"]["model"])

    def test_other_ref_models_keep_control_support(self):
        for name in REF_MODELS:
            if name == "minimax-h3-ref2va":
                continue
            with self.subTest(model=name):
                model, _ = setup_model(name)
                req = request(model, h3_options={"capability_revision": revision(model, BASE),
                    "length": 124, "control_net": {"enabled": True, "reference_id": "video1"}})
                require_options(req, model, BASE)


class H3ControlCapabilityTests(unittest.IsolatedAsyncioTestCase):
    async def test_installed_control_nodes_cannot_override_model_restriction(self):
        model, _ = setup_model("minimax-h3-ref2va")
        nodes = {name: {"input": {}} for name in (
            model.h3_extensions["conditioning"]["class_type"], "MiniMaxH3AddGuide",
            "MiniMaxH3ContextWindows", "MiniMaxH3FunControlNetApply",
            *(preset["class_type"] for preset in model.h3_extensions["preprocessors"].values()))}
        nodes["ModelPatchLoader"] = {"input": {"required": {
            "name": [[model.h3_extensions["control_patch"]]]}}}
        response = Mock(); response.json.return_value = nodes
        stats = Mock(); stats.json.return_value = {"system": {}}
        http = AsyncMock(); http.get.side_effect = [response, stats]
        client = Mock(http_base="http://compute")
        client.get_http_client = AsyncMock(return_value=http)
        caps = await inspect_backend(model, client)
        self.assertFalse(caps["controlNet"])
        self.assertEqual([], caps["controlTypes"])
        self.assertEqual(model.h3_extensions["control_net_unavailable_reason"], caps["controlNetUnavailableReason"])
        self.assertTrue(caps["continuation"])
        self.assertEqual([], caps["missingNodes"])

    async def test_offline_node_still_reports_model_restriction(self):
        model, _ = setup_model("minimax-h3-ref2va")
        client = Mock()
        client.get_http_client = AsyncMock(side_effect=RuntimeError("offline"))
        caps = await inspect_backend(model, client)
        self.assertFalse(caps["controlNet"])
        self.assertEqual([], caps["controlTypes"])
        self.assertIn("AdaLN", caps["controlNetUnavailableReason"])
        self.assertIn("backend-unreachable", caps["missingNodes"])


if __name__ == "__main__":
    unittest.main()
