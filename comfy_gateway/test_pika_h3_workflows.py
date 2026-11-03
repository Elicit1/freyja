import json
import unittest
from pathlib import Path

import yaml

from config import GatewayConfig, NodeConfig
from schemas import ImageGenerationRequest
from workflow_engine import WorkflowEngine


class PikaH3WorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = Path(__file__).resolve().parent
        node = NodeConfig(**yaml.safe_load(
            (cls.root / "nodes/machine_default.yaml").read_text(encoding="utf-8")))
        cls.models = {m.model_name: m for m in node.models}
        cls.engine = WorkflowEngine(GatewayConfig(models=node.models))

    def test_fixed_official_schedule_survives_generic_overrides(self):
        for kind, sampler, sigmas, guider, lora in (
            ("fl2va", "17", "9", "16", "121"),
            ("ref2va", "123", "124", "126", "145"),
        ):
            with self.subTest(kind=kind):
                model = "pika-h3-flex-" + kind
                graph = self.engine.assemble_workflow(
                    ImageGenerationRequest(model=model, prompt="probe", size="512x288",
                                           duration=5, seed=42, steps=20, cfg=7,
                                           extra_body={"steps": 8, "cfg": 9,
                                                       "sampler": "euler", "sigmas": "1,0"}),
                    uploaded_ref_images=["first.png"],
                )
                self.assertEqual(graph[sampler]["inputs"]["sampler_name"], "lcm")
                self.assertEqual(graph[sigmas]["class_type"], "ManualSigmas")
                values = [float(s) for s in graph[sigmas]["inputs"]["sigmas"].split(",")]
                self.assertEqual(values, [1, .972973, .923077, .8, .571429, .333333, 0])
                self.assertEqual(graph[guider]["inputs"]["model"], [lora, 0])
                self.assertEqual(graph[lora]["inputs"]["lora_name"],
                                 f"pika_h3_flex_{kind}_v1_bf16.safetensors")
                self.assertNotIn("steps", self.models[model].mappings)

    def test_fl2va_image_modes_and_runtime_parameters(self):
        for images in ([], ["first.png"], ["first.png", "last.png"]):
            with self.subTest(images=images):
                graph = self.engine.assemble_workflow(
                    ImageGenerationRequest(model="pika-h3-flex-fl2va", prompt="move",
                                           size="960x544", duration=5, seed=42),
                    uploaded_ref_images=images)
                cond = graph["104"]["inputs"]
                self.assertEqual((cond["prompt"], cond["width"], cond["height"], cond["length"]),
                                 ("move", 960, 544, 124))
                self.assertEqual(graph["15"]["inputs"]["noise_seed"], 42)
                for index, key in enumerate(("first_frame", "last_frame")):
                    if index < len(images):
                        self.assertEqual(graph[cond[key][0]]["inputs"]["image"], images[index])
                    else:
                        self.assertNotIn(key, cond)
                self.assertEqual(graph["91"]["inputs"]["audio"], ["23", 0])

    def test_ref2va_preserves_nine_images_and_three_audios(self):
        graph = self.engine.assemble_workflow(
            ImageGenerationRequest(model="pika-h3-flex-ref2va", prompt="references", size="512x288"),
            uploaded_ref_images=[f"ref{i}.png" for i in range(9)],
            uploaded_ref_audios=[f"audio{i}.wav" for i in range(3)])
        cond = graph["136"]["inputs"]
        for i in range(9):
            self.assertEqual(graph[cond[f"ref_images.ref_image_{i}"][0]]["inputs"]["image"],
                             f"ref{i}.png")
        for i in range(3):
            self.assertEqual(graph[cond[f"ref_audios.ref_audio_{i}"][0]]["inputs"]["audio"],
                             f"audio{i}.wav")
        self.assertEqual(graph["130"]["inputs"]["audio"], ["121", 0])

    def test_ui_workflows_match_api_sampling_and_have_valid_links(self):
        for kind in ("fl2va", "ref2va"):
            ui = json.loads((self.root.parent / "comfyUI_workflow" /
                             f"video_pika_h3_flex_{kind}.json").read_text(encoding="utf-8"))
            nodes = {n["id"]: n for n in ui["nodes"]}
            for lid, origin, slot, target, target_slot, _ in ui["links"]:
                self.assertIn(lid, nodes[origin]["outputs"][slot]["links"])
                self.assertEqual(nodes[target]["inputs"][target_slot]["link"], lid)
            sigmas = next(n for n in ui["nodes"] if n["type"] == "ManualSigmas")
            self.assertEqual(len(sigmas["widgets_values"][0].split(",")), 7)
            self.assertEqual(next(n for n in ui["nodes"] if n["type"] == "KSamplerSelect")
                             ["widgets_values"], ["lcm"])


if __name__ == "__main__":
    unittest.main()
