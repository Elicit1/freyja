package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.script.*;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.service.impl.ShotAiVisualPlanServiceImpl;
import com.astra.freyja.service.impl.ShotMergeServiceImpl;
import com.astra.freyja.util.UnifiedShotScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.converter.BeanOutputConverter;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CharacterVoiceUnifiedScriptTest {
    @Mock DramaShotMapper shots;
    @InjectMocks ShotAiVisualPlanServiceImpl prompts;

    private ShotPromptDeriveDTO input(List<ShotRefAudioDTO> audios) {
        return ShotPromptDeriveDTO.builder().shotId(1L).refImages(List.of()).characterRefs(List.of()).refAudios(audios).build();
    }

    @Test void explicitOptOutNeverReloadsSavedAudio() {
        assertTrue(prompts.buildReferenceManifest(input(List.of())).getAudios().isEmpty());
        verifyNoInteractions(shots);
    }

    @Test void omittedAudioRecoversSnapshotWithoutInventingDurationOrLanguage() {
        DramaShot saved = new DramaShot();
        saved.setRefAudiosJson("[{\"id\":\"voice\",\"audioUrl\":\"https://example.invalid/sample.wav\",\"usageMode\":\"VOICE_TIMBRE\"}]");
        when(shots.selectById(1L)).thenReturn(saved);
        var manifest = prompts.buildReferenceManifest(input(null));
        assertEquals(1, manifest.getAudios().size());
        assertNull(manifest.getAudios().getFirst().getDuration());
        assertNull(manifest.getAudios().getFirst().getLanguage());
    }

    @Test void overCapacityIsRejectedBeforeAnyTruncation() {
        var audios = java.util.stream.IntStream.range(0, 4).mapToObj(i -> ShotRefAudioDTO.builder()
                .audioUrl("https://example.invalid/" + i + ".wav").sourceType("UPLOAD").build()).toList();
        assertThrows(BizException.class, () -> prompts.buildReferenceManifest(input(audios)));
    }

    @Test void absentCharacterAndPronounAliasNeverCreateAnOnScreenReference() {
        WorkerShotResult.WorkerShotVO shot = new WorkerShotResult.WorkerShotVO();
        shot.setScriptContent("张三不在现场，李四想起她说过的话。时间：深夜。");
        var scene = WorkerShotResult.WorkerSceneVO.builder().shots(List.of(shot)).build();
        var worker = WorkerShotResult.builder().scenes(List.of(scene)).build();
        var context = GlobalStoryContext.builder().characters(List.of(DecomposedCharacterVO.builder()
                .name("张三").aliases(List.of("她")).build())).build();
        var result = new ShotMergeServiceImpl().mergeSegmentResults(List.of(SegmentShotResult.builder()
                .sequence(1).shotResult(worker).build()), PlannerDecomposeResultVO.builder().build(), context, log -> {});
        assertTrue(result.getEpisodes().getFirst().getScenes().getFirst().getShots().getFirst().getCharacterNames().isEmpty());
        var schema = new BeanOutputConverter<>(WorkerShotResult.class).getFormat();
        assertFalse(schema.contains("\"dialogue\""));
        assertFalse(schema.contains("\"voiceover\""));
    }

    @Test void legacySpeechIsVisibleAndNeverGuessesMissingSpeaker() {
        DramaShot saved = new DramaShot(); saved.setScriptContent("人物推门。");
        saved.setDialogue("不要过来。"); saved.setVoiceover("他仍不知道真相。");
        String script = UnifiedShotScript.fromShot(saved);
        assertTrue(script.contains("人物推门。"));
        assertTrue(script.contains("未指定说话人：“不要过来。”"));
        assertTrue(script.contains("他仍不知道真相。"));
    }

    @Test void legacyRedisDraftProjectionPreservesSpeechAndIsIdempotent() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var raw = json.readTree("{\"episodes\":[{\"scenes\":[{\"shots\":[{\"scriptContent\":\"开门。\",\"dialogue\":\"别动。\",\"dialogueSpeaker\":\"画外的人\",\"voiceover\":\"夜深了。\"}]}]}]}");
        var migrated = com.astra.freyja.util.LegacyShotJsonMigration.projectDraft(raw);
        String encoded = migrated.toString();
        assertTrue(encoded.contains("别动。")); assertTrue(encoded.contains("画外的人")); assertTrue(encoded.contains("夜深了。"));
        assertFalse(encoded.contains("\"dialogue\""));
        assertEquals(migrated, com.astra.freyja.util.LegacyShotJsonMigration.projectDraft(migrated));
        assertTrue(raw.toString().contains("\"dialogue\""));
    }

    @Test void oldPromptTaskInputsMigrateBothScriptAndDirectorInputWithoutInjectingLegacyAudio() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var raw = json.readTree("{\"shotId\":\"1\",\"dialogue\":\"别动。\",\"audioUrl\":\"legacy.wav\",\"directorPlanInput\":{\"dialogue\":\"别动。\"},\"refAudios\":[{\"sourceType\":\"UPLOAD\",\"audioUrl\":\"selected.wav\"}]}");
        var migrated = com.astra.freyja.util.LegacyShotJsonMigration.projectPromptInput(raw);
        var dto = json.treeToValue(migrated, ShotPromptDeriveDTO.class);
        assertTrue(dto.getScriptContent().contains("别动。"));
        assertTrue(dto.getDirectorPlanInput().scriptContent().contains("别动。"));
        assertFalse(migrated.has("audioUrl"));
        assertEquals("selected.wav", dto.getRefAudios().getFirst().getAudioUrl());
    }

    @Test void voiceTimbreMustBelongToTheExplicitSpeakerWhileAuthorizedRecordingKeepsItsMode() {
        var voice = ShotRefAudioDTO.builder().audioUrl("https://example.invalid/voice.wav")
                .sourceType("CHARACTER").characterId(201L).usageMode("VOICE_TIMBRE").build();
        var manifest = prompts.buildReferenceManifest(input(List.of(voice)));
        ShotSpeechLine line = new ShotSpeechLine(); line.setKind(ShotSpeechLine.Kind.OFF_SCREEN_DIALOGUE);
        line.setText("别动。"); line.setLanguage("Chinese"); line.setCharacterId(202L); line.setAudioIndex(1);
        line.setDialogueTag("<d>[Chinese] 未具名的人: \"别动。\"</d>");
        var output = output(List.of(line), "<Audio 1> " + line.getDialogueTag());
        assertTrue(prompts.validateAndNormalize(output, "REFERENCE_MODE", manifest, "有人说：“别动。”", true).hasErrors());
        var recording = ShotRefAudioDTO.builder().audioUrl("https://example.invalid/current.wav")
                .sourceType("UPLOAD").usageMode("DIALOGUE_REUSE").build();
        var recordingManifest = prompts.buildReferenceManifest(input(List.of(recording)));
        assertFalse(prompts.validateAndNormalize(output, "REFERENCE_MODE", recordingManifest, "有人说：“别动。”", true).hasErrors());
        assertEquals("DIALOGUE_REUSE", recordingManifest.getAudios().getFirst().getUsageMode());
    }

    private ShotPromptDeriveVO output(List<ShotSpeechLine> lines, String speech) {
        return ShotPromptDeriveVO.builder().speechLines(lines).prompt("subject_definitions:\nnone\nsummary:\nnone\nretention_analysis:\nnone\ndetailed_description:\nnone\noverall_soundscape:\n"
                + speech + "\nnon_diegetic_music:\nN/A").build();
    }

    @Test void punctuationWithoutSpeechIsAcceptedAndMissingSpeechStructureIsRejected() {
        assertFalse(prompts.validateAndNormalize(output(List.of(), "N/A"), "REFERENCE_MODE", null, "时间：深夜", true).hasErrors());
        assertTrue(prompts.validateAndNormalize(output(null, "N/A"), "REFERENCE_MODE", null, "时间：深夜", true).hasErrors());
    }

    @Test void actualValidationRejectsMissingDialogueTagAndPreservesOffScreenSpeech() {
        ShotSpeechLine line = new ShotSpeechLine(); line.setKind(ShotSpeechLine.Kind.OFF_SCREEN_DIALOGUE);
        line.setSpeakerName("画外的人"); line.setText("别动。"); line.setLanguage("Chinese");
        line.setDialogueTag("<d>[Chinese] 画外的人: \"别动。\"</d>");
        var bad = prompts.validateAndNormalize(output(List.of(line), "No speech tag"), "REFERENCE_MODE", null, "画外的人：“别动。”", true);
        assertTrue(bad.hasErrors());
        var good = prompts.validateAndNormalize(output(List.of(line), line.getDialogueTag()), "REFERENCE_MODE", null, "画外的人：“别动。”", true);
        assertFalse(good.hasErrors());
        assertNull(good.getResult().getSpeechLines().getFirst().getCharacterId());
    }
}
