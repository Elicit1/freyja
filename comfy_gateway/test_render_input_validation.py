import copy
import hashlib
import unittest
from config import load_config
from schemas import ImageGenerationRequest
from workflow_engine import WorkflowEngine
from render_input_validation import validate_transport, validate_workflow


def bind_contract(request):
    """A test caller builds the wire contract, separately from gateway validation."""
    def slot(label, kind, role, reference_id, source):
        return dict(label=label, kind=kind, role=role, referenceId=reference_id,
                    mediaHash=hashlib.sha256(source.encode()).hexdigest())
    slots = []
    if request.prompt_format == "MINIMAX_H3_FL2VA_V1":
        slots = [slot("<Picture 1>", "IMAGE", "FIRST_FRAME", "FIRST_FRAME", request.first_frame),
                 slot("<Picture 2>", "IMAGE", "LAST_FRAME", "LAST_FRAME", request.last_frame)]
    else:
        picture_index = 0
        for ref in request.references or []:
            for image in ref.images or []:
                picture_index += 1
                slots.append(slot(f"<Picture {picture_index}>", "IMAGE", "REFERENCE_IMAGE", ref.reference_id, image))
        audio_index = 0
        for index, video in enumerate(request.get_all_ref_videos()):
            slots.append(slot(f"<Video {index+1}>", "VIDEO", "REFERENCE_VIDEO", video.reference_id, video.video_url))
            if video.include_audio:
                audio_index += 1
                slots.append(slot(f"<Audio {audio_index}>", "AUDIO", "VIDEO_AUDIO", video.reference_id, video.video_url))
        for ref in request.references or []:
            if ref.reference_type.value == "REFERENCE_AUDIO" and ref.audio_url:
                audio_index += 1
                slots.append(slot(f"<Audio {audio_index}>", "AUDIO", "REFERENCE_AUDIO", ref.reference_id, ref.audio_url))
    request.render_input_contract = dict(version="h3-render-input/1", slots=slots,
                                        promptHash=hashlib.sha256(request.prompt.encode()).hexdigest())
    return request


class RenderInputValidationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = WorkflowEngine(load_config())

    def request(self, model="minimax-h3-ref-turbo8"):
        return bind_contract(ImageGenerationRequest(model=model, prompt="<Picture 1> <Picture 2> <Audio 1>",
            prompt_format="MINIMAX_H3_REF2VA_V1", references=[
                dict(referenceId="a", referenceType="UPLOAD", referenceImages=["same.png"]),
                dict(referenceId="b", referenceType="UPLOAD", referenceImages=["same.png"]),
                dict(referenceId="voice", referenceType="REFERENCE_AUDIO", referenceAudio="voice.wav")]))

    def assemble(self, request):
        return self.engine.assemble_workflow(request, uploaded_ref_images=["image-a.png", "image-b.png"], uploaded_ref_audios=["voice-a.wav"])

    def test_duplicate_media_keep_distinct_slots_and_face_refine_namespace(self):
        for model in ("minimax-h3-ref-turbo8", "minimax-h3-ref-turbo4-face-refine", "minimax-h3-face-refine"):
            with self.subTest(model=model):
                request = self.request(model)
                self.assertEqual(request.get_all_ref_images(), ["same.png", "same.png"])
                workflow = self.assemble(request)
                inputs = workflow["136"]["inputs"]
                self.assertEqual(workflow[inputs["ref_images.ref_image_1"][0]]["inputs"]["image"], "image-b.png")
                if model != "minimax-h3-ref-turbo8":
                    self.assertEqual(inputs["ref_audios.ref_audio_0"], workflow["158"]["inputs"]["ref_audios.ref_audio_0"])

    def test_prompt_or_order_changes_fail_before_assembly(self):
        request = self.request(); request.prompt += " changed"
        with self.assertRaisesRegex(ValueError, "prompt changed"):
            self.assemble(request)
        request = self.request(); request.references[0], request.references[1] = request.references[1], request.references[0]
        with self.assertRaisesRegex(ValueError, "ordered media"):
            validate_transport(request)

    def test_missing_upload_and_bad_actual_connections_are_rejected(self):
        request = self.request()
        with self.assertRaisesRegex(ValueError, "number of slots"):
            self.engine.assemble_workflow(request, uploaded_ref_images=["one.png"], uploaded_ref_audios=["voice-a.wav"])
        model = self.engine.config.get_model(request.model)
        for mutate in (lambda w: w["136"]["inputs"].pop("ref_images.ref_image_1"),
                       lambda w: w["136"]["inputs"].update(prompt="different"),
                       lambda w: w["136"]["inputs"].update({"ref_audios.ref_audio_0": ["missing", 0]}),
                       lambda w: w.pop("130")):
            workflow = self.assemble(request); mutate(workflow)
            with self.assertRaises(ValueError):
                validate_workflow(request, workflow, model, ["image-a.png", "image-b.png"], ["voice-a.wav"], [])

    def test_refine_cannot_truncate_references(self):
        request = self.request("minimax-h3-face-refine")
        for i in range(4):
            request.references.append(request.references[0].model_copy(update={"reference_id": f"more{i}"}))
        bind_contract(request)
        with self.assertRaisesRegex(ValueError, "image slots differ"):
            self.engine.assemble_workflow(request, uploaded_ref_images=[f"{i}.png" for i in range(6)], uploaded_ref_audios=["voice-a.wav"])

    def test_identical_first_last_frames_are_two_explicit_inputs(self):
        for model in ("minimax-h3-fl2va-turbo4", "minimax-h3-fl2va"):
            request = bind_contract(ImageGenerationRequest(model=model, prompt="frame transition", first_frame="same.png", last_frame="same.png", prompt_format="MINIMAX_H3_FL2VA_V1"))
            self.assertEqual(request.get_all_ref_images(), ["same.png", "same.png"])
            workflow = self.engine.assemble_workflow(request, uploaded_ref_images=["first-upload.png", "last-upload.png"])
            node = self.engine.config.get_model(model).mappings["prompt"][0]
            self.assertIn("first_frame", workflow[node]["inputs"])
            self.assertIn("last_frame", workflow[node]["inputs"])
            first = workflow[node]["inputs"]["first_frame"][0]
            last = workflow[node]["inputs"]["last_frame"][0]
            self.assertEqual(workflow[first]["inputs"]["image"], "first-upload.png")
            self.assertEqual(workflow[last]["inputs"]["image"], "last-upload.png")

    def test_formatted_requests_cannot_skip_the_contract(self):
        request = self.request(); request.render_input_contract = None
        with self.assertRaisesRegex(ValueError, "missing render_input_contract"):
            validate_transport(request)


if __name__ == "__main__":
    unittest.main()
