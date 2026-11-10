package com.astra.freyja.service;

import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.video.*;
import com.astra.freyja.util.CryptoUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ShotContinuationServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private ShotContinuationService service() throws Exception {
        var service=spy(new ShotContinuationService(new ShotContinuationPlanner(),mock(ShotVideoReferenceService.class),json,mock(CryptoUtil.class),new com.astra.freyja.config.ReferenceVideoProperties()));
        doReturn(json.readTree("{\"healthy\":true,\"nodeId\":\"gpu\",\"h3\":{\"status\":\"VERIFIED\",\"revision\":\"r1\",\"continuation\":true}}"))
                .when(service).capabilities(any(),any());return service;
    }
    @Test void onlyExplicitSegmentReferencesAreCopiedAndGlobalMediaAreNotInherited() throws Exception {
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(30);new ShotContinuationPlanner().plan(draft,true);
        draft.getSegments().forEach(s->s.setPrompt("One continuous action"));
        var image=new ShotRefImageDTO();image.setId("binding");image.setImageUrl("https://archive/character.png");draft.getSegments().getFirst().setReferenceImages(List.of(image));
        var shot=shot(draft,"REFERENCE_MODE");var model=model("TXT2VIDEO_REF");var result=service().prepare(shot,model,provider(),"960x544",null);
        var children=result.getRequest().getContinuationSegments();assertEquals(7,children.size());
        assertTrue(children.getFirst().getShot().getRefImagesJson().contains("binding"));
        assertEquals("[]",children.get(1).getShot().getRefImagesJson());
        for(var child:children){assertNull(child.getShot().getPreviewImageUrl());assertNull(child.getShot().getEndFrameImageUrl());assertEquals("[]",child.getShot().getRefAudiosJson());assertFalse((Boolean)((Map<?,?>)child.getRequest().getH3Options().get("control_net")).get("enabled"));assertFalse(child.getRequest().getH3Options().containsKey("lock_context"));}
    }
    @Test void firstLastWorkflowUsesOnlyPerSegmentExplicitAnchors() throws Exception {
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(30);new ShotContinuationPlanner().plan(draft,true);
        draft.getSegments().forEach(s->s.setPrompt("Continue walking"));draft.getSegments().get(1).setFirstFrameImageUrl("https://archive/explicit.png");
        var result=service().prepare(shot(draft,"FIRST_LAST_FRAME"),model("TXT2VIDEO_FIRST_LAST"),provider(),"960x544",null);
        var children=result.getRequest().getContinuationSegments();assertNull(children.getFirst().getShot().getPreviewImageUrl());
        assertEquals("https://archive/explicit.png",children.get(1).getShot().getPreviewImageUrl());assertNull(children.getLast().getShot().getEndFrameImageUrl());
    }
    @Test void fl2vaUsesOnlyPerSegmentFramesAndPreservesInactiveReferenceDraft() throws Exception {
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(8);new ShotContinuationPlanner().plan(draft,true);
        for(int i=0;i<draft.getSegments().size();i++){
            var segment=draft.getSegments().get(i);segment.setPrompt("Continuous motion");
            segment.setFirstFrameImageUrl("https://archive/first-"+i+".png");segment.setLastFrameImageUrl("https://archive/last-"+i+".png");
            var image=new ShotRefImageDTO();image.setId("image");image.setImageUrl("https://archive/ref.png");segment.getReferenceImages().add(image);
            var video=new ShotRefVideoDTO();video.setId("video");video.setSourceType("DIRECT_URL");video.setSourceVideoUrl("https://archive/motion.mp4");segment.getReferenceVideos().add(video);
            var audio=new ShotRefAudioDTO();audio.setAudioUrl("https://archive/audio.wav");segment.getReferenceAudios().add(audio);segment.setControlNetEnabled(true);segment.setControlVideoId("video");
        }
        var service=service();var model=model("TXT2VIDEO_FIRST_LAST");var plan=service.preview(draft,false,model,provider());assertTrue(plan.isRenderable(),plan.getIssues().toString());
        var shot=shot(draft,"FIRST_LAST_FRAME");var children=service.prepare(shot,model,provider(),"960x544",null).getRequest().getContinuationSegments();
        assertEquals(2,children.size());
        for(int i=0;i<children.size();i++){
            var child=children.get(i);assertEquals("https://archive/first-"+i+".png",child.getShot().getPreviewImageUrl());assertEquals("https://archive/last-"+i+".png",child.getShot().getEndFrameImageUrl());
            assertEquals("[]",child.getShot().getRefImagesJson());assertEquals("[]",child.getShot().getRefVideosJson());assertEquals("[]",child.getShot().getRefAudiosJson());
            assertFalse((Boolean)((Map<?,?>)child.getRequest().getH3Options().get("control_net")).get("enabled"));assertNull(child.getRequest().getResolvedRefVideos());
        }
        var first=draft.getSegments().getFirst();assertEquals(1,first.getReferenceVideos().size());assertTrue(first.isControlNetEnabled());
        first.getReferenceImages().getFirst().setImageUrl("https://archive/other-ref.png");assertEquals(plan.getPlanHash(),service.preview(draft,false,model,provider()).getPlanHash());
    }
    @Test void ref2vaUsesReferenceMediaWithoutActivatingSavedFrameAnchors() throws Exception {
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(8);new ShotContinuationPlanner().plan(draft,true);draft.getSegments().forEach(s->s.setPrompt("Continuous action"));
        var segment=draft.getSegments().getFirst();segment.setFirstFrameImageUrl("https://archive/first.png");segment.setLastFrameImageUrl("https://archive/last.png");
        var image=new ShotRefImageDTO();image.setId("ref");image.setImageUrl("https://archive/ref.png");segment.getReferenceImages().add(image);
        var service=service();var model=model("TXT2VIDEO_REF");var first=service.preview(draft,false,model,provider());assertTrue(first.isRenderable(),first.getIssues().toString());
        var children=service.prepare(shot(draft,"REFERENCE_MODE"),model,provider(),"960x544",null).getRequest().getContinuationSegments();
        assertNull(children.getFirst().getShot().getPreviewImageUrl());assertNull(children.getFirst().getShot().getEndFrameImageUrl());assertTrue(children.getFirst().getShot().getRefImagesJson().contains("ref.png"));
        segment.setFirstFrameImageUrl("https://archive/other-first.png");assertEquals(first.getPlanHash(),service.preview(draft,false,model,provider()).getPlanHash());assertNotNull(segment.getLastFrameImageUrl());
        var empty=new ShotContinuation();empty.setEnabled(true);empty.setDurationSeconds(8);new ShotContinuationPlanner().plan(empty,true);empty.getSegments().forEach(s->s.setPrompt("Continuous action"));
        assertNotEquals(service.preview(empty,false,model,provider()).getPlanHash(),service.preview(empty,false,model("TXT2VIDEO_FIRST_LAST"),provider()).getPlanHash());
    }
    @Test void segmentMetadataIsExplicitAndParentIdentityDoesNotLeak()throws Exception{
        var d=new ShotContinuation();d.setEnabled(true);d.setDurationSeconds(10);new ShotContinuationPlanner().plan(d,true);d.getSegments().forEach(s->s.setPrompt("Continuous walk"));
        var segment=d.getSegments().getFirst();segment.setName("独立片段");segment.setScriptContent("本段剧本");segment.setResSceneId(2032095628944588801L);
        var c=new com.astra.freyja.dto.res.CharacterShotRefDTO();c.setCharacterId(2032095628944588802L);segment.getCharacterRefs().add(c);
        var shot=shot(d,"REFERENCE_MODE");shot.setResSceneId(99L);shot.setScriptContent("parent");shot.setCharacterRefsJson("[{\"characterId\":\"99\"}]");
        var children=service().prepare(shot,model("TXT2VIDEO_REF"),provider(),"960x544",null).getRequest().getContinuationSegments();
        assertEquals("独立片段",children.getFirst().getShot().getShotName());assertEquals("本段剧本",children.getFirst().getShot().getScriptContent());assertEquals(segment.getResSceneId(),children.getFirst().getShot().getResSceneId());
        assertTrue(children.getFirst().getShot().getCharacterRefsJson().contains("\"2032095628944588802\""));
        assertNull(children.get(1).getShot().getResSceneId());assertNull(children.get(1).getShot().getScriptContent());assertEquals("[]",children.get(1).getShot().getCharacterRefsJson());
    }
    @Test void inactiveLongDraftSurvivesOrdinaryDuration()throws Exception{
        var d=new ShotContinuation();d.setDurationSeconds(60);var shot=shot(d,"REFERENCE_MODE");shot.setDuration(BigDecimal.valueOf(5));
        assertEquals(60,service().read(shot).getDurationSeconds());
    }
    @Test void legacyAcceptanceFieldsDoNotBlockAndZeroModelLimitIdentifiesEveryAffectedSegment()throws Exception{
        var service=service();
        doReturn(json.readTree("{\"healthy\":true,\"referenceVideo\":{\"adapter\":\"MINIMAX_H3_REF_VIDEO_V1\",\"status\":\"UNVERIFIED\",\"maxVideos\":3},\"h3\":{\"status\":\"UNVERIFIED\",\"continuation\":true}}"))
                .when(service).capabilities(any(),any());
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(30);new ShotContinuationPlanner().plan(draft,true);
        for(var segment:draft.getSegments()){
            segment.setPrompt("Continuous action");var video=new ShotRefVideoDTO();video.setId("video-"+segment.getId());video.setSourceType("DIRECT_URL");video.setSourceVideoUrl("https://archive.test/reference.mp4");segment.getReferenceVideos().add(video);
        }
        var model=model("TXT2VIDEO_REF");model.setMaxVideos(0);
        var preview=service.preview(draft,false,model,provider());
        assertFalse(preview.isRenderable());assertEquals(1,preview.getIssues().size());
        assertTrue(preview.getIssues().getLast().contains("片段 1、2、3、4、5、6、7"));
        assertTrue(preview.getIssues().getLast().contains("模型中心：0，网关：3"));
        model.setMaxVideos(1);var corrected=service.preview(draft,false,model,provider());
        assertTrue(corrected.getIssues().isEmpty());assertTrue(corrected.isRenderable());
    }
    @Test void verifiedReferenceCountLimitStillBlocksOnlyAffectedSegments()throws Exception{
        var service=service();
        doReturn(json.readTree("{\"healthy\":true,\"referenceVideo\":{\"adapter\":\"MINIMAX_H3_REF_VIDEO_V1\",\"status\":\"VERIFIED\",\"maxVideos\":1},\"h3\":{\"status\":\"VERIFIED\",\"continuation\":true}}"))
                .when(service).capabilities(any(),any());
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(8);new ShotContinuationPlanner().plan(draft,true);
        for(var segment:draft.getSegments())segment.setPrompt("Continuous action");
        for(int i=0;i<2;i++){var v=new ShotRefVideoDTO();v.setId("v"+i);v.setSourceType("DIRECT_URL");v.setSourceVideoUrl("https://archive.test/"+i+".mp4");draft.getSegments().getLast().getReferenceVideos().add(v);}
        var model=model("TXT2VIDEO_REF");model.setMaxVideos(3);
        var result=service.preview(draft,false,model,provider());
        assertFalse(result.isRenderable());assertEquals(1,result.getIssues().size());assertTrue(result.getIssues().getFirst().contains("片段 2 的参考视频数量超过上限 1"));
    }
    @Test void sevenContinuationSlicesDoNotConsumeOneSharedVideoCountOrDurationBudget()throws Exception{
        var service=service();
        doReturn(json.readTree("{\"healthy\":true,\"referenceVideo\":{\"adapter\":\"MINIMAX_H3_REF_VIDEO_V1\",\"status\":\"VERIFIED\",\"maxVideos\":1,\"maxClipSeconds\":6,\"maxTotalClipSeconds\":6,\"maxDecodedFrames\":144},\"h3\":{\"status\":\"VERIFIED\",\"continuation\":true}}"))
                .when(service).capabilities(any(),any());
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(30);new ShotContinuationPlanner().plan(draft,true);
        for(var segment:draft.getSegments()){
            segment.setPrompt("Continuous action from <Video 1>");var video=new ShotRefVideoDTO();video.setId("slice-"+segment.getId());video.setSourceType("DIRECT_URL");video.setSourceVideoUrl("https://archive.test/source.mp4");
            video.setStartSeconds(BigDecimal.valueOf(segment.getStartFrame()).divide(BigDecimal.valueOf(24),12,java.math.RoundingMode.HALF_UP));
            video.setEndSeconds(BigDecimal.valueOf(Math.min(segment.getEndFrame(),720)).divide(BigDecimal.valueOf(24),12,java.math.RoundingMode.HALF_UP));segment.getReferenceVideos().add(video);
        }
        var model=model("TXT2VIDEO_REF");model.setMaxVideos(1);
        var preview=service.preview(draft,false,model,provider());
        assertEquals(7,preview.getSegmentCount());assertTrue(preview.isRenderable(),preview.getIssues().toString());
        assertTrue(preview.getIssues().isEmpty());
    }
    @Test void referenceAdapterRevisionInvalidatesPreparedPlanWithoutChangingH3Revision() throws Exception {
        var service=service();var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(5);
        new ShotContinuationPlanner().plan(draft,true);draft.getSegments().forEach(s->s.setPrompt("Continuous action"));
        var video=new ShotRefVideoDTO();video.setId("v1");video.setSourceType("DIRECT_URL");video.setSourceVideoUrl("https://archive.test/reference.mp4");
        draft.getSegments().getFirst().getReferenceVideos().add(video);
        var cap=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("{\"healthy\":true,\"nodeId\":\"gpu\",\"workflowRevision\":\"video-v1\",\"referenceVideo\":{\"adapter\":\"MINIMAX_H3_REF_VIDEO_V1\",\"maxVideos\":1},\"h3\":{\"revision\":\"h3-v1\",\"continuation\":true}}");
        doReturn(cap).when(service).capabilities(any(),any());var model=model("TXT2VIDEO_REF");var provider=provider();
        var before=service.preview(draft,false,model,provider);assertTrue(before.isRenderable());
        assertEquals(before.getPlanHash(),service.preview(draft,false,model,provider).getPlanHash());
        cap.put("workflowRevision","video-v2");var after=service.preview(draft,false,model,provider);
        assertEquals(before.getCapabilityRevision(),after.getCapabilityRevision());assertNotEquals(before.getPlanHash(),after.getPlanHash());
        var request=new DramaShotRenderRequestDTO();request.setExpectedRenderFingerprint(before.getPlanHash());
        assertEquals(409,assertThrows(com.astra.freyja.common.BizException.class,
                ()->service.prepare(shot(draft,"REFERENCE_MODE"),model,provider,"960x544",request)).getCode());
    }
    @Test void allSegmentsShareOnePreparationAndVersionChangesBeforePreparationAreRejected() throws Exception {
        var videos=mock(ShotVideoReferenceService.class);var batch=mock(ShotVideoReferenceService.SegmentPreparation.class);
        var option=new ShotVideoModelOptionVO();option.setWorkflowRevision("video-v1");option.setNodeId("gpu");
        when(batch.model()).thenReturn(option);when(videos.openSegmentPreparation(any())).thenReturn(batch);
        when(batch.prepare(any(),anyList())).thenAnswer(call->{var result=new VideoReferencePreflightVO();result.setModel(option);result.setReferences(List.of());return result;});
        var service=spy(new ShotContinuationService(new ShotContinuationPlanner(),videos,json,mock(CryptoUtil.class),new com.astra.freyja.config.ReferenceVideoProperties()));
        doReturn(json.readTree("{\"healthy\":true,\"nodeId\":\"gpu\",\"workflowRevision\":\"video-v1\",\"referenceVideo\":{\"adapter\":\"MINIMAX_H3_REF_VIDEO_V1\",\"maxVideos\":1},\"h3\":{\"revision\":\"h3-v1\",\"continuation\":true}}"))
                .when(service).capabilities(any(),any());
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(30);new ShotContinuationPlanner().plan(draft,true);
        for(var segment:draft.getSegments()){segment.setPrompt("Continuous action");var video=new ShotRefVideoDTO();video.setId(segment.getId());video.setSourceType("DIRECT_URL");video.setSourceVideoUrl("https://archive.test/reference.mp4");segment.getReferenceVideos().add(video);}
        var progress=new ArrayList<Integer>();var shot=shot(draft,"REFERENCE_MODE");var model=model("TXT2VIDEO_REF");
        var snapshot=service.prepare(shot,model,provider(),"960x544",null,(done,total)->{assertEquals(7,total);progress.add(done);});
        assertEquals(List.of(0,1,2,3,4,5,6,7),progress);assertEquals(7,snapshot.getRequest().getContinuationSegments().size());
        verify(videos,times(1)).openSegmentPreparation(model);verify(batch,times(7)).prepare(any(),anyList());verify(batch).close();
        option.setWorkflowRevision("video-v2");
        assertEquals(409,assertThrows(com.astra.freyja.common.BizException.class,()->service.prepare(shot,model,provider(),"960x544",null)).getCode());
        verify(batch,times(7)).prepare(any(),anyList());verify(batch,times(2)).close();
    }
    private DramaShot shot(ShotContinuation draft,String mode) throws Exception {
        var shot=new DramaShot();shot.setId(1L);shot.setDramaId(2L);shot.setGenerationMode(mode);shot.setSeed(123L);shot.setDuration(BigDecimal.valueOf(draft.getDurationSeconds()));
        shot.setContinuationJson(json.writeValueAsString(draft));shot.setPreviewImageUrl("https://archive/global-first.png");shot.setEndFrameImageUrl("https://archive/global-last.png");
        shot.setRefImagesJson("[{\"id\":\"global\",\"imageUrl\":\"https://archive/global.png\"}]");shot.setRefAudiosJson("[{\"id\":\"global-audio\"}]");return shot;
    }
    @Test void ordinaryControlOptionsUseUnion2TypesAndRespectInstalledPreprocessors() throws Exception {
        var service=service();
        doReturn(json.readTree("{\"healthy\":true,\"h3\":{\"status\":\"VERIFIED\",\"controlNet\":true,\"controlTypes\":[\"HED\",\"MLSD\",\"SCRIBBLE\",\"LAYOUT\",\"GRAY\"]}}"))
                .when(service).capabilities(any(),any());
        var shot=new DramaShot();shot.setDuration(BigDecimal.valueOf(5));
        var control=new ShotControlNetOptions();control.setEnabled(true);control.setReferenceId("v1");
        for(String kind:List.of("HED","MLSD","SCRIBBLE","LAYOUT","GRAY")){
            control.setControlType(kind);var options=service.controlOptions(shot,model("TXT2VIDEO_REF"),provider(),control);
            assertEquals(kind,((Map<?,?>)options.get("control_net")).get("control_type"));
        }
        control.setControlType("DEPTH");assertThrows(com.astra.freyja.common.BizException.class,()->service.controlOptions(shot,model("TXT2VIDEO_REF"),provider(),control));
    }
    private AiModel model(String type){var model=new AiModel();model.setId(3L);model.setModelCode("h3");model.setModelType(type);return model;}
    @Test void ordinaryMultiControlPreservesTypesAndSkipsExtractorRequirementForPreparedInputs()throws Exception{
        var service=service();
        doReturn(json.readTree("{\"healthy\":true,\"h3\":{\"controlNet\":true,\"controlTypes\":[\"PREPARED\"]}}"))
                .when(service).capabilities(any(),any());
        var shot=new DramaShot();shot.setDuration(BigDecimal.valueOf(5));
        var pose=new ShotControlNetOptions.Input();pose.setReferenceId("pose");pose.setControlType("POSE");pose.setInputMode("PREPROCESSED");pose.setStrength(.8);
        var depth=new ShotControlNetOptions.Input();depth.setReferenceId("depth");depth.setControlType("DEPTH");depth.setInputMode("PREPROCESSED");depth.setStrength(.4);
        var control=new ShotControlNetOptions();control.setEnabled(true);control.setControls(List.of(pose,depth));
        var result=json.valueToTree(service.controlOptions(shot,model("TXT2VIDEO_REF"),provider(),control)).path("control_net").path("controls");
        assertEquals(2,result.size());assertEquals("pose",result.get(0).path("reference_id").asText());
        assertEquals("DEPTH",result.get(1).path("control_type").asText());assertEquals(.4,result.get(1).path("strength").asDouble());
        assertEquals("PREPROCESSED",result.get(1).path("input_mode").asText());
        depth.setInputMode("RAW");assertThrows(com.astra.freyja.common.BizException.class,()->service.controlOptions(shot,model("TXT2VIDEO_REF"),provider(),control));
        depth.setEnabled(false);assertDoesNotThrow(()->service.controlOptions(shot,model("TXT2VIDEO_REF"),provider(),control));
    }
    @Test void preparedContinuationSnapshotsRetainEveryIndependentControlAndValidateEachDuration()throws Exception{
        var videos=mock(ShotVideoReferenceService.class);var media=mock(ShotVideoReferenceService.SegmentPreparation.class);
        var option=new ShotVideoModelOptionVO();option.setWorkflowRevision("video-v1");option.setNodeId("gpu");
        when(media.model()).thenReturn(option);when(videos.openSegmentPreparation(any())).thenReturn(media);
        when(media.prepare(any(),anyList())).thenAnswer(call->{var prepared=new VideoReferencePreflightVO();prepared.setModel(option);prepared.setReferences(List.of());return prepared;});
        var limits=new com.astra.freyja.config.ReferenceVideoProperties();limits.setMaxClipSeconds(20);
        var service=spy(new ShotContinuationService(new ShotContinuationPlanner(),videos,json,mock(CryptoUtil.class),limits));
        doReturn(json.readTree("{\"healthy\":true,\"nodeId\":\"gpu\",\"workflowRevision\":\"video-v1\",\"referenceVideo\":{\"adapter\":\"MINIMAX_H3_REF_VIDEO_V1\",\"maxVideos\":3,\"maxTotalClipSeconds\":20},\"h3\":{\"revision\":\"h3-v1\",\"continuation\":true,\"controlNet\":true,\"controlTypes\":[\"PREPARED\"]}}"))
                .when(service).capabilities(any(),any());
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(5);new ShotContinuationPlanner().plan(draft,true);
        var segment=draft.getSegments().getFirst();segment.setPrompt("Follow the supplied motion");segment.setControlNetEnabled(true);
        var inputs=new ArrayList<ShotControlNetOptions.Input>();
        for(String kind:List.of("POSE","DEPTH")){
            var video=new ShotRefVideoDTO();video.setId(kind);video.setSourceType("DIRECT_URL");video.setSourceVideoUrl("https://archive.test/"+kind+".mp4");video.setEndSeconds(BigDecimal.valueOf(5));segment.getReferenceVideos().add(video);
            var input=new ShotControlNetOptions.Input();input.setReferenceId(kind);input.setControlType(kind);input.setInputMode("PREPROCESSED");inputs.add(input);
        }
        segment.setControlInputs(inputs);
        var result=service.prepare(shot(draft,"REFERENCE_MODE"),model("TXT2VIDEO_REF"),provider(),"960x544",null);
        var controls=json.valueToTree(result.getRequest().getContinuationSegments().getFirst().getRequest().getH3Options()).path("control_net").path("controls");
        assertEquals(2,controls.size());assertEquals("POSE",controls.get(0).path("reference_id").asText());assertEquals("DEPTH",controls.get(1).path("reference_id").asText());
        assertEquals("PREPROCESSED",controls.get(1).path("input_mode").asText());verify(media).prepare(any(),argThat(refs->refs.size()==2));
        segment.getReferenceVideos().get(1).setEndSeconds(BigDecimal.ONE);
        var rejected=service.preview(draft,false,model("TXT2VIDEO_REF"),provider());assertFalse(rejected.isRenderable());
        assertTrue(rejected.getIssues().stream().anyMatch(issue->issue.contains("未完整覆盖")));
    }
    @Test void oldDraftWithRemovedLockOptionCanBeReadAndSavedAsNativeGuide() throws Exception {
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(5);new ShotContinuationPlanner().plan(draft,true);
        var shot=shot(draft,"REFERENCE_MODE");var legacy=json.readTree(shot.getContinuationJson());((com.fasterxml.jackson.databind.node.ObjectNode)legacy).put("lockContext",true);shot.setContinuationJson(legacy.toString());
        var current=service().read(shot);assertTrue(current.isEnabled());assertEquals(draft.getSegments().getFirst().getId(),current.getSegments().getFirst().getId());
        assertFalse(json.readTree(json.writeValueAsString(current)).has("lockContext"));
    }
    @Test void nativeWindowControlNeedsItsOwnAcceptanceButControlAloneDoesNot() throws Exception {
        var service=service();
        doReturn(json.readTree("{\"healthy\":true,\"referenceVideo\":{\"adapter\":\"MINIMAX_H3_REF_VIDEO_V1\",\"status\":\"VERIFIED\"},\"h3\":{\"status\":\"VERIFIED\",\"continuation\":true,\"contextWindows\":true,\"controlNet\":true,\"contextWindowsWithControlNet\":false}}"))
                .when(service).capabilities(any(),any());
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(5);new ShotContinuationPlanner().plan(draft,true);
        var segment=draft.getSegments().getFirst();segment.setPrompt("Continue walking");segment.setControlNetEnabled(true);
        var video=new ShotRefVideoDTO();video.setId("v");video.setSourceType("DIRECT_URL");video.setSourceVideoUrl("https://archive.test/clip.mp4");segment.getReferenceVideos().add(video);
        assertTrue(service.preview(draft,false,model("TXT2VIDEO_REF"),provider()).isRenderable());
        draft.setContextWindows(true);var preview=service.preview(draft,false,model("TXT2VIDEO_REF"),provider());
        assertFalse(preview.isRenderable());assertTrue(preview.getIssues().stream().anyMatch(issue->issue.contains("官方上下文窗口与 ControlNet 联用尚未验收")));
    }
    private AiProvider provider(){var provider=new AiProvider();provider.setId(4L);provider.setBaseUrl("http://gateway/v1");return provider;}
}
