package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.service.video.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShotContinuationPlannerTest {
    private final ShotContinuationPlanner planner=new ShotContinuationPlanner();
    private ShotContinuation draft(double seconds){var d=new ShotContinuation();d.setEnabled(true);d.setDurationSeconds(seconds);d.setSegmentFrames(362);d.setOverlapFrames(39);return d;}
    @Test void eachPresetLimitsEveryAutomaticSegmentAndPreservesExactOutputCoverage(){
        for(var preset:planner.presets())for(double duration:new double[]{3,15,30,60,300}){
            var d=new ShotContinuation();d.setEnabled(true);d.setDurationSeconds(duration);d.setSegmentFrames(preset.segmentFrames());d.setOverlapFrames(preset.overlapFrames());
            var plan=planner.plan(d,true);int cursor=0;
            for(var range:plan.getRanges()){assertTrue(range.getEndFrame()-range.getStartFrame()<=preset.segmentFrames());assertEquals(cursor,range.getKeepStartFrame());cursor=range.getKeepEndFrame();}
            assertEquals((int)Math.round(duration*24),cursor);
        }
    }
    @Test void shorterPresetCannotKeepAHiddenLongManualSegment(){
        var d=draft(30);planner.plan(d,true);d.setSegmentFrames(124);d.setOverlapFrames(22);assertThrows(BizException.class,()->planner.plan(d,false));
    }
    @Test void coversOneMinuteExactlyWithoutCountingOverlapTwice(){
        var plan=planner.plan(draft(60),true);assertEquals(5,plan.getSegmentCount());assertEquals(1440,plan.getOutputFrames());assertEquals(1450,plan.getRenderFrames());
        int cursor=0;for(var range:plan.getRanges()){assertEquals(cursor,range.getKeepStartFrame());cursor=range.getKeepEndFrame();assertEquals(5,(range.getEndFrame()-range.getStartFrame())%17);}assertEquals(1440,cursor);
    }
    @Test void ordinaryModeCannotExceedFifteenSeconds(){var d=draft(16);d.setEnabled(false);assertThrows(BizException.class,()->planner.plan(d,true));}
    @Test void replanningDoesNotSilentlyDiscardMaterialsOrLocks(){
        var d=draft(40);planner.plan(d,true);var image=new ShotRefImageDTO();image.setId("reference");image.setImageUrl("https://archive.test/image.png");d.getSegments().getFirst().getReferenceImages().add(image);
        d.setSegmentFrames(243);assertThrows(BizException.class,()->planner.plan(d,true));
    }
    @Test void samePlanPreservesExplicitAttachmentsOnlyOnTheirSegment(){
        var d=draft(40);planner.plan(d,true);var image=new ShotRefImageDTO();image.setId("reference");image.setImageUrl("https://archive.test/image.png");d.getSegments().getFirst().getReferenceImages().add(image);
        planner.plan(d,true);assertEquals(1,d.getSegments().getFirst().getReferenceImages().size());assertTrue(d.getSegments().get(1).getReferenceImages().isEmpty());
    }
    @Test void controlNetNeedsAnAttachedVideoButReferencesNeedNoDescriptions(){
        var d=draft(10);planner.plan(d,true);d.getSegments().getFirst().setControlNetEnabled(true);assertThrows(BizException.class,()->planner.plan(d,false));
        var video=new ShotRefVideoDTO();video.setId("v1");video.setSourceType("DIRECT_URL");video.setSourceVideoUrl("https://archive.test/video.mp4");d.getSegments().getFirst().getReferenceVideos().add(video);
        assertDoesNotThrow(()->planner.plan(d,false));assertEquals("v1",d.getSegments().getFirst().getControlVideoId());
    }
    @Test void union2ControlTypesRemainExplicitAndUnknownTypesAreRejected(){
        var d=draft(10);planner.plan(d,true);var segment=d.getSegments().getFirst();
        var video=new ShotRefVideoDTO();video.setId("v1");video.setSourceType("DIRECT_URL");video.setSourceVideoUrl("https://archive.test/control.mp4");
        segment.getReferenceVideos().add(video);segment.setControlNetEnabled(true);
        for(String kind:ShotControlNetOptions.CONTROL_TYPES){segment.setControlType(kind);assertDoesNotThrow(()->planner.plan(d,false));}
        segment.setControlType("UNKNOWN");assertThrows(BizException.class,()->planner.plan(d,false));
        segment.setControlType(null);assertThrows(BizException.class,()->planner.plan(d,false));
    }
    @Test void independentControlInputsAreValidatedAndChangingOnlyOneInvalidatesThePlan()throws Exception{
        var d=draft(5);planner.plan(d,true);var segment=d.getSegments().getFirst();segment.setControlNetEnabled(true);
        for(String id:List.of("pose","depth")){var video=new ShotRefVideoDTO();video.setId(id);video.setSourceType("DIRECT_URL");video.setSourceVideoUrl("https://archive.test/"+id+".mp4");segment.getReferenceVideos().add(video);}
        var pose=new ShotControlNetOptions.Input();pose.setReferenceId("pose");pose.setControlType("POSE");
        var depth=new ShotControlNetOptions.Input();depth.setReferenceId("depth");depth.setControlType("DEPTH");depth.setInputMode("PREPROCESSED");
        segment.setControlInputs(List.of(pose,depth));var before=planner.plan(d,false).getPlanHash();
        depth.setStrength(.4);assertNotEquals(before,planner.plan(d,false).getPlanHash());
        depth.setReferenceId("foreign");assertThrows(BizException.class,()->planner.plan(d,false));
        depth.setReferenceId(null);depth.setEnabled(false);assertDoesNotThrow(()->planner.plan(d,false));
        segment.setControlVideoId("old-missing");segment.setControlInputs(List.of());
        assertDoesNotThrow(()->planner.plan(d,false));assertFalse(segment.controlOptions().isActive());
        var legacy=new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"enabled\":true,\"referenceId\":\"pose\",\"controlType\":\"POSE\",\"strength\":0.7}",ShotControlNetOptions.class);
        assertEquals(.7,legacy.activeInputs().getFirst().getStrength());
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(legacy).has("controls"));
    }
    @Test void replanningProtectsAuthoredTextAndDirectorEvenWithoutMaterials(){
        var d=draft(40);planner.plan(d,true);d.getSegments().getFirst().setScriptContent("本段独立剧本");d.setSegmentFrames(243);
        assertThrows(BizException.class,()->planner.plan(d,true));
        d.getSegments().getFirst().setScriptContent(null);d.getSegments().getFirst().setDirectorPlan(new com.astra.freyja.director.model.DirectorPlan());
        assertThrows(BizException.class,()->planner.plan(d,true));
    }
    @Test void segmentIdentityIdsSurviveEmbeddedJsonAndConflictsAreRejected()throws Exception{
        var d=draft(10);planner.plan(d,true);var segment=d.getSegments().getFirst();
        var c=new com.astra.freyja.dto.res.CharacterShotRefDTO();c.setCharacterId(2032095628944588801L);c.setLookId(2032095628944588802L);segment.getCharacterRefs().add(c);
        var text=com.astra.freyja.util.VideoReferenceSnapshotJson.write(d,new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals("2032095628944588801",new com.fasterxml.jackson.databind.ObjectMapper().readTree(text).path("segments").path(0).path("characterRefs").path(0).path("characterId").textValue());
        segment.getCharacterRefs().add(c);assertThrows(BizException.class,()->planner.plan(d,false));segment.getCharacterRefs().removeLast();
        segment.setResSceneId(1L);segment.setResKeyframeId(2L);assertThrows(BizException.class,()->planner.plan(d,false));
    }
    @Test void manualGapsAndDuplicateIdsAreRejected(){var d=draft(40);planner.plan(d,true);d.getSegments().get(1).setStartFrame(374);assertThrows(BizException.class,()->planner.plan(d,false));}
    @Test void shortEnabledShotStillUsesOnlyOneSegment(){assertEquals(1,planner.plan(draft(3),true).getSegmentCount());}
    @Test void embeddedContinuationJsonPreservesSnowflakeIds()throws Exception{
        var d=draft(10);planner.plan(d,true);var image=new ShotRefImageDTO();image.setId("image");image.setImageUrl("https://archive.test/image");image.setSourceId(2032095628944588801L);d.getSegments().getFirst().getReferenceImages().add(image);
        var text=com.astra.freyja.util.VideoReferenceSnapshotJson.write(d,new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals("2032095628944588801",new com.fasterxml.jackson.databind.ObjectMapper().readTree(text).path("segments").path(0).path("referenceImages").path(0).path("sourceId").textValue());
    }
    @Test void nestedArchiveCannotBecomeTheParentRecoveryResult(){
        VideoExecutionState.begin("parent");try{VideoExecutionState.current().continuationChild=true;VideoExecutionState.completed("child.mp4");assertNull(VideoExecutionState.current().recoveryUrl);VideoExecutionState.current().continuationChild=false;VideoExecutionState.completed("full.mp4");assertEquals("full.mp4",VideoExecutionState.current().recoveryUrl);}finally{VideoExecutionState.clear("parent");}
    }
}
