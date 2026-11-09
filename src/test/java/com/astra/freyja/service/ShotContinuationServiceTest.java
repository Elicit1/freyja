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
    @Test void globalAcceptanceErrorsAreReportedOnceAndZeroModelLimitIdentifiesEveryAffectedSegment()throws Exception{
        var service=service();
        doReturn(json.readTree("{\"healthy\":true,\"referenceVideo\":{\"status\":\"UNVERIFIED\",\"maxVideos\":3},\"h3\":{\"status\":\"UNVERIFIED\",\"continuation\":true}}"))
                .when(service).capabilities(any(),any());
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(30);new ShotContinuationPlanner().plan(draft,true);
        for(var segment:draft.getSegments()){
            segment.setPrompt("Continuous action");var video=new ShotRefVideoDTO();video.setId("video-"+segment.getId());video.setSourceType("DIRECT_URL");video.setSourceVideoUrl("https://archive.test/reference.mp4");segment.getReferenceVideos().add(video);
        }
        var model=model("TXT2VIDEO_REF");model.setMaxVideos(0);
        var preview=service.preview(draft,false,model,provider());
        assertFalse(preview.isRenderable());assertEquals(3,preview.getIssues().size());
        assertEquals(1,preview.getIssues().stream().filter(i->i.equals("此工作流的参考视频能力尚未验收")).count());
        assertTrue(preview.getIssues().getLast().contains("片段 1、2、3、4、5、6、7"));
        assertTrue(preview.getIssues().getLast().contains("模型中心：0，网关：3"));
        model.setMaxVideos(1);var corrected=service.preview(draft,false,model,provider());
        assertEquals(2,corrected.getIssues().size());assertFalse(corrected.isRenderable());
    }
    @Test void verifiedReferenceCountLimitStillBlocksOnlyAffectedSegments()throws Exception{
        var service=service();
        doReturn(json.readTree("{\"healthy\":true,\"referenceVideo\":{\"status\":\"VERIFIED\",\"maxVideos\":1},\"h3\":{\"status\":\"VERIFIED\",\"continuation\":true}}"))
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
        doReturn(json.readTree("{\"healthy\":true,\"referenceVideo\":{\"status\":\"VERIFIED\",\"maxVideos\":1,\"maxClipSeconds\":6,\"maxTotalClipSeconds\":6,\"maxDecodedFrames\":144},\"h3\":{\"status\":\"VERIFIED\",\"continuation\":true}}"))
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
    @Test void oldDraftWithRemovedLockOptionCanBeReadAndSavedAsNativeGuide() throws Exception {
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(5);new ShotContinuationPlanner().plan(draft,true);
        var shot=shot(draft,"REFERENCE_MODE");var legacy=json.readTree(shot.getContinuationJson());((com.fasterxml.jackson.databind.node.ObjectNode)legacy).put("lockContext",true);shot.setContinuationJson(legacy.toString());
        var current=service().read(shot);assertTrue(current.isEnabled());assertEquals(draft.getSegments().getFirst().getId(),current.getSegments().getFirst().getId());
        assertFalse(json.readTree(json.writeValueAsString(current)).has("lockContext"));
    }
    @Test void nativeWindowControlNeedsItsOwnAcceptanceButControlAloneDoesNot() throws Exception {
        var service=service();
        doReturn(json.readTree("{\"healthy\":true,\"referenceVideo\":{\"status\":\"VERIFIED\"},\"h3\":{\"status\":\"VERIFIED\",\"continuation\":true,\"contextWindows\":true,\"controlNet\":true,\"contextWindowsWithControlNet\":false}}"))
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
