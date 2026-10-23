package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.ReferenceVideoProperties;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.video.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.util.ShotVideoReferenceValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShotVideoReferenceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final DramaShotMapper shots = mock(DramaShotMapper.class);
    private final AiModelMapper models = mock(AiModelMapper.class);
    private final VideoProcessSourceResolver sources = mock(VideoProcessSourceResolver.class);
    private final ShotVideoCapabilityService capabilities = mock(ShotVideoCapabilityService.class);
    private final ReferenceVideoMediaService media = mock(ReferenceVideoMediaService.class);
    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:ref_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    private ShotVideoReferenceService service;
    private DramaShot shot;
    private AiModel model;
    private ShotVideoModelOptionVO option;
    private ResolvedVideoProcessSource current;

    @BeforeEach void setup() throws Exception {
        jdbc.execute("CREATE TABLE shot_video_reference_preflight(preflight_id VARCHAR(64) PRIMARY KEY,shot_id BIGINT,provider_id BIGINT,model_code VARCHAR(100),input_fingerprint VARCHAR(64),status VARCHAR(20),data_json CLOB,created_at TIMESTAMP,expires_at TIMESTAMP)");
        service = new ShotVideoReferenceService(shots, models, sources, capabilities, media, new ReferenceVideoProperties(), jdbc, json, Runnable::run);
        shot = new DramaShot(); shot.setId(2032095628944588801L); shot.setDramaId(10L); shot.setGenerationMode("REFERENCE_MODE");
        var ref = new ShotRefVideoDTO(); ref.setId("video1"); ref.setSourceType("SHOT_CURRENT"); ref.setSourceShotId(2032095628944588802L);
        ref.setStartSeconds(new BigDecimal("1.25")); ref.setEndSeconds(new BigDecimal("5.25"));
        shot.setRefVideosJson(json.writeValueAsString(List.of(ref)));
        model = new AiModel(); model.setId(20L); model.setProviderId(30L); model.setModelType("TXT2VIDEO_REF"); model.setModelCode("test-video"); model.setStatus(1);
        option = new ShotVideoModelOptionVO(); option.setId(20L); option.setProviderId(30L); option.setModelCode("test-video"); option.setModelFingerprint("model1"); option.setWorkflowRevision("revision1"); option.setNodeId("node1"); option.setRenderable(true);
        var cap = new ReferenceVideoCapability(); cap.setMaxVideos(1); cap.setMaxTotalClipSeconds(BigDecimal.valueOf(15)); cap.setMaxMixedAssets(12); cap.setSupportsVideoAudio(false); option.setReferenceVideo(cap);
        current = ResolvedVideoProcessSource.builder().sourceType("SHOT_CURRENT").shotId(ref.getSourceShotId()).dramaId(10L).videoTakeId(40L).videoUrl("https://archive/current.mp4").build();
        var clip = new ResolvedShotVideoReference(); clip.setId("video1"); clip.setSourceVideoTakeId(40L); clip.setContentSha256("a".repeat(64)); clip.setObjectPath("reference-video/immutable/clip.mp4");
        clip.setStartSeconds(ref.getStartSeconds()); clip.setEndSeconds(ref.getEndSeconds()); clip.setMetadata(new VideoProbeInfoVO()); clip.setIncludeAudio(false);
        when(shots.selectById(shot.getId())).thenReturn(shot); when(models.selectOne(any())).thenReturn(model);
        when(capabilities.describe(model)).thenReturn(option); when(sources.resolveForProbe(any())).thenAnswer(invocation -> current);
        when(media.prepare(any(), any(), any())).thenReturn(clip); when(media.transportUrl(any())).thenReturn("https://archive/temporary-preview");
    }

    private DramaShotRenderRequestDTO request() { var req = new DramaShotRenderRequestDTO(); req.setProviderId(30L); req.setWorkflowTemplateId("test-video"); return req; }

    @Test void multipleVideosFollowModelCountPreserveOrdinalsAndCheckTotalDuration() throws Exception {
        var first = ShotVideoReferenceValidator.parse(shot.getRefVideosJson(), json).getFirst();
        var second = json.readValue(json.writeValueAsString(first), ShotRefVideoDTO.class); second.setId("video2");
        shot.setRefVideosJson(json.writeValueAsString(List.of(first, second)));
        model.setMaxVideos(2); option.getReferenceVideo().setMaxVideos(2);
        when(media.prepare(any(), any(), any())).thenAnswer(call -> {
            ShotRefVideoDTO input = call.getArgument(0);
            var ref = new ResolvedShotVideoReference(); ref.setId(input.getId()); ref.setStartSeconds(input.getStartSeconds()); ref.setEndSeconds(input.getEndSeconds());
            ref.setObjectPath("reference-video/" + input.getId() + "/clip.mp4"); ref.setMetadata(new VideoProbeInfoVO()); ref.setIncludeAudio(false); return ref;
        });
        var result = service.start(shot.getId(), request()); assertEquals("READY", result.getStatus());
        assertEquals(List.of(1, 2), result.getReferences().stream().map(ResolvedShotVideoReference::getOrdinal).toList());
        option.getReferenceVideo().setMaxVideos(1);
        assertThrows(BizException.class, () -> service.start(shot.getId(), request()));
        option.getReferenceVideo().setMaxVideos(2); option.getReferenceVideo().setMaxTotalClipSeconds(BigDecimal.valueOf(7));
        assertEquals("FAILED", service.start(shot.getId(), request()).getStatus());
    }

    @Test void sourceChangeBetweenPreparationAndSubmissionRequiresNewPreflight() {
        var prepared = service.start(shot.getId(), request());
        assertEquals("READY", prepared.getStatus());
        assertNotNull(service.forSubmission(shot, model, prepared.getPreflightId()));
        current.setVideoTakeId(41L); current.setVideoUrl("https://archive/new-take.mp4");
        BizException error = assertThrows(BizException.class, () -> service.forSubmission(shot, model, prepared.getPreflightId()));
        assertEquals(409, error.getCode()); assertTrue(error.getMessage().contains("SOURCE_CHANGED"));
        assertEquals(40L, service.get(shot.getId(), prepared.getPreflightId()).getReferences().getFirst().getSourceVideoTakeId());
    }

    @Test void preflightSurvivesServiceRecreationAndDoesNotPersistSignedUrls() {
        var prepared = service.start(shot.getId(), request());
        var restarted = new ShotVideoReferenceService(shots, models, sources, capabilities, media, new ReferenceVideoProperties(), jdbc, json, Runnable::run);
        assertEquals(prepared.getPreflightId(), restarted.forSubmission(shot, model, null).getPreflightId());
        String stored = jdbc.queryForObject("SELECT data_json FROM shot_video_reference_preflight", String.class);
        assertFalse(stored.contains("temporary-preview"));
        assertThrows(BizException.class, () -> service.get(123L, prepared.getPreflightId()));
    }

    @Test void changingClipAudioOrModelInvalidatesPreparation() throws Exception {
        var prepared = service.start(shot.getId(), request());
        var inputs = ShotVideoReferenceValidator.parse(shot.getRefVideosJson(), json);
        inputs.getFirst().setIncludeAudio(true); shot.setRefVideosJson(json.writeValueAsString(inputs));
        assertThrows(BizException.class, () -> service.forSubmission(shot, model, prepared.getPreflightId()));
        inputs.getFirst().setIncludeAudio(false); shot.setRefVideosJson(json.writeValueAsString(inputs));
        option.setModelFingerprint("changed-model");
        assertThrows(BizException.class, () -> service.forSubmission(shot, model, prepared.getPreflightId()));
    }

    @Test void unverifiedModelCanPrepareDraftButCannotSubmit() {
        option.setRenderable(false);
        var prepared = service.start(shot.getId(), request());
        assertEquals("READY", prepared.getStatus());
        doThrow(new BizException(409, "REFERENCE_VIDEO_UNVERIFIED")).when(capabilities).requireRenderable(option);
        assertThrows(BizException.class, () -> service.forSubmission(shot, model, prepared.getPreflightId()));
    }

    @Test void embeddedAuditJsonKeepsSnowflakeIdsExactForBrowserParsing() throws Exception {
        var reference = new ResolvedShotVideoReference(); reference.setSourceShotId(2032095628944588801L); reference.setSourceVideoTakeId(2032095628944588802L);
        String stored = com.astra.freyja.util.VideoReferenceSnapshotJson.write(List.of(reference), json);
        var tree = json.readTree(stored).get(0);
        assertTrue(tree.path("sourceShotId").isTextual()); assertEquals("2032095628944588802", tree.path("sourceVideoTakeId").asText());
        assertEquals(2032095628944588802L, json.readValue(tree.toString(), ResolvedShotVideoReference.class).getSourceVideoTakeId());
    }

    @Test void invalidRangesAndMixedSourceFieldsAreRejectedWithoutNetwork() {
        var ref = new ShotRefVideoDTO(); ref.setId("video1"); ref.setSourceType("SHOT_CURRENT"); ref.setSourceShotId(2032095628944588802L); ref.setSourceVideoTakeId(40L);
        assertThrows(BizException.class, () -> ShotVideoReferenceValidator.validate(List.of(ref)));
        ref.setSourceVideoTakeId(null); ref.setStartSeconds(BigDecimal.TEN); ref.setEndSeconds(BigDecimal.ONE);
        assertThrows(BizException.class, () -> ShotVideoReferenceValidator.validate(List.of(ref)));
        assertEquals(List.of(), ShotVideoReferenceValidator.parse("[]", json));
    }
}
