import unittest
from types import SimpleNamespace
from schemas import ImageGenerationRequest
from workflow_engine import WorkflowEngine


class FrameRoleTest(unittest.TestCase):
    def apply(self, request, uploaded):
        workflow = {"136": {"inputs": {}}}
        model = SimpleNamespace(mappings={})
        engine = WorkflowEngine.__new__(WorkflowEngine)
        engine._apply_minimax_h3_fl2va(workflow, model, uploaded, request)
        return workflow

    def test_last_frame_only_keeps_last_frame_role(self):
        request = ImageGenerationRequest(model="fl", prompt="motion", last_frame="last.png")
        workflow = self.apply(request, ["uploaded_last.png"])
        inputs = workflow["136"]["inputs"]
        self.assertNotIn("first_frame", inputs)
        last_node = inputs["last_frame"][0]
        self.assertEqual("uploaded_last.png", workflow[last_node]["inputs"]["image"])

    def test_explicit_roles_survive_other_reference_images(self):
        request = ImageGenerationRequest(model="fl", prompt="motion", ref_images=["other.png"], first_frame="first.png", last_frame="last.png")
        uploaded = ["uploaded_" + name for name in request.get_all_ref_images()]
        workflow = self.apply(request, uploaded)
        inputs = workflow["136"]["inputs"]
        self.assertEqual("uploaded_first.png", workflow[inputs["first_frame"][0]]["inputs"]["image"])
        self.assertEqual("uploaded_last.png", workflow[inputs["last_frame"][0]]["inputs"]["image"])

    def test_same_image_can_fill_both_roles(self):
        request = ImageGenerationRequest(model="fl", prompt="motion", first_frame="same.png", last_frame="same.png")
        workflow = self.apply(request, ["uploaded_same.png"])
        inputs = workflow["136"]["inputs"]
        for role in ("first_frame", "last_frame"):
            self.assertEqual("uploaded_same.png", workflow[inputs[role][0]]["inputs"]["image"])


if __name__ == "__main__":
    unittest.main()
