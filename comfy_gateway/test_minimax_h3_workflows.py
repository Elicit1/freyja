import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from config import load_config
from schemas import ImageGenerationRequest
from workflow_engine import WorkflowEngine


class MiniMaxH3WorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = WorkflowEngine(load_config())

    def assemble(self, model):
        return self.engine.assemble_workflow(
            ImageGenerationRequest(model=model, prompt="face close-up", size="768x768"),
            uploaded_ref_images=[f"image{i}.png" for i in range(5)],
            uploaded_ref_audios=[f"audio{i}.wav" for i in range(3)]
        )

    def test_face_refine_conditions_both_passes_on_all_images(self):
        workflow = self.assemble("minimax-h3-face-refine")
        for index in range(5):
            key = f"ref_images.ref_image_{index}"
            for node_id in ("136", "158"):
                load_id = workflow[node_id]["inputs"][key][0]
                self.assertEqual(workflow[load_id]["inputs"]["image"], f"image{index}.png")
        for index in range(3):
            key = f"ref_audios.ref_audio_{index}"
            self.assertIn(key, workflow["136"]["inputs"])
        self.assertEqual(workflow["158"]["inputs"]["ref_audios.ref_audio_0"], ["121", 0])
        self.assertEqual(workflow["130"]["inputs"]["images"], ["165", 0])

    def test_official_turbo_uses_lora_and_four_steps(self):
        workflow = self.assemble("minimax-h3-ref")
        self.assertEqual(workflow["124"]["inputs"]["steps"], 4)
        self.assertEqual(workflow["124"]["inputs"]["model"], ["145", 0])
        self.assertEqual(workflow["126"]["inputs"]["model"], ["145", 0])
        self.assertEqual(workflow["145"]["inputs"]["model"], ["127", 0])
        self.assertIn("turbo_4step", workflow["145"]["inputs"]["lora_name"])
        self.assertEqual(workflow["127"]["inputs"]["unet_name"],
                         "minimax_h3_ref2va_pruned_int8_convrot.safetensors")

    def test_fl2va_turbo4_frame_modes_and_fixed_sampling(self):
        for images in ([], ["first.png"], ["first.png", "last.png"]):
            with self.subTest(images=images):
                workflow = self.engine.assemble_workflow(
                    ImageGenerationRequest(
                        model="minimax-h3-fl2va-turbo4", prompt="camera moves forward",
                        size="1344x768", duration=5, seed=123,
                        steps=20, extra_body={"steps": 8},
                    ),
                    uploaded_ref_images=images,
                )
                inputs = workflow["104"]["inputs"]
                self.assertEqual(inputs["prompt"], "camera moves forward")
                self.assertEqual((inputs["width"], inputs["height"], inputs["length"]),
                                 (1344, 768, 124))
                self.assertEqual(workflow["15"]["inputs"]["noise_seed"], 123)
                for index, key in enumerate(("first_frame", "last_frame")):
                    if index < len(images):
                        self.assertEqual(workflow[inputs[key][0]]["inputs"]["image"],
                                         images[index])
                    else:
                        self.assertNotIn(key, inputs)
                self.assertEqual(workflow["9"]["inputs"]["steps"], 4)
                self.assertEqual(workflow["9"]["inputs"]["model"], ["121", 0])
                self.assertEqual(workflow["16"]["inputs"]["model"], ["121", 0])
                self.assertEqual(workflow["121"]["inputs"]["model"], ["6", 0])
                self.assertEqual(workflow["121"]["inputs"]["lora_name"],
                                 "minimax_h3_fl2v_turbo_4step_v1.0_768p_comfyui_bf16.safetensors")
                self.assertEqual(workflow["6"]["inputs"]["unet_name"],
                                 "minimax_h3_fl2va_pruned_int8_convrot.safetensors")
                self.assertEqual(workflow["91"]["inputs"]["audio"], ["23", 0])
                self.assertEqual(workflow["92"]["inputs"]["format.codec"], "auto")
                # No demo LoadImage nodes may survive in text-only mode.
                loaders = [n for n in workflow.values() if n["class_type"] == "LoadImage"]
                self.assertEqual(len(loaders), len(images))

    def test_fl2va_turbo_available_in_public_config(self):
        import yaml
        from config import NodeConfig

        config_path = Path(__file__).resolve().parent / "nodes" / "machine_default.yaml"
        node = NodeConfig(**yaml.safe_load(config_path.read_text(encoding="utf-8")))
        for steps in (4, 8):
            with self.subTest(steps=steps):
                model = next(m for m in node.models if m.model_name == f"minimax-h3-fl2va-turbo{steps}")
                self.assertEqual(model.default_steps, steps)
                self.assertNotIn("steps", model.mappings)
                self.assertEqual(model.handler, "minimax_h3_fl2va")

    def test_fl2va_turbo8_frame_modes_and_fixed_sampling(self):
        for images in ([], ["first.png"], ["first.png", "last.png"]):
            with self.subTest(images=images):
                workflow = self.engine.assemble_workflow(
                    ImageGenerationRequest(model="minimax-h3-fl2va-turbo8", prompt="slow camera pan",
                                           size="960x544", duration=5, seed=789,
                                           steps=4, extra_body={"steps": 20}),
                    uploaded_ref_images=images,
                )
                self.assertEqual(workflow["9"]["inputs"]["steps"], 8)
                self.assertEqual(workflow["9"]["inputs"]["model"], ["121", 0])
                self.assertEqual(workflow["16"]["inputs"]["model"], ["121", 0])
                self.assertEqual(workflow["121"]["inputs"]["model"], ["6", 0])
                self.assertEqual(workflow["121"]["inputs"]["lora_name"],
                                 "minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors")
                self.assertEqual(workflow["15"]["inputs"]["noise_seed"], 789)
                inputs = workflow["104"]["inputs"]
                self.assertEqual((inputs["width"], inputs["height"], inputs["length"]), (960, 544, 124))
                self.assertEqual(inputs["prompt"], "slow camera pan")
                for index, key in enumerate(("first_frame", "last_frame")):
                    if index < len(images):
                        self.assertEqual(workflow[inputs[key][0]]["inputs"]["image"], images[index])
                    else:
                        self.assertNotIn(key, inputs)
                self.assertEqual(workflow["91"]["inputs"]["audio"], ["23", 0])
                self.assertEqual(sum(n["class_type"] == "LoadImage" for n in workflow.values()), len(images))

    def test_official_turbo_face_refine_uses_four_steps_in_both_passes(self):
        workflow = self.assemble("minimax-h3-ref-turbo4-face-refine")
        self.assertEqual(workflow["124"]["inputs"]["steps"], 4)
        self.assertEqual(workflow["162"]["inputs"]["steps"], 4)
        self.assertEqual(workflow["124"]["inputs"]["model"], ["145", 0])
        self.assertEqual(workflow["160"]["inputs"]["model"], ["145", 0])
        self.assertEqual(workflow["130"]["inputs"]["images"], ["165", 0])
        self.assertEqual(workflow["119"]["inputs"]["vae_name"],
                         "minimax_h3_video_vae_int8_convrot.safetensors")
        for index in range(5):
            key = f"ref_images.ref_image_{index}"
            for node_id in ("136", "158"):
                load_id = workflow[node_id]["inputs"][key][0]
                self.assertEqual(workflow[load_id]["inputs"]["image"], f"image{index}.png")
        for index in range(3):
            self.assertIn(f"ref_audios.ref_audio_{index}", workflow["136"]["inputs"])

    def test_ref2va_turbo8_preserves_references_and_fixed_steps(self):
        for model, face in (("minimax-h3-ref-turbo8", False),
                            ("minimax-h3-ref-turbo8-face-refine", True)):
            with self.subTest(model=model):
                workflow = self.engine.assemble_workflow(
                    ImageGenerationRequest(model=model, prompt="portrait dialogue",
                                           size="1344x768", duration=5, seed=456,
                                           steps=4, extra_body={"steps": 20}),
                    uploaded_ref_images=[f"image{i}.png" for i in range(5)],
                    uploaded_ref_audios=[f"audio{i}.wav" for i in range(3)],
                )
                self.assertEqual(workflow["124"]["inputs"]["steps"], 8)
                self.assertEqual(workflow["124"]["inputs"]["model"], ["145", 0])
                self.assertEqual(workflow["126"]["inputs"]["model"], ["145", 0])
                self.assertEqual(workflow["145"]["inputs"]["lora_name"],
                                 "minimax_h3_ref2v_turbo_8step_v1.0_768p_comfyui_bf16.safetensors")
                self.assertEqual(workflow["129"]["inputs"]["noise_seed"], 456)
                self.assertNotIn("137", workflow)
                self.assertNotIn("147", workflow)
                self.assertEqual(workflow["136"]["inputs"]["length"], 124)
                for index in range(5):
                    key = f"ref_images.ref_image_{index}"
                    for node_id in (("136", "158") if face else ("136",)):
                        load_id = workflow[node_id]["inputs"][key][0]
                        self.assertEqual(workflow[load_id]["inputs"]["image"], f"image{index}.png")
                for index in range(3):
                    key = f"ref_audios.ref_audio_{index}"
                    load_id = workflow["136"]["inputs"][key][0]
                    self.assertEqual(workflow[load_id]["inputs"]["audio"], f"audio{index}.wav")
                self.assertEqual(workflow["130"]["inputs"]["audio"], ["121", 0])
                if face:
                    self.assertEqual(workflow["162"]["inputs"]["steps"], 8)
                    self.assertEqual(workflow["162"]["inputs"]["denoise"], 0.4)
                    self.assertEqual(workflow["160"]["inputs"]["model"], ["145", 0])
                    self.assertEqual(workflow["158"]["inputs"]["ref_audios.ref_audio_0"], ["121", 0])
                    self.assertEqual(workflow["158"]["inputs"]["prompt"], "portrait dialogue")
                    self.assertEqual(workflow["130"]["inputs"]["images"], ["165", 0])
                else:
                    self.assertEqual(workflow["130"]["inputs"]["images"], ["122", 0])

    def test_four_reference_audios_are_rejected_instead_of_truncated(self):
        request = ImageGenerationRequest(
            model="minimax-h3-ref-turbo4-face-refine", prompt="test", ref_images=["face.png"],
            references=[{"referenceType": "REFERENCE_AUDIO", "referenceAudio": f"a{i}.wav"}
                        for i in range(4)],
        )
        with self.assertRaisesRegex(ValueError, "at most 3 reference audios"):
            request.validate_minimax_h3_limits()


if __name__ == "__main__":
    unittest.main()
