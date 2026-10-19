package com.astra.freyja.service;

import com.astra.freyja.dao.VoiceEffectMapper;
import com.astra.freyja.dto.voice.DubbingInputs.*;
import com.astra.freyja.entity.VoiceEffect;
import com.astra.freyja.config.MediaToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoiceEffectServiceTest {
    final VoiceEffectMapper presets=mock(VoiceEffectMapper.class);
    final VoiceEffectEngine engine=new VoiceEffectEngine(new MediaToolProperties(),mock(NativeProcessExecutor.class),mock(VideoFrameExtractService.class));
    final VoiceEffectService service=new VoiceEffectService(presets,engine,new ObjectMapper());

    @Test void recordingFreezesTypedDefaultsAndListsAndRoundTripsWithoutChangingTemplateRevision() {
        var preset=new VoiceEffect();preset.setId(1L);preset.setVersion(3);preset.setEnabled(1);preset.setName("合唱");
        preset.setStepsJson("[{\"type\":\"chorus\",\"params\":{},\"enabled\":false}]");
        when(presets.selectById(1L)).thenReturn(preset);
        var snapshots=service.snapshots(List.of(new EffectSelection(1L,null)));
        var step=snapshots.getFirst().steps().getFirst();
        assertEquals(false,step.enabled());assertEquals(List.of(50d),step.params().get("delays"));
        assertThrows(UnsupportedOperationException.class,()->step.params().put("delays",List.of(20)));
        var roundTrip=service.readSnapshots(service.write(snapshots));
        assertEquals(snapshots,roundTrip);
        clearInvocations(presets);
        var unchanged=service.snapshots(List.of(new EffectSelection(1L,List.of(new Step("chorus",Map.of(),false)))),roundTrip);
        assertEquals(3,unchanged.getFirst().version());verifyNoInteractions(presets);
    }

    @Test void unchangedNumbersKeepExistingSnapshotEvenWhenJsonUsesIntegerValues() {
        var prior=new VoiceEffectService.Snapshot("1","原模板",2,List.of(new Step("echo",Map.of("delay",200,"decay",0.3))));
        var result=service.snapshots(List.of(new EffectSelection(1L,List.of(new Step("echo",Map.of("delay",200d,"decay",0.3))))),List.of(prior));
        assertEquals(List.of(prior),result);verifyNoInteractions(presets);
    }
}
