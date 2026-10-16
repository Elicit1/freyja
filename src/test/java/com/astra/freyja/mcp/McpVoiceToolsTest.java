package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.voice.CharacterVoiceDesignDTO;
import com.astra.freyja.dto.voice.CharacterVoiceResultVO;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.CharacterVoiceService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpVoiceToolsTest {
    static final String PROJECT = "2032095628944588801", CHARACTER = "2032095628944588802", PROVIDER = "2032095628944588803";
    final McpScope scope = mock(McpScope.class);
    final McpBusinessRecords records = mock(McpBusinessRecords.class);
    final McpQueryMapper queries = mock(McpQueryMapper.class);
    final McpBusinessOperationMapper operations = mock(McpBusinessOperationMapper.class);
    final CharacterVoiceService voices = mock(CharacterVoiceService.class);
    final AiProviderMapper providers = mock(AiProviderMapper.class);
    final AiModelMapper models = mock(AiModelMapper.class);
    final McpJson json = new McpJson();
    final McpBusinessView views = new McpBusinessView(json, mock(McpSchemaMetadata.class));
    final McpOperationStore journal = new McpOperationStore(operations, json, scope);
    final McpVoiceTools tools = new McpVoiceTools(scope, records, views, queries, journal, voices, providers, models);
    final McpPrincipal caller = new McpPrincipal(true, false, false, Set.of(Long.valueOf(PROJECT)));
    final ResCharacter character = new ResCharacter();

    @BeforeEach void setup() {
        character.setId(Long.valueOf(CHARACTER)); character.setDramaId(Long.valueOf(PROJECT)); character.setName("林川");
        when(scope.project(caller, PROJECT)).thenReturn(Long.valueOf(PROJECT));
        when(records.require(caller, McpRecordType.CHARACTER, CHARACTER, PROJECT, true)).thenReturn(character);
        when(records.lockScoped(caller, McpRecordType.CHARACTER, CHARACTER, PROJECT)).thenReturn(character);
        when(records.read(caller, McpRecordType.CHARACTER, CHARACTER, PROJECT))
                .thenAnswer(inv -> views.project(McpBusinessView.Kind.CHARACTER, character));
        when(queries.lockProject(Long.valueOf(PROJECT))).thenReturn(new Drama());
        Map<String, McpBusinessOperation> stored = new HashMap<>();
        when(operations.reserve(any())).thenAnswer(inv -> {
            McpBusinessOperation value = inv.getArgument(0);
            value.setStatus("RUNNING"); return stored.putIfAbsent(value.getRequestId(), value) == null ? 1 : 0;
        });
        when(operations.lock(anyString())).thenAnswer(inv -> stored.get(inv.getArgument(0)));
        when(operations.complete(any())).thenAnswer(inv -> {
            McpBusinessOperation value = inv.getArgument(0); value.setStatus("SUCCEEDED"); return 1;
        });
        when(voices.designCharacterVoice(eq(Long.valueOf(CHARACTER)), any())).thenAnswer(inv -> {
            CharacterVoiceDesignDTO dto = inv.getArgument(1);
            CharacterVoiceResultVO result = new CharacterVoiceResultVO();
            result.setCharacterId(character.getId()); result.setVoiceSampleUrl("https://media.example/sample.wav");
            result.setVoiceDesc(dto.getVoiceDesc()); result.setSampleText(dto.getSampleText()); result.setDuration(2.5);
            if (Boolean.TRUE.equals(dto.getAutoSave())) {
                character.setVoiceSampleUrl(result.getVoiceSampleUrl());
                character.setVoiceDesc(dto.getVoiceDesc()); character.setVoiceSampleText(dto.getSampleText());
            }
            return result;
        });
    }

    McpVoiceTools.Design input(Boolean save) {
        return new McpVoiceTools.Design(PROJECT, CHARACTER, "voice-request-0001", views.fingerprint(character),
                PROVIDER, "voice-model", "清朗温和的青年男声", "你好，很高兴认识你。", save);
    }
    @SuppressWarnings("unchecked") McpTool<Object> tool(String name) {
        return (McpTool<Object>) tools.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }
    Object call(McpVoiceTools.Design input) { return tool(McpVoiceTools.DESIGN_TOOL).handler().apply(caller, input); }

    @Test void savesAndReplaysCommittedResultWithoutAnotherModelCallOrFingerprintCheck() {
        var request = input(null);
        String original = views.fingerprint(character);
        Map<?, ?> first = (Map<?, ?>) json.value(call(request));
        assertEquals(true, first.get("saved"));
        assertNotEquals(original, views.fingerprint(character));
        Map<?, ?> persisted = (Map<?, ?>) first.get("character");
        assertEquals(CHARACTER, persisted.get("id"));
        assertEquals(request.voiceDesc(), persisted.get("voiceDesc"));
        assertEquals(request.sampleText(), persisted.get("voiceSampleText"));
        assertEquals(first, json.value(call(request)));
        verify(voices, times(1)).designCharacterVoice(eq(Long.valueOf(CHARACTER)), any());
        verify(records, times(1)).lockScoped(caller, McpRecordType.CHARACTER, CHARACTER, PROJECT);
    }

    @Test void auditionsWithoutSavingAndStillJournalsTheGeneratedSample() {
        String fingerprint = views.fingerprint(character);
        Map<?, ?> result = (Map<?, ?>) call(input(false));
        assertEquals(false, result.get("saved")); assertEquals(fingerprint, views.fingerprint(character));
        assertNotNull(((CharacterVoiceResultVO) result.get("audio")).getVoiceSampleUrl());
        verify(operations).complete(any());
    }

    @Test void blocksReadOnlyCrossProjectAndPublicCharacterRequestsBeforeInference() {
        var reader = new McpPrincipal(false, true, false, Set.of());
        assertEquals(403, assertThrows(BizException.class, () -> tool(McpVoiceTools.DESIGN_TOOL).handler().apply(reader, input(false))).getCode());
        when(scope.project(caller, PROJECT)).thenThrow(new BizException(403, "PROJECT_ACCESS_DENIED"));
        assertEquals(403, assertThrows(BizException.class, () -> call(input(null))).getCode());
        when(scope.project(caller, PROJECT)).thenReturn(Long.valueOf(PROJECT));
        when(records.require(caller, McpRecordType.CHARACTER, CHARACTER, PROJECT, true)).thenThrow(new BizException(403, "公共库通过 MCP 只读"));
        assertEquals(403, assertThrows(BizException.class, () -> call(input(false))).getCode());
        verifyNoInteractions(voices, operations);
    }

    @Test void staleFingerprintNeverCallsModelAndReusedRequestCannotChangeContent() {
        var request = input(null);
        character.setPersonality("角色已被其他编辑者修改");
        assertEquals(409, assertThrows(BizException.class, () -> call(request)).getCode());
        verifyNoInteractions(voices);
        // A fresh request succeeds, then changing autoSave on the same request ID is rejected.
        var fresh = new McpVoiceTools.Design(PROJECT, CHARACTER, "voice-request-0002", views.fingerprint(character),
                PROVIDER, "voice-model", request.voiceDesc(), request.sampleText(), false);
        call(fresh);
        var changed = new McpVoiceTools.Design(PROJECT, CHARACTER, fresh.requestId(), fresh.expectedFingerprint(),
                PROVIDER, "voice-model", fresh.voiceDesc(), fresh.sampleText(), true);
        assertEquals(409, assertThrows(BizException.class, () -> call(changed)).getCode());
        verify(voices, times(1)).designCharacterVoice(anyLong(), any());
    }

    @Test void replayRechecksCurrentAuthorization() {
        var request = input(false); call(request);
        when(records.require(caller, McpRecordType.CHARACTER, CHARACTER, PROJECT, true)).thenThrow(new BizException(403, "SCOPE_VIOLATION"));
        assertEquals(403, assertThrows(BizException.class, () -> call(request)).getCode());
        verify(voices, times(1)).designCharacterVoice(anyLong(), any());
    }

    @Test void modelDiscoveryExposesOnlyPublicSelectionMetadata() {
        AiProvider provider = new AiProvider(); provider.setId(Long.valueOf(PROVIDER)); provider.setProviderName("声音提供商");
        provider.setApiKey("must-not-leak"); provider.setBaseUrl("https://private.example");
        AiModel model = new AiModel(); model.setProviderId(provider.getId()); model.setModelCode("voice-model");
        model.setModelType("TTS"); model.setModelName("声音设计"); model.setParamsJson("private-model-params");
        when(providers.selectList(any())).thenReturn(List.of(provider)); when(models.selectList(any())).thenReturn(List.of(model));
        String result = json.write(tool(McpVoiceTools.MODELS_TOOL).handler().apply(caller, new McpInputs.Project(PROJECT)));
        assertTrue(result.contains(PROVIDER)); assertTrue(result.contains("voice-model"));
        assertFalse(result.contains("must-not-leak")); assertFalse(result.contains("private.example")); assertFalse(result.contains("private-model-params"));
        verifyNoInteractions(voices, operations);
    }

    @Test void protocolRejectsNumericIdsUnknownFieldsAndOversizedTextBeforeInference() {
        var definition = new McpToolCatalog(json).specification(tool(McpVoiceTools.DESIGN_TOOL));
        var context = McpTransportContext.create(Map.of(McpPrincipal.CONTEXT_KEY, caller));
        Map<String, Object> args = json.read(input(false), Map.class);
        args.put("characterId", Long.valueOf(CHARACTER));
        assertTrue(definition.callHandler().apply(context, new McpSchema.CallToolRequest(McpVoiceTools.DESIGN_TOOL, args)).isError());
        args.put("characterId", CHARACTER); args.put("unknown", "field");
        assertTrue(definition.callHandler().apply(context, new McpSchema.CallToolRequest(McpVoiceTools.DESIGN_TOOL, args)).isError());
        args.remove("unknown"); args.put("voiceDesc", "声".repeat(513));
        assertTrue(definition.callHandler().apply(context, new McpSchema.CallToolRequest(McpVoiceTools.DESIGN_TOOL, args)).isError());
        verifyNoInteractions(voices, operations);
    }
}
