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
