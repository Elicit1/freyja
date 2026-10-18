package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MediaToolProperties;
import com.astra.freyja.dto.voice.DubbingInputs.Step;
import org.junit.jupiter.api.*;
import java.io.*;
import java.util.*;
import javax.sound.sampled.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoiceEffectEngineTest {
    private final MediaToolProperties properties=new MediaToolProperties();
    @Test void rejectsUnknownFiltersUnknownParametersAndNonFiniteValuesBeforeExecution() {
        var processes=mock(NativeProcessExecutor.class);
        var engine=new VoiceEffectEngine(properties,processes,mock(VideoFrameExtractService.class));
        assertThrows(BizException.class,()->engine.graph(List.of(new Step(";amovie=http://host",Map.of()))));
        assertThrows(BizException.class,()->engine.graph(List.of(new Step("pitch",Map.of("semitones",Double.NaN)))));
        assertThrows(BizException.class,()->engine.graph(List.of(new Step("pitch",Map.of("semitones",13d)))));
        assertThrows(BizException.class,()->engine.graph(List.of(new Step("echo",Map.of("delay",100d,"decay",0.3,"command",1d)))));
        verifyNoInteractions(processes);
    }
    @Test void rendersEverySupportedEffectWithNativeFfmpegAndMeasuresTheResult() throws Exception {
        var processes=new NativeProcessExecutor();
        var engine=new VoiceEffectEngine(properties,processes,new VideoFrameExtractService(properties,processes));
        Assumptions.assumeTrue(engine.capabilities().stream().anyMatch(c -> Boolean.TRUE.equals(c.get("available"))),"FFmpeg unavailable");
        byte[] source=sine();
        var normalized=engine.process(source,List.of());
        assertEquals(1.5,normalized.duration(),0.05);
        for(var definition:VoiceEffectEngine.FILTERS) {
            var capability=engine.capabilities().stream().filter(c -> definition.type().equals(c.get("type"))).findFirst().orElseThrow();
            if(!Boolean.TRUE.equals(capability.get("available"))) continue;
            Map<String,Double> parameters=new LinkedHashMap<>();
            definition.parameters().forEach(p -> parameters.put(p.key(),p.initial()));
            var result=engine.process(source,List.of(new Step(definition.type(),parameters)));
            assertTrue(result.duration()>1 && result.duration()<4,definition.type());
            assertTrue(result.bytes().length>1000,definition.type());
            assertEquals('R',result.bytes()[0],definition.type());
        }
    }
    static byte[] sine() throws Exception {
        int rate=24000,frames=36000;byte[] pcm=new byte[frames*2];
        for(int i=0;i<frames;i++) {short sample=(short)(Math.sin(2*Math.PI*220*i/rate)*8000);pcm[i*2]=(byte)sample;pcm[i*2+1]=(byte)(sample>>8);}
        try(var output=new ByteArrayOutputStream();var input=new AudioInputStream(new ByteArrayInputStream(pcm),new AudioFormat(rate,16,1,true,false),frames)) {
            AudioSystem.write(input,AudioFileFormat.Type.WAVE,output);return output.toByteArray();
        }
    }
}
