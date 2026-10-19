package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MediaToolProperties;
import com.astra.freyja.dto.voice.DubbingInputs.Step;
import org.junit.jupiter.api.*;
import java.io.*;
import java.util.*;
import javax.sound.sampled.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoiceEffectEngineTest {
    private final MediaToolProperties properties=mediaTools();
    private static MediaToolProperties mediaTools() {
        var value=new MediaToolProperties();
        value.setFfmpegPath(System.getProperty("freyja.test.ffmpeg","ffmpeg"));
        value.setFfprobePath(System.getProperty("freyja.test.ffprobe","ffprobe"));
        return value;
    }
    @Test void rejectsUnknownFiltersUnknownParametersAndNonFiniteValuesBeforeExecution() {
        var processes=mock(NativeProcessExecutor.class);
        var engine=new VoiceEffectEngine(properties,processes,mock(VideoFrameExtractService.class));
        assertThrows(BizException.class,()->engine.graph(List.of(new Step(";amovie=http://host",Map.of()))));
        assertThrows(BizException.class,()->engine.graph(List.of(new Step("pitch",Map.of("semitones",Double.NaN)))));
        assertThrows(BizException.class,()->engine.graph(List.of(new Step("pitch",Map.of("semitones",13d)))));
        assertThrows(BizException.class,()->engine.graph(List.of(new Step("echo",Map.of("delay",100d,"decay",0.3,"command",1d)))));
        verifyNoInteractions(processes);
    }
    @Test void typedParametersRejectCommandsWrongTypesAndMismatchedVoiceLists() {
        var processes=mock(NativeProcessExecutor.class);
        var engine=new VoiceEffectEngine(properties,processes,mock(VideoFrameExtractService.class));
        for(Step step:List.of(
            new Step("rubberband",Map.of("formant","preserved;amovie=http://host")),
            new Step("afftdn",Map.of("tracking","true")),
            new Step("volume",Map.of("gain","2dB")),
            new Step("chorus",Map.of("delays",List.of(30,50))),
            new Step("chorus",Map.of("delays",List.of(1,2,3,4,5))),
            new Step("deesser",Map.of("intensity",1.1)),
            new Step("rubberband",Map.of("model","/tmp/model")),
            new Step("highpass",Map.of("frequency",300,"poles",2))))
            assertThrows(BizException.class,()->engine.graph(List.of(step)),step.type());
        verifyNoInteractions(processes);
    }
    @Test void missingOptionalLibraryOnlyDisablesItsOwnProcessingEntry() throws Exception {
        var processes=mock(NativeProcessExecutor.class);
        when(processes.execute(anyList(),any())).thenReturn(new NativeProcessExecutor.ProcessResult(0,false," TS highpass A->A highpass\n .. lowpass A->A lowpass\n"));
        var engine=new VoiceEffectEngine(properties,processes,mock(VideoFrameExtractService.class));
        assertEquals(true,engine.capabilities().stream().filter(f -> "highpass".equals(f.get("type"))).findFirst().orElseThrow().get("available"));
        var missing=engine.capabilities().stream().filter(f -> "rubberband".equals(f.get("type"))).findFirst().orElseThrow();
        assertEquals(false,missing.get("available"));assertEquals(List.of("rubberband"),missing.get("missingFilters"));
    }
    @Test void repeatReorderBypassAndTypedValuesCompileWithoutUserGraphSyntax() {
        var engine=new VoiceEffectEngine(properties,mock(NativeProcessExecutor.class),mock(VideoFrameExtractService.class));
        var steps=List.of(new Step("equalizer",Map.of("frequency",800,"gain",3)),
            new Step("volume",Map.of("gain",-6),false),new Step("equalizer",Map.of("frequency",2500,"gain",-2)),
            new Step("afftdn",Map.of("tracking",true)),new Step("chorus",Map.of("delays",List.of(40,60),
                "decays",List.of(0.3,0.2),"speeds",List.of(0.3,0.4),"depths",List.of(1,2))));
        String graph=engine.graph(steps);
        assertFalse(graph.contains("volume="));
        assertTrue(graph.indexOf("f=800")<graph.indexOf("f=2500"));
        assertTrue(graph.contains("tn=1"));assertTrue(graph.contains("delays=40|60"));
        String bypass=engine.graph(List.of(new Step("rubberband",Map.of(),false)));
        assertFalse(bypass.contains("rubberband="));assertTrue(bypass.contains("anull[out]"));
        assertEquals(engine.normalize(List.of(new Step("equalizer",Map.of("gain",2)))),
            engine.normalize(List.of(new Step("equalizer",Map.of("gain",2d)))));
    }
    @Test void completeCatalogDefaultsAndNonDefaultTypesProduceExecutableGraphFixtures() throws Exception {
        var engine=new VoiceEffectEngine(properties,mock(NativeProcessExecutor.class),mock(VideoFrameExtractService.class));
        Map<String,String> graphs=new LinkedHashMap<>();
        for(var filter:VoiceEffectEngine.FILTERS) {
            Map<String,Object> params=new LinkedHashMap<>();filter.parameters().forEach(p -> params.put(p.key(),p.initial()));
            graphs.put(filter.type(),engine.graph(List.of(new Step(filter.type(),params))));
        }
        graphs.put("mixed_typed_chain",engine.graph(List.of(new Step("highpass",Map.of("frequency",150,"normalize",true)),
            new Step("rubberband",Map.of("pitch",1.1,"formant","preserved")),new Step("chorus",Map.of()),
            new Step("limiter",Map.of("level",false)),new Step("dual",Map.of("semitones",3,"mix",0.2,"delay",10)))));
        graphs.put("loudnorm_pitch_chain",engine.graph(List.of(new Step("loudnorm",Map.of()),new Step("pitch",Map.of("semitones",3)))));
        Path fixtures=Path.of("target/voice-filter-work/graphs.json");Files.createDirectories(fixtures.getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(fixtures.toFile(),graphs);
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
            Map<String,Object> parameters=new LinkedHashMap<>();
            definition.parameters().forEach(p -> parameters.put(p.key(),p.initial()));
            var result=assertDoesNotThrow(()->engine.process(source,List.of(new Step(definition.type(),parameters))),definition.type());
            assertTrue(result.duration()>1 && result.duration()<4,definition.type());
            assertTrue(result.bytes().length>1000,definition.type());
            assertEquals('R',result.bytes()[0],definition.type());
        }
        var clockSensitive=engine.process(source,List.of(new Step("loudnorm",Map.of()),new Step("pitch",Map.of("semitones",3))));
        assertEquals(1.5,clockSensitive.duration(),0.15,"响度处理后变调不应改变台词时长");
    }
    static byte[] sine() throws Exception {
        int rate=24000,frames=36000;byte[] pcm=new byte[frames*2];
        for(int i=0;i<frames;i++) {short sample=(short)(Math.sin(2*Math.PI*220*i/rate)*8000);pcm[i*2]=(byte)sample;pcm[i*2+1]=(byte)(sample>>8);}
        try(var output=new ByteArrayOutputStream();var input=new AudioInputStream(new ByteArrayInputStream(pcm),new AudioFormat(rate,16,1,true,false),frames)) {
            AudioSystem.write(input,AudioFileFormat.Type.WAVE,output);return output.toByteArray();
        }
    }
}
