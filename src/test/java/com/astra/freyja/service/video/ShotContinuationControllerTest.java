package com.astra.freyja.service.video;

import com.astra.freyja.controller.ShotContinuationController;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.ShotContinuation;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.service.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ShotContinuationControllerTest {
    @Test void editingPlanDoesNotContactRenderProvider(){
        var shots=mock(DramaShotMapper.class);var models=mock(AiModelMapper.class);var providers=mock(AiProviderMapper.class);var service=mock(ShotContinuationService.class);
        var shot=new DramaShot();shot.setId(1L);when(shots.selectById(1L)).thenReturn(shot);
        var controller=new ShotContinuationController(shots,models,providers,service,new ShotContinuationPlanner(),null,null,null,null,null,new ObjectMapper());
        var d=new ShotContinuation();d.setEnabled(true);d.setDurationSeconds(30);var request=new ShotContinuationController.PreviewRequest();request.setDraft(d);request.setAuto(true);
        controller.plan(1L,request);assertEquals(7,d.getSegments().size());verifyNoInteractions(models,providers,service);
    }
    @Test void disabledSaveRetainsLongDraftAndCapsOrdinaryShot()throws Exception{
        var shots=mock(DramaShotMapper.class);var current=new DramaShot();current.setId(1L);current.setDuration(BigDecimal.valueOf(60));when(shots.selectById(1L)).thenReturn(current);
        when(shots.update(isNull(),any(Wrapper.class))).thenReturn(1);
        var controller=new ShotContinuationController(shots,null,null,null,new ShotContinuationPlanner(),null,null,null,null,null,new ObjectMapper());
        var d=new ShotContinuation();d.setDurationSeconds(60);controller.save(1L,d);
        var captor=org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);verify(shots).update(isNull(),captor.capture());
        var update=captor.getValue();assertTrue(update.getSqlSet().contains("duration"));assertTrue(update.getParamNameValuePairs().containsValue(15));assertTrue(update.getParamNameValuePairs().values().stream().anyMatch(v->v instanceof String str&&str.contains("\"durationSeconds\":60.0")));
    }
}
