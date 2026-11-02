package com.astra.freyja.service;

import com.astra.freyja.service.video.*;
import com.astra.freyja.service.video.RenderValidationReport.Slot;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.DramaShot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RenderInputValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private Slot image(int n) { return new Slot("<Picture " + n + ">", "p" + n, "IMAGE", "image" + n, "REFERENCE_IMAGE", false); }
    private RenderValidationReport check(String prompt, Slot... slots) { return RenderInputValidator.validate(2032095628944588801L, prompt, true, List.of(slots)); }
    @Test void missingAttachmentsAreReportedWithOriginalLineNumbers() {
        var result = check(RenderInputFixtures.reference("<Picture 1>\n<Picture 2>\n<Picture 3>"), image(1));
        assertFalse(result.isSubmissionReady());
        assertEquals(List.of("<Picture 2>", "<Picture 3>"), result.getIssues().stream().map(RenderValidationReport.Issue::label).toList());
        assertEquals(7, result.getIssues().getFirst().line());
        assertEquals(2032095628944588801L, result.getIssues().getFirst().shotId());
    }
    @Test void repeatedReferencesAreOneBindingAndUnusedAttachmentsFail() {
        assertTrue(check(RenderInputFixtures.reference("<Picture 1> <Picture 1>"), image(1)).isSubmissionReady());
        assertEquals("ATTACHED_REFERENCE_UNUSED", check(RenderInputFixtures.reference("<Picture 1>"), image(1), image(2)).getIssues().getFirst().code());
    }
    @Test void dialogueAndQuotedTextCannotDeclareBindings() {
        String prompt = RenderInputFixtures.reference("<Picture 1>") + "\n<d>[Chinese] <Picture 9></d>\n\"<Picture 8>\"\n“<Video 7>”";
        assertTrue(check(prompt, image(1)).isSubmissionReady());
        assertFalse(check(RenderInputFixtures.reference("<d>[English] <Picture 1></d>"), image(1)).isSubmissionReady());
    }
    @Test void malformedAndBareLabelsFailWithoutGuessingNaturalLanguage() {
        for (String label : List.of("<Image 1>", "<Picture1>", "<Picture 2", "<Picture 0>", "<Picture 01>", "<picture 1>", "Picture 1", "<Picture 999999999999999999999999999>"))
            assertFalse(check(RenderInputFixtures.reference(label)).isSubmissionReady(), label);
        assertTrue(check(RenderInputFixtures.reference("三个人站在一起。")).isSubmissionReady());
    }
    @Test void subjectsAreDefinitionsNotImageCounts() {
        String prompt = RenderInputFixtures.reference("<Picture 1>").replace("subject_definitions:\nN/A", "subject_definitions:\n<Subject 1> is the woman in <Picture 1>.\n<Subject 2> is the man in <Picture 1>.")
                .replace("The camera observes the scene.", "<Subject 1> speaks to <Subject 2>.");
        assertTrue(check(prompt, image(1)).isSubmissionReady());
        assertTrue(check(prompt.replace("<Subject 2> is", "<Subject 3> is"), image(1)).getIssues().stream().anyMatch(i -> i.code().equals("SUBJECT_UNDEFINED")));
    }
    @Test void sectionsMustBeCompleteOrderedAndNonempty() {
        String valid = RenderInputFixtures.reference("");
        for (String invalid : List.of(valid.replace("summary:", "summary:\n\nsummary:"), valid.replace("summary:\nA continuous scene.", "summary:"), valid.replace("overall_soundscape:", "integrated_multimodal_description:")))
            assertFalse(check(invalid).isSubmissionReady());
    }
    @Test void plannedFramesAreAllowedForAdmissionButNeverDispatch() {
        var shot = new DramaShot(); shot.setId(1L); shot.setGenerationMode("FIRST_LAST_FRAME"); shot.setPreviewImageUrl("first.png");
        var snap = new VideoBatchModels.Snapshot(); snap.setShot(shot);
        var req = new DramaShotRenderRequestDTO(); req.setPrompt(RenderInputFixtures.FL); snap.setRequest(req);
        var plan = new VideoBatchModels.FramePlan(); plan.setType("PRIOR_BATCH_RESULT"); plan.setSourceShotId(2L); plan.setSourceTaskId("source-task"); snap.setLastFramePlan(plan);
        var admitted = RenderInputResolver.inspect(snap, false, json);
        assertTrue(admitted.isSubmissionReady()); assertFalse(admitted.isDispatchReady());
        assertFalse(RenderInputResolver.inspect(snap, true, json).isSubmissionReady());
        shot.setEndFrameImageUrl("last.png"); assertTrue(RenderInputResolver.inspect(snap, true, json).isDispatchReady());
    }
    @Test void videoSoundtracksPrecedeStandaloneAudioAndDuplicatesKeepTheirSlots() throws Exception {
        var shot = new DramaShot(); shot.setId(1L); shot.setGenerationMode("REFERENCE_MODE");
        shot.setRefImagesJson(json.writeValueAsString(List.of(ShotRefImageDTO.builder().id("one").imageUrl("same.png").build(), ShotRefImageDTO.builder().id("two").imageUrl("same.png").build())));
        shot.setRefAudiosJson(json.writeValueAsString(List.of(ShotRefAudioDTO.builder().id("voice").audioUrl("voice.wav").build())));
        var video = new ResolvedShotVideoReference(); video.setId("video"); video.setObjectPath("clip.mp4"); video.setIncludeAudio(true);
        var req = new DramaShotRenderRequestDTO(); req.setResolvedRefVideos(List.of(video)); req.setPrompt(RenderInputFixtures.reference("<Picture 1> <Picture 2> <Video 1> <Audio 1> <Audio 2>"));
        var snap = new VideoBatchModels.Snapshot(); snap.setShot(shot); snap.setRequest(req);
        var result = RenderInputResolver.inspect(snap, true, json); assertTrue(result.isDispatchReady());
        assertEquals(List.of("<Picture 1>", "<Picture 2>", "<Video 1>", "<Audio 1>", "<Audio 2>"), result.getSlots().stream().map(Slot::label).toList());
        assertEquals("video", result.getSlots().get(3).referenceId()); assertEquals("voice", result.getSlots().get(4).referenceId());
        var original = result.getInputFingerprint(); req.setPrompt(req.getPrompt()+"\nChanged");
        assertNotEquals(original, RenderInputResolver.inspect(snap, true, json).getInputFingerprint());
    }
}
