package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.service.video.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShotContinuationReferenceSplitterTest {
    private final ShotContinuationPlanner planner=new ShotContinuationPlanner();
    private final ShotContinuationReferenceSplitter splitter=new ShotContinuationReferenceSplitter(planner,new ObjectMapper());
    private ShotContinuation draft(){var value=new ShotContinuation();value.setEnabled(true);value.setDurationSeconds(30);planner.plan(value,true);return value;}
    private ShotRefVideoDTO source(){var value=new ShotRefVideoDTO();value.setId("long-source");value.setSourceType("DIRECT_URL");value.setSourceVideoUrl("https://archive/source.mp4");value.setStartSeconds(new BigDecimal("2"));value.setEndSeconds(new BigDecimal("32"));return value;}

    @Test void mapsRealSharedSourceFramesAndDoesNotTurnControlNetOn(){
        var draft=draft();var plan=splitter.split(draft,source(),new BigDecimal("60"),null);
        int previousEnd=0;
        for(int i=0;i<plan.getRanges().size();i++){
            var range=plan.getRanges().get(i);var segment=plan.getDraft().getSegments().get(i);var video=segment.getReferenceVideos().getFirst();
            assertEquals(48+range.getStartFrame(),video.getSourceStartFrame());assertEquals(48+Math.min(range.getEndFrame(),720),video.getSourceEndFrame());
            if(i>0)assertEquals(range.getOverlapFrames(),previousEnd-video.getSourceStartFrame());
            previousEnd=video.getSourceEndFrame();assertFalse(segment.isControlNetEnabled());assertTrue(segment.getReferenceImages().isEmpty());
        }
        assertEquals(768,previousEnd);assertEquals(1,plan.getDraft().getVideoSources().size());
        assertTrue(draft.getSegments().stream().allMatch(s->s.getReferenceVideos().isEmpty()));
    }
    @Test void repeatedSplitReplacesOnlyItsOwnBindingAndKeepsSelectedControlSource(){
        var first=splitter.split(draft(),source(),new BigDecimal("60"),null);
        var segment=first.getDraft().getSegments().getFirst();String id=segment.getReferenceVideos().getFirst().getId();
        segment.setControlNetEnabled(true);segment.setControlVideoId(id);
        var second=splitter.split(first.getDraft(),source(),new BigDecimal("60"),null);
        assertEquals(1,second.getDraft().getSegments().getFirst().getReferenceVideos().size());assertEquals(id,second.getDraft().getSegments().getFirst().getControlVideoId());
    }
    @Test void selectedTargetsDoNotAttachVideoToOtherSegments(){
        var draft=draft();String selected=draft.getSegments().get(1).getId();
        var result=splitter.split(draft,source(),new BigDecimal("60"),List.of(selected));
        assertTrue(result.getDraft().getSegments().getFirst().getReferenceVideos().isEmpty());assertEquals(1,result.getDraft().getSegments().get(1).getReferenceVideos().size());
    }
    @Test void insufficientSourceFailsAtomicallyWithoutLoopingOrPartialBindings(){
        var draft=draft();var source=source();source.setEndSeconds(new BigDecimal("12"));
        assertThrows(BizException.class,()->splitter.split(draft,source,new BigDecimal("60"),null));
        assertTrue(draft.getSegments().stream().allMatch(s->s.getReferenceVideos().isEmpty()));
    }
    @Test void videoSourcesInTrayAreNotAutomaticallyInheritedByNewSegments(){
        var draft=draft();draft.getVideoSources().add(source());planner.plan(draft,true);
        assertTrue(draft.getSegments().stream().allMatch(s->s.getReferenceVideos().isEmpty()));
    }
}
