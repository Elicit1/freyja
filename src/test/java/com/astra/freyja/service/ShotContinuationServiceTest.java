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
        var children=result.getRequest().getContinuationSegments();assertEquals(3,children.size());
        assertTrue(children.getFirst().getShot().getRefImagesJson().contains("binding"));
        assertEquals("[]",children.get(1).getShot().getRefImagesJson());
        for(var child:children){assertNull(child.getShot().getPreviewImageUrl());assertNull(child.getShot().getEndFrameImageUrl());assertEquals("[]",child.getShot().getRefAudiosJson());assertFalse((Boolean)((Map<?,?>)child.getRequest().getH3Options().get("control_net")).get("enabled"));}
    }
    @Test void firstLastWorkflowUsesOnlyPerSegmentExplicitAnchors() throws Exception {
        var draft=new ShotContinuation();draft.setEnabled(true);draft.setDurationSeconds(30);new ShotContinuationPlanner().plan(draft,true);
        draft.getSegments().forEach(s->s.setPrompt("Continue walking"));draft.getSegments().get(1).setFirstFrameImageUrl("https://archive/explicit.png");
        var result=service().prepare(shot(draft,"FIRST_LAST_FRAME"),model("TXT2VIDEO_FIRST_LAST"),provider(),"960x544",null);
        var children=result.getRequest().getContinuationSegments();assertNull(children.getFirst().getShot().getPreviewImageUrl());
        assertEquals("https://archive/explicit.png",children.get(1).getShot().getPreviewImageUrl());assertNull(children.getLast().getShot().getEndFrameImageUrl());
    }
    private DramaShot shot(ShotContinuation draft,String mode) throws Exception {
        var shot=new DramaShot();shot.setId(1L);shot.setDramaId(2L);shot.setGenerationMode(mode);shot.setSeed(123L);shot.setDuration(BigDecimal.valueOf(draft.getDurationSeconds()));
        shot.setContinuationJson(json.writeValueAsString(draft));shot.setPreviewImageUrl("https://archive/global-first.png");shot.setEndFrameImageUrl("https://archive/global-last.png");
        shot.setRefImagesJson("[{\"id\":\"global\",\"imageUrl\":\"https://archive/global.png\"}]");shot.setRefAudiosJson("[{\"id\":\"global-audio\"}]");return shot;
    }
    private AiModel model(String type){var model=new AiModel();model.setId(3L);model.setModelCode("h3");model.setModelType(type);return model;}
    private AiProvider provider(){var provider=new AiProvider();provider.setId(4L);provider.setBaseUrl("http://gateway/v1");return provider;}
}
