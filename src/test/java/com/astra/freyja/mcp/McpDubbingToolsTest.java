package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.voice.DubbingInputs.Effects;
import com.astra.freyja.entity.CharacterVoiceAudio;
import com.astra.freyja.service.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpDubbingToolsTest {
    final McpScope scope=mock(McpScope.class);
    final McpBusinessRecords records=mock(McpBusinessRecords.class);
    final McpOperationStore journal=mock(McpOperationStore.class);
    final DubbingService dubbing=mock(DubbingService.class);
    final VoiceEffectService effects=mock(VoiceEffectService.class);
    final AiTaskService tasks=mock(AiTaskService.class);
    final McpDubbingTools adapter=new McpDubbingTools(scope,records,journal,dubbing,effects,tasks);
    final McpPrincipal writer=new McpPrincipal(true,false,false,Set.of(10L));
    @SuppressWarnings("unchecked") Object call(String name,McpPrincipal caller,Object input) {
        return ((McpTool<Object>)adapter.tools().stream().filter(t -> name.equals(t.name())).findFirst().orElseThrow()).handler().apply(caller,input);
    }
    @SuppressWarnings("unchecked") @BeforeEach void journal() {
        when(journal.execute(anyString(),anyString(),any(),any())).thenAnswer(inv -> ((Supplier<McpOperationStore.Outcome>)inv.getArgument(3)).get().data());
    }
    @Test void clearEffectsUsesSharedServiceAndDoesNotNeedTts() {
        var audio=new CharacterVoiceAudio();audio.setCharacterId(20L);when(dubbing.require(30L)).thenReturn(audio);
        call("dubbing_update_effects",writer,new McpDubbingTools.Modify("10","20","30","request-123",4,List.of()));
        verify(dubbing).updateEffects(30L,new Effects(4,List.of()));
        verify(records).require(writer,McpRecordType.CHARACTER,"20","10",true);
        verify(records).lockScoped(writer,McpRecordType.CHARACTER,"20","10");
    }
    @Test void generationForwardsJsonToSharedService() {
        var request=Map.<String,Object>of("messages",List.of(Map.of("role","assistant","content","(愤怒)马上[提高音量]离开！")));
        call("dubbing_create_audio",writer,new McpDubbingTools.Generate("10","20","request-json","配音","1",
                "mimo-v2.5-tts-voiceclone","CLONE",request,List.of(),null));
        verify(dubbing).create(20L,new com.astra.freyja.dto.voice.DubbingInputs.Create("配音",1L,
                "mimo-v2.5-tts-voiceclone",request,List.of(),"CLONE",null));
    }
    @SuppressWarnings("unchecked") @Test void generationSchemaAcceptsNestedModelJson() throws Exception {
        String encoded=org.springframework.ai.util.json.schema.JsonSchemaGenerator.generateForType(McpDubbingTools.Generate.class);
        Map<String,Object> schema=new McpJson().mapper().readValue(encoded,Map.class);
        var values=Map.<String,Object>of("dramaId","10","characterId","20","requestId","request-json","name","配音",
                "providerId","1","modelCode","mimo-v2.5-tts-voiceclone","method","CLONE",
                "modelRequest",Map.of("messages",List.of(Map.of("role","assistant","content","(平静)你好。")),
                        "audio",Map.of("optimize_text_preview",false),"temperature",0.7));
        var result=new io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator().validate(schema,values);
        assertTrue(result.valid(),encoded+"\n"+result);
        var json=new McpJson();
        var bound=json.read(values,McpDubbingTools.Generate.class).modelRequest();
        assertEquals(json.mapper().writeValueAsString(values.get("modelRequest")),json.mapper().writeValueAsString(bound));
    }
    @Test void parameterCatalogIsScopedAndUsesTheWorkbenchDefinitions() {
        var catalog=List.<Map<String,Object>>of(Map.of("type","rubberband","available",true));
        when(effects.filterCapabilities()).thenReturn(catalog);
        assertEquals(catalog,call("dubbing_list_filters",writer,new McpDubbingTools.Filters("10")));
        verify(scope).project(writer,"10");verifyNoInteractions(dubbing,tasks);
    }
    @SuppressWarnings("unchecked") @Test void toolSchemaAndBindingAcceptTypedParametersAndOptionalStepEnabled() throws Exception {
        String encoded=org.springframework.ai.util.json.schema.JsonSchemaGenerator.generateForType(McpDubbingTools.Modify.class);
        Map<String,Object> schema=new McpJson().mapper().readValue(encoded,Map.class);
        var values=Map.of("dramaId","10","characterId","20","audioId","30","requestId","request-123","revision",1,
            "effects",List.of(Map.of("effectId","1","steps",List.of(
                Map.of("type","rubberband","params",Map.of("pitch",1.1,"formant","preserved")),
                Map.of("type","afftdn","params",Map.of("tracking",true)),
                Map.of("type","chorus","params",Map.of("delays",List.of(40,60),"decays",List.of(0.3,0.2),
                    "speeds",List.of(0.3,0.4),"depths",List.of(1,2)))))));
        var validator=new io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator();
        assertTrue(validator.validate(schema,values).valid(),encoded);
        var dto=new McpJson().read(values,McpDubbingTools.Modify.class);
        var step=dto.effects().getFirst().steps().getFirst();
        assertTrue(step.active());assertEquals("preserved",step.params().get("formant"));
        assertEquals(true,dto.effects().getFirst().steps().get(1).params().get("tracking"));
        assertEquals(List.of(40,60),dto.effects().getFirst().steps().get(2).params().get("delays"));
    }
    @Test void rejectsCrossCharacterAudioBeforeMutation() {
        var audio=new CharacterVoiceAudio();audio.setCharacterId(21L);when(dubbing.require(30L)).thenReturn(audio);
        assertEquals(403,assertThrows(BizException.class,()->call("dubbing_update_effects",writer,new McpDubbingTools.Modify("10","20","30","request-123",4,List.of()))).getCode());
        verify(dubbing,never()).updateEffects(any(),any());
    }
    @Test void readOnlyCallerCannotUploadAndLocalPathsAreNotAudioBytes() {
        var input=new McpDubbingTools.Upload("10","20","request-123","基础声音","你好","C:\\audio.wav",null);
        var reader=new McpPrincipal(false,false,false,Set.of(10L));
        assertEquals(403,assertThrows(BizException.class,()->call("dubbing_upload_base_audio",reader,input)).getCode());
        verifyNoInteractions(journal,dubbing);
        assertEquals(400,assertThrows(BizException.class,()->call("dubbing_upload_base_audio",writer,input)).getCode());
        verify(dubbing,never()).uploadBase(any(),any(),any(),any(),any());
    }
    @Test void passesActualUploadedBytesAndReplacementIdentityThroughTheSameService() {
        byte[] bytes={1,2,3};
        call("dubbing_upload_base_audio",writer,new McpDubbingTools.Upload("10","20","request-123","基础声音","你好",Base64.getEncoder().encodeToString(bytes),"40"));
        verify(dubbing).uploadBase(eq(20L),eq("基础声音"),eq("你好"),aryEq(bytes),eq(40L));
    }
    private static byte[] aryEq(byte[] bytes) { return org.mockito.AdditionalMatchers.aryEq(bytes); }
}
