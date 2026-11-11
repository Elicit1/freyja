import unittest
from dynamic_workflow import describe
from h3_extensions import revision
from schemas import ImageGenerationRequest
from video_reference import mixed_reference_count, workflow_revision
from workflow_engine import WorkflowEngine
from test_video_reference import setup_model, BASE
from test_render_input_validation import bind_contract


class ControlReferenceSlotsTest(unittest.TestCase):
    def request(self, enabled=True, audio=False, stage=None):
        model, config = setup_model()
        model.reference_video.max_videos = 3
        controls = {"enabled": enabled, "controls": [
            {"reference_id": "control", "control_type": "POSE"},
            {"reference_id": "control", "control_type": "DEPTH"}]}
        refs = [{"referenceId": source, "referenceType": "REFERENCE_VIDEO",
                 "referenceVideo": f"http://archive/{source}.mp4", "contentSha256": digest*64,
                 "includeAudio": audio and source == "control"}
                for source, digest in (("control", "a"), ("ordinary", "b"))]
        extra = {"h3_options": {"capability_revision": revision(model, BASE), "length": 124, "control_net": controls}}
        if stage:
            extra = {"dynamic_workflow": {"stage": stage, "revision": describe(model, BASE)["revision"],
                "control_source_ids": ["control"], "source_url": "http://archive/base.mp4",
                "source_sha256": "c"*64, "scale": 2 if stage == "UPSCALE" else 1,
                **({"component": {}} if stage == "UPSCALE" else {})}}
        request = bind_contract(ImageGenerationRequest(model=model.model_name, prompt="Continue the action.",
            size="960x544", length=124, references=refs, node_id=model.node_id,
            prompt_format="MINIMAX_H3_REF2VA_VIDEO_V1", reference_video_revision=workflow_revision(model, BASE), **extra))
        uploaded = [{"reference_id": source, "filename": f"{source}.mp4", "frames": 124,
                     "include_audio": audio and source == "control"} for source in ("control", "ordinary")]
        graph = WorkflowEngine(config).assemble_workflow(request, uploaded_ref_videos=uploaded,
            dynamic_source_filename="base.mp4" if stage else None)
        return model, request, graph

    def test_same_source_two_controls_replace_one_original_video_slot(self):
        model, request, graph = self.request()
        self.assertEqual(3, mixed_reference_count(request))  # two controls + one ordinary video
        cond = graph[model.h3_extensions["conditioning"]["id"]]["inputs"]
        self.assertEqual([("<Video 1>", "ordinary")], [(slot["label"], slot["referenceId"]) for slot in request.render_input_contract["slots"]])
        visual = [value for key, value in cond.items() if key.startswith("ref_videos.")]
        self.assertEqual(1, len(visual))
        self.assertEqual("ordinary.mp4", graph[visual[0][0]]["inputs"]["video"])
        self.assertEqual("control.mp4", graph["h3_control_video"]["inputs"]["video"])
        self.assertEqual("control.mp4", graph["h3_control_video_1"]["inputs"]["video"])

    def test_disabling_control_restores_both_ordinary_video_slots(self):
        model, request, graph = self.request(enabled=False)
        self.assertEqual(2, mixed_reference_count(request))
        cond = graph[model.h3_extensions["conditioning"]["id"]]["inputs"]
        self.assertEqual(2, sum(key.startswith("ref_videos.") for key in cond))
        self.assertNotIn("h3_control_video", graph)

    def test_explicit_source_audio_is_kept_without_restoring_its_visual_slot(self):
        model, request, graph = self.request(audio=True)
        self.assertEqual(4, mixed_reference_count(request))
        cond = graph[model.h3_extensions["conditioning"]["id"]]["inputs"]
        audio = cond["ref_video_audios.ref_video_audio_0"]
        self.assertEqual(2, audio[1])
        self.assertEqual("control.mp4", graph[audio[0]]["inputs"]["video"])
        self.assertEqual(1, sum(key.startswith("ref_videos.") for key in cond))

    def test_enhancements_do_not_reintroduce_control_sources_as_visual_references(self):
        for stage in ("UPSCALE", "FACE"):
            with self.subTest(stage=stage):
                model, request, graph = self.request(audio=True, stage=stage)
                cond_id = "dynamic_face_158" if stage == "FACE" else model.h3_extensions["conditioning"]["id"]
                cond = graph[cond_id]["inputs"]
                self.assertEqual(2, mixed_reference_count(request))  # ordinary video + explicit source audio
                visual = [value for key, value in cond.items() if key.startswith("ref_videos.")]
                self.assertEqual(1, len(visual))
                self.assertEqual("ordinary.mp4", graph[visual[0][0]]["inputs"]["video"])
                self.assertIn("ref_video_audios.ref_video_audio_0", cond)


if __name__ == "__main__":
    unittest.main()
