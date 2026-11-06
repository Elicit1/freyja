package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShotContinuationAiServiceTest {
    private final ShotContinuationAiService service=new ShotContinuationAiService(null,null,null,null,null,null,null);
    private ShotContinuation draft(){var d=new ShotContinuation();d.setEnabled(true);d.setDurationSeconds(15);new ShotContinuationPlanner().plan(d,true);return d;}
    private ShotContinuationAiService.SegmentPrompt result(String id){return new ShotContinuationAiService.SegmentPrompt(id,"new video","新名称","新剧本",null,null,null,null);}
    @Test void onlySelectedUnlockedTextChangesAndMaterialBindingsSurvive(){
        var d=draft();var a=d.getSegments().getFirst();var b=d.getSegments().get(1);var c=d.getSegments().get(2);b.setPrompt("unchanged");c.setPrompt("locked");c.setLocked(true);
        var image=new ShotRefImageDTO();image.setId("img");image.setImageUrl("https://archive.test/i.png");a.getReferenceImages().add(image);
        service.applyResult(d,new ShotContinuationAiService.Prompts(List.of(result(a.getId()))),Set.of(a.getId()));
        assertEquals("新剧本",a.getScriptContent());assertSame(image,a.getReferenceImages().getFirst());assertEquals("unchanged",b.getPrompt());assertEquals("locked",c.getPrompt());
    }
    @Test void unselectedDuplicateAndMissingResultIdsAreRejected(){
        var d=draft();var id=d.getSegments().getFirst().getId();var selected=Set.of(id);
        assertThrows(BizException.class,()->service.applyResult(d,new ShotContinuationAiService.Prompts(List.of(result("other"))),selected));
        assertThrows(BizException.class,()->service.applyResult(d,new ShotContinuationAiService.Prompts(List.of(result(id),result(id))),selected));
        assertThrows(BizException.class,()->service.applyResult(d,new ShotContinuationAiService.Prompts(List.of()),selected));
        assertNull(d.getSegments().getFirst().getPrompt());
    }
}
