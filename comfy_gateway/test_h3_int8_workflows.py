import io
import json
import os
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import yaml

from config import GatewayConfig, NodeConfig
from dynamic_workflow import describe
from schemas import ImageGenerationRequest
from test_continuation_modes import assert_acyclic
from workflow_engine import WorkflowEngine


BASE = Path(__file__).resolve().parent
MODELS = ("minimax-h3-fl2va-int8", "minimax-h3-ref2va-int8")
ACCELERATORS = {"LoraLoaderModelOnly", "LoraLoader", "EasyCache", "ManualSigmas",
                "MiniMaxH3SolAttention", "MiniMaxH3FusedModulation", "MiniMaxH3ChunkFeedForward"}


class H3Int8WorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.node = NodeConfig(**yaml.safe_load((BASE / "nodes/machine_default.yaml").read_text(encoding="utf-8")))
        cls.models = {model.model_name: model for model in cls.node.models}
        cls.engine = WorkflowEngine(GatewayConfig(models=cls.node.models))

    def assert_base_sampler(self, graph, model, steps=20):
        self.assertFalse(ACCELERATORS & {node["class_type"] for node in graph.values()})
        assert_acyclic(graph)
        for node in graph.values():
            for value in node.get("inputs", {}).values():
                if isinstance(value, list):
                    self.assertIn(value[0], graph, "No dangling graph inputs")
        samplers = [node for node in graph.values() if node["class_type"] == "SamplerCustomAdvanced"]
        self.assertEqual(1, len(samplers))
        sampler = samplers[0]
        self.assertEqual("res_multistep", graph[sampler["inputs"]["sampler"][0]]["inputs"]["sampler_name"])
        schedule = graph[sampler["inputs"]["sigmas"][0]]
        self.assertEqual("BasicScheduler", schedule["class_type"])
        self.assertEqual(steps, schedule["inputs"]["steps"])
        self.assertEqual("simple", schedule["inputs"]["scheduler"])
        return sampler, schedule

    def test_base_sampling_and_reference_bindings(self):
        for name in MODELS:
            model = self.models[name]
            fl = model.handler == "minimax_h3_fl2va"
            for images in (([], ["first.png"], ["first.png", "last.png"]) if fl else ([f"ref{i}.png" for i in range(9)],)):
                with self.subTest(model=name, images=len(images)):
                    graph = self.engine.assemble_workflow(ImageGenerationRequest(model=name, prompt="motion", size="832x480", seed=42),
                        uploaded_ref_images=images, uploaded_ref_audios=[] if fl else [f"voice{i}.wav" for i in range(3)])
                    _, schedule = self.assert_base_sampler(graph, model)
                    loader = model.h3_extensions["model_output"]["id"]
                    self.assertEqual("UNETLoader", graph[loader]["class_type"])
                    self.assertEqual(f"minimax_h3_{'fl2va' if fl else 'ref2va'}_pruned_int8_convrot.safetensors", graph[loader]["inputs"]["unet_name"])
                    self.assertEqual([loader, 0], schedule["inputs"]["model"])
                    guider = next(node for node in graph.values() if node["class_type"] == "BasicGuider")
                    self.assertEqual([loader, 0], guider["inputs"]["model"])
                    cond = graph[model.h3_extensions["conditioning"]["id"]]["inputs"]
                    keys = [key for key in ("first_frame", "last_frame") if key in cond] if fl else [key for key in cond if key.startswith("ref_images.")]
                    self.assertEqual(len(images), len(keys))
                    for key, image in zip(keys, images):
                        self.assertEqual(image, graph[cond[key][0]]["inputs"]["image"])
                    if not fl:
                        for i in range(3):
                            self.assertEqual(f"voice{i}.wav", graph[cond[f"ref_audios.ref_audio_{i}"][0]]["inputs"]["audio"])
                    self.assertEqual(1, schedule["inputs"]["denoise"])

    def test_steps_remain_configurable_without_changing_the_base_template(self):
        for name in MODELS:
            model = self.models[name]
            graph = self.engine.assemble_workflow(ImageGenerationRequest(model=name, prompt="motion", steps=24))
            self.assert_base_sampler(graph, model, 24)
            self.assert_base_sampler(self.engine.get_template(model), model)

    def test_both_enhancements_reuse_int8_without_acceleration(self):
        for name in MODELS:
            model = self.models[name]
            for stage in ("UPSCALE", "FACE"):
                for memory in (("FULL", "TEMPORAL", "SPATIAL") if stage == "UPSCALE" else ("FULL",)):
                    with self.subTest(model=name, stage=stage, memory=memory):
                        options = dict(stage=stage, revision=describe(model, BASE)["revision"], denoise=.35,
                                       source_url="https://archive/source.mp4", source_sha256="a" * 64)
                        if stage == "UPSCALE":
                            options.update(scale=2, component=dict(steps=20, memory=memory))
                        request = ImageGenerationRequest(model=name, prompt="portrait", length=124,
                            size="1664x960" if stage == "UPSCALE" else "832x480", dynamic_workflow=options)
                        before = self.engine.get_template(model)
                        graph = self.engine.assemble_workflow(request, uploaded_ref_images=["first.png", "last.png"],
                            dynamic_source_filename="source.mp4")
                        _, schedule = self.assert_base_sampler(graph, model)
                        self.assertEqual(.35, schedule["inputs"]["denoise"])
                        loader = model.h3_extensions["model_output"]["id"]
                        if stage == "FACE":
                            self.assertEqual([loader, 0], graph["dynamic_face_160"]["inputs"]["model"])
                            self.assertIn("ref_images.ref_image_1", graph["dynamic_face_158"]["inputs"])
                        video = next(node for node in graph.values() if node["class_type"] == "CreateVideo")
                        self.assertEqual(["dynamic_source", 2], video["inputs"]["audio"])
                        self.assertEqual(before, self.engine.get_template(model))

    def test_ui_graphs_have_consistent_links_and_direct_model_sampling(self):
        for kind in ("fl2va", "ref2va"):
            ui = json.loads((BASE.parent / "comfyUI_workflow" / f"video_minimax_h3_{kind}_int8.json").read_text(encoding="utf-8"))
            nodes = {node["id"]: node for node in ui["nodes"]}
            self.assertFalse(ACCELERATORS & {node["type"] for node in nodes.values()})
            for lid, origin, slot, target, target_slot, _ in ui["links"]:
                self.assertIn(lid, nodes[origin]["outputs"][slot]["links"])
                self.assertEqual(lid, nodes[target]["inputs"][target_slot]["link"])
            schedule = next(node for node in nodes.values() if node["type"] == "BasicScheduler")
            self.assertEqual(["simple", 20, 1.0], schedule["widgets_values"])
            model_link = next(link for link in ui["links"] if link[0] == schedule["inputs"][0]["link"])
            self.assertEqual("UNETLoader", nodes[model_link[1]]["type"])

    def test_local_override_and_studio_declarations_keep_both_models(self):
        local = BASE / "nodes/machine_local.yaml"
        if local.exists():
            node = NodeConfig(**yaml.safe_load(local.read_text(encoding="utf-8")))
            models = {model.model_name: model for model in node.models}
            for name in MODELS:
                self.assertEqual(self.models[name].model_dump(), models[name].model_dump())
        sys.path.insert(0, str(BASE.parent / "scripts"))
        from register_h3_int8_models import model_definitions
        definitions = model_definitions("2032095628944588801")
        self.assertEqual(set(MODELS), {row["modelCode"] for row in definitions})
        self.assertEqual(["TXT2VIDEO_FIRST_LAST", "TXT2VIDEO_REF"], [row["modelType"] for row in definitions])
        self.assertTrue(all(row["providerId"] == "2032095628944588801" for row in definitions))
        self.assertEqual([(2, 0, 0), (9, 3, 3)], [(row["maxImages"], row["maxAudios"], row["maxVideos"]) for row in definitions])

    def test_studio_registration_preserves_existing_model_configuration(self):
        sys.path.insert(0, str(BASE.parent / "scripts"))
        from register_h3_int8_models import register, model_definitions
        provider = "2032095628944588801"
        with patch("register_h3_int8_models.api", side_effect=[
            {"records": [{"modelCode": MODELS[0], "id": "2032095628944588802"}], "total": 1}, None]) as api, redirect_stdout(io.StringIO()):
            register("http://studio", provider)
        self.assertEqual(2, api.call_count)
        self.assertEqual(("http://studio", "POST", "/ai/model", model_definitions(provider)[1]), api.call_args.args)

    def test_gateway_publishes_both_models_and_enhancement_stages(self):
        import main
        from fastapi.testclient import TestClient
        token = "freyja-int8-workflow-test-token"
        with patch.object(main, "config", GatewayConfig(models=self.node.models)), patch.dict(os.environ, {"GATEWAY_API_KEY": token}):
            # No lifespan context: discovery must not require a live GPU backend.
            client = TestClient(main.app, headers={"Authorization": "Bearer " + token})
            response = client.get("/v1/models")
            self.assertEqual(200, response.status_code)
            self.assertTrue(set(MODELS).issubset({row["id"] for row in response.json()["data"]}))
            response = client.get("/v1/dynamic-workflows/capabilities")
            self.assertEqual(200, response.status_code)
            catalog = {row["modelCode"]: row for row in response.json()["models"]}
            for name in MODELS:
                self.assertTrue(catalog[name]["supported"])
                self.assertEqual(["BASE", "UPSCALE", "FACE"], catalog[name]["stages"])
                self.assertEqual(20, catalog[name]["freyjaUpscale"]["maxSteps"])

    def test_studio_api_registration_sends_login_authorization(self):
        sys.path.insert(0, str(BASE.parent / "scripts"))
        from register_h3_int8_models import api
        with patch.dict(os.environ, {"FREYJA_API_TOKEN": "studio-test-token"}), patch("register_h3_int8_models.urlopen") as open_url:
            open_url.return_value.__enter__.return_value = io.BytesIO(b'{"code":200,"data":null}')
            self.assertIsNone(api("http://studio", "POST", "/ai/model", {"modelCode": MODELS[0]}))
            request = open_url.call_args.args[0]
            self.assertEqual("Bearer studio-test-token", request.get_header("Authorization"))


if __name__ == "__main__":
    unittest.main()
