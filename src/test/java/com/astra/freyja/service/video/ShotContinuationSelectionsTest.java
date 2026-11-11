package com.astra.freyja.service.video;

import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.astra.freyja.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ShotContinuationSelectionsTest {
    final DictService dictionaries=mock(DictService.class);final ResCharacterMapper characters=mock(ResCharacterMapper.class);final ResCharacterLookMapper looks=mock(ResCharacterLookMapper.class);
    final ShotContinuationSelections selections=new ShotContinuationSelections(dictionaries,characters,looks,mock(ResSceneMapper.class),mock(ResPropMapper.class),mock(ResKeyframeMapper.class),mock(DubbingService.class),mock(ResAudioService.class),mock(ShotVideoReferenceService.class));
    DramaShot shot(){var s=new DramaShot();s.setId(1L);s.setDramaId(2L);return s;}
    ShotContinuation draft(){var d=new ShotContinuation();d.setEnabled(true);d.setDurationSeconds(5);new ShotContinuationPlanner().plan(d,true);return d;}
    @Test void sceneAndKeyframeCannotBothBeSaved(){
        var d=draft();var segment=d.getSegments().getFirst();segment.setResSceneId(2032095628944588801L);segment.setResKeyframeId(2032095628944588802L);
        assertTrue(assertThrows(BizException.class,()->selections.validate(shot(),d)).getMessage().contains("只能选择一个"));
    }
    @Test void disabledDictionaryAndWrongCharacterLookCannotBeSaved(){
        var d=draft();var segment=d.getSegments().getFirst();segment.setShotType("CLOSE_UP");assertThrows(BizException.class,()->selections.validate(shot(),d));segment.setShotType(null);
        var c=new ResCharacter();c.setId(3L);c.setDramaId(2L);c.setStatus(1);when(characters.selectById(3L)).thenReturn(c);
        var other=new ResCharacter();other.setId(4L);other.setDramaId(2L);other.setStatus(1);when(characters.selectById(4L)).thenReturn(other);
        var look=new ResCharacterLook();look.setId(5L);look.setCharacterId(4L);look.setStatus(1);when(looks.selectById(5L)).thenReturn(look);
        var ref=new com.astra.freyja.dto.res.CharacterShotRefDTO();ref.setCharacterId(3L);ref.setLookId(5L);segment.getCharacterRefs().add(ref);
        assertThrows(BizException.class,()->selections.validate(shot(),d));
        look.setCharacterId(3L);assertDoesNotThrow(()->selections.validate(shot(),d));c.setStatus(0);assertThrows(BizException.class,()->selections.validate(shot(),d));
    }
    @Test void danglingControlVideoAndUnknownImageUsageAreRejected(){var d=draft();var segment=d.getSegments().getFirst();segment.setControlVideoId("gone");assertThrows(BizException.class,()->selections.validate(shot(),d));segment.setControlVideoId(null);var image=new ShotRefImageDTO();image.setId("i");image.setSourceType("UPLOAD");image.setImageUrl("https://archive.test/a.png");image.setUsageRole("invented");segment.getReferenceImages().add(image);assertThrows(BizException.class,()->selections.validate(shot(),d));image.setUsageRole("STYLE");assertDoesNotThrow(()->selections.validate(shot(),d));}
    @Test void modelSizeAndAspectDropdownRestrictionsApplyToMcpRequests(){
        var app=new ShotContinuationApplication(null,null,null,null,null,null,null,null,null,null,new ObjectMapper(),selections);var m=new AiModel();m.setParamsJson("{\"videoGeneration\":{\"supportedSizes\":[\"960x544\",\"544x960\"],\"supportedAspectRatios\":[\"16:9\"]}}");
        assertDoesNotThrow(()->app.validateSize(m,"960x544"));assertThrows(BizException.class,()->app.validateSize(m,"544x960"));assertThrows(BizException.class,()->app.validateSize(m,"1280x736"));
    }
    @Test void imageSourceLookMustMatchExplicitCharacterAndLookChoices(){
        var d=draft();var image=new ShotRefImageDTO();image.setSourceType("CHARACTER_REFERENCE");image.setUsageRole("SUBJECT");image.setSourceId(5L);image.setCharacterId(3L);
        d.getSegments().getFirst().getReferenceImages().add(image);
        var character=new ResCharacter();character.setId(3L);character.setDramaId(2L);character.setStatus(1);when(characters.selectById(3L)).thenReturn(character);
        var look=new ResCharacterLook();look.setId(5L);look.setCharacterId(3L);look.setStatus(1);when(looks.selectById(5L)).thenReturn(look);
        assertDoesNotThrow(()->selections.validate(shot(),d));
        image.setCharacterId(4L);assertThrows(BizException.class,()->selections.validate(shot(),d));
        image.setCharacterId(3L);image.setLookId(6L);assertThrows(BizException.class,()->selections.validate(shot(),d));
    }
    @Test void disablingContinuationRetainsInactiveSelectionsButChecksActiveOrdinaryControl(){
        var d=draft();d.getSegments().getFirst().setControlVideoId("deleted");d.setEnabled(false);
        assertDoesNotThrow(()->selections.validate(shot(),d));
        d.getControlNet().setEnabled(true);d.getControlNet().setReferenceId("deleted");
        assertThrows(BizException.class,()->selections.validate(shot(),d));
        d.getControlNet().setEnabled(false);d.getControlNet().setReferenceId(null);d.setEnabled(true);
        d.getSegments().getFirst().setControlVideoId(null);d.getSegments().getFirst().getReferenceAudios().add(null);
        assertThrows(BizException.class,()->selections.validate(shot(),d));
    }
    @Test void stoppedProviderAndCrossModeModelAreRejectedBeforeNetworkCalls(){
        var providers=mock(AiProviderMapper.class);var models=mock(AiModelMapper.class);var p=new AiProvider();p.setStatus(0);when(providers.selectById(10L)).thenReturn(p);
        var service=mock(ShotContinuationService.class);var app=new ShotContinuationApplication(mock(DramaShotMapper.class),models,providers,service,new ShotContinuationPlanner(),null,null,null,null,null,new ObjectMapper(),selections);
        assertThrows(BizException.class,()->app.model(10L,"h3","TXT2VIDEO_REF"));
        p.setStatus(1);var model=new AiModel();model.setProviderId(10L);model.setModelCode("h3");model.setStatus(1);model.setModelType("TXT2VIDEO_FIRST_LAST");when(models.selectOne(any())).thenReturn(model);
        assertThrows(BizException.class,()->app.model(10L,"h3","TXT2VIDEO_REF"));verifyNoInteractions(service);
    }
}
