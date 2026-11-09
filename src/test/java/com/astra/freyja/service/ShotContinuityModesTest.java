package com.astra.freyja.service;

import com.astra.freyja.dto.drama.*;
import com.astra.freyja.service.video.ShotContinuationPlanner;
import com.astra.freyja.common.BizException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShotContinuityModesTest {
    private final ShotContinuationPlanner planner=new ShotContinuationPlanner();
    private ShotContinuation draft(){var d=new ShotContinuation();d.setEnabled(true);d.setDurationSeconds(15);d.setSchemaVersion(2);return d;}
    @Test void mixedStrategiesInheritAndSavePredecessorsAndAnchors(){
        var d=draft();planner.plan(d,true);
        var raw=new ShotContinuityOptions();raw.setMode(ShotContinuityOptions.Mode.LATENT_PROTECTED);
        d.getSegments().get(1).setContinuityOverride(raw);
        var plan=planner.plan(d,false);
        assertEquals(ShotContinuityOptions.Mode.GUIDE,plan.getRanges().getFirst().getEffectiveContinuity().getMode());
        assertEquals("AV_LATENT",plan.getRanges().get(1).getContextRequirement());
        assertEquals(d.getSegments().getFirst().getId(),plan.getRanges().get(1).getAnchorSourceSegmentId());
        assertTrue(plan.getRanges().stream().allMatch(ShotContinuationPlan.Range::isSaveLatent));
    }
    @Test void unknownOrFutureAnchorCannotAffectEarlierSegment(){
        var d=draft();planner.plan(d,true);var mode=new ShotContinuityOptions();mode.setMode(ShotContinuityOptions.Mode.LATENT_PROTECTED);
        var p=new ShotContinuityOptions.Protection();p.setAnchorSourceSegmentId(d.getSegments().getLast().getId());mode.setProtection(p);
        d.getSegments().get(1).setContinuityOverride(mode);assertThrows(BizException.class,()->planner.plan(d,false));
    }
    @Test void strategyEditsInvalidateFingerprintAndDoNotChangeTimeline(){
        var d=draft();var before=planner.plan(d,true);String hash=before.getPlanHash();int start=d.getSegments().get(1).getStartFrame();
        d.getContinuity().setMode(ShotContinuityOptions.Mode.GUIDE_CHROMA);var next=planner.plan(d,false);
        assertNotEquals(hash,next.getPlanHash());assertEquals(start,d.getSegments().get(1).getStartFrame());assertFalse(next.getRanges().getFirst().isSaveLatent());
    }
    @Test void noiseMustBeFiniteAndTaperFitTheOverlap(){
        var mode=new ShotContinuityOptions();mode.setMode(ShotContinuityOptions.Mode.GUIDE_CHROMA);var c=new ShotContinuityOptions.Chroma();mode.setChroma(c);
        c.setStrength(Double.NaN);assertThrows(BizException.class,()->mode.resolved(22));c.setStrength(.45);c.setTaperFrames(23);assertThrows(BizException.class,()->mode.resolved(22));
    }
}
