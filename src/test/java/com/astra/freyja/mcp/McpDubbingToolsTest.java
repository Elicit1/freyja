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
