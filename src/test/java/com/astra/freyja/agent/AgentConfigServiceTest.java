package com.astra.freyja.agent;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.profile.*;
import com.astra.freyja.agent.settings.*;
import com.astra.freyja.agent.tool.*;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.agent.AgentConfigDTO;
import com.astra.freyja.entity.*;
import com.astra.freyja.skill.service.SkillCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentConfigServiceTest {
    AgentConfigMapper mapper = mock(AgentConfigMapper.class);
    AiModelMapper models = mock(AiModelMapper.class);
    AiProviderMapper providers = mock(AiProviderMapper.class);
    SkillCatalogService skills = mock(SkillCatalogService.class);
    AgentJson json = new AgentJson();
    AgentConfigValidator validator;
    AgentConfigService service;
    AgentConfigDTO config;
    AiModel model;
    @BeforeEach void setup() {
        AgentTestSupport.metadata();
        CoreAssistantProfile profile = AgentTestSupport.profile();
        var profiles = new AgentProfileRegistry(List.of(profile));
        List<AgentTool<?>> entries = new ArrayList<>();
        for (String name : profile.allowedTools()) {
            AgentTool<?> tool = mock(AgentTool.class);
            when(tool.name()).thenReturn(name); when(tool.version()).thenReturn(1);
            when(tool.effect()).thenReturn("save_session_note".equals(name) ? com.astra.freyja.agent.model.AgentProtocol.Effect.WRITE : com.astra.freyja.agent.model.AgentProtocol.Effect.READ);
            when(tool.recoveryMode()).thenReturn("save_session_note".equals(name) ? com.astra.freyja.agent.model.AgentProtocol.RecoveryMode.TRANSACTIONAL : com.astra.freyja.agent.model.AgentProtocol.RecoveryMode.REPLAY_SAFE);
            entries.add(tool);
        }
        var tools = new AgentToolRegistry(entries);
        when(skills.getEnabledCatalog()).thenReturn(List.of());
        AiProvider provider = new AiProvider(); provider.setStatus(1);
        when(providers.selectById(anyLong())).thenReturn(provider);
        model = new AiModel(); model.setStatus(1); model.setModelType("CHAT"); model.setTemperature(0.4);
        model.setParamsJson("{\"capabilities\":{\"toolCalling\":true},\"seed\":42,\"api_key\":\"do-not-copy\"}");
        when(models.selectOne(any())).thenReturn(model);
        validator = new AgentConfigValidator(profiles, tools, providers, models, skills, json);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        service = new AgentConfigService(mapper, models, providers, profiles, tools, skills, validator, json, new TransactionTemplate(manager));
        config = AgentTestSupport.snapshot(profile.allowedTools(), "NORMAL").config();
    }
    @Test void activeRunUsesDetachedConfigurationAndNoCredentials() {
        var snapshot = service.snapshot("core-assistant", config, "NORMAL");
        config.getInstructions().setSystemPrompt("后来修改"); model.setTemperature(1.9);
        assertNotEquals("后来修改", snapshot.config().getInstructions().getSystemPrompt());
        assertEquals(0.4, snapshot.pinnedModel().getTemperature());
        assertFalse(json.write(snapshot).contains("do-not-copy"));
        assertTrue(snapshot.pinnedModel().getParamsJson().contains("42"));
    }
    @Test void mainAndIndependentSummaryPinInheritedProviderCapabilities() {
        var provider = providers.selectById(config.getModel().getProviderId());
        provider.setChatCapabilitiesJson("{\"reasoningProtocol\":\"MIMO_CHAT\",\"outputTokenParameter\":\"max_completion_tokens\",\"maxOutputTokens\":131072,\"recommendedOutputTokens\":65536}");
        config.getModel().setTokenMode("MODEL"); config.getModel().setReasoningMode("ENABLED");
        config.getMemory().setContextTokens(262144);
        config.getMemory().getSummary().setProviderId(config.getModel().getProviderId());
        config.getMemory().getSummary().setModelCode(config.getModel().getModelCode());
        var snapshot = service.snapshot("core-assistant", config, "NORMAL");
        assertEquals(65536, snapshot.config().getModel().getMaxTokens());
        assertEquals("MIMO_CHAT", com.astra.freyja.agent.model.ChatModelCapabilities.from(snapshot.pinnedModel()).reasoningProtocol());
        assertEquals("max_completion_tokens", com.astra.freyja.agent.model.ChatModelCapabilities.from(snapshot.summaryModel()).outputTokenParameter());
        assertEquals("DISABLED", snapshot.config().getMemory().getSummary().getReasoningMode());
        provider.setChatCapabilitiesJson("{\"reasoningProtocol\":\"NONE\"}");
        assertEquals("MIMO_CHAT", com.astra.freyja.agent.model.ChatModelCapabilities.from(snapshot.summaryModel()).reasoningProtocol());
        assertFalse(json.write(snapshot).contains("do-not-copy"));
        config.getModel().setReasoningMode("HIGH");
        provider.setChatCapabilitiesJson("{\"reasoningProtocol\":\"MIMO_CHAT\"}");
        assertTrue(validator.validate("core-assistant", config, true).errors().stream().anyMatch(e -> e.field().equals("model.reasoningMode")));
    }
    @Test void modelDrivenBudgetsUseDeclaredCapabilitiesWithoutTheOld32768Ceiling() {
        model.setMaxTokens(8192);
        model.setParamsJson("{\"capabilities\":{\"toolCalling\":true,\"contextWindow\":1048576,\"maxOutputTokens\":393216,\"recommendedOutputTokens\":65536,\"reasoningProtocol\":\"DEEPSEEK_CHAT\",\"reasoningEfforts\":[\"low\",\"high\",\"max\"]},\"api_key\":\"never-pin\"}");
        config.getModel().setTokenMode("MODEL"); config.getModel().setMaxTokens(8192); config.getMemory().setContextTokens(262144);
        assertTrue(validator.validate("core-assistant", config, true).valid());
        var snapshot = service.snapshot("core-assistant", config, "NORMAL");
        assertEquals(65536, snapshot.config().getModel().getMaxTokens()); assertEquals(8192, config.getModel().getMaxTokens());
        assertTrue(snapshot.pinnedModel().getParamsJson().contains("1048576")); assertFalse(json.write(snapshot).contains("never-pin"));
        config.getModel().setTokenMode("CUSTOM"); config.getModel().setMaxTokens(393217);
        assertTrue(validator.validate("core-assistant", config, true).errors().stream().anyMatch(e -> e.field().equals("model.maxTokens")));
        config.getModel().setMaxTokens(65536); config.getMemory().setContextTokens(1048577);
        assertTrue(validator.validate("core-assistant", config, true).errors().stream().anyMatch(e -> e.field().equals("memory.contextTokens")));
    }
    @Test void replyModeRoundTripsAndIsPinnedWhileLegacySnapshotsStayComplete() {
        var legacy = json.read("{\"providerId\":\"2032095628944588804\",\"modelCode\":\"test-model\"}", AgentConfigDTO.Model.class);
        assertEquals("COMPLETE", legacy.getOutputMode());
        config.getModel().setOutputMode("STREAM");
        when(mapper.selectOne(any())).thenReturn(stored(config, AgentTestSupport.profile().version()));
        assertEquals("STREAM", service.get("core-assistant").getModel().getOutputMode());
        var snapshot = service.snapshot("core-assistant", config, "NORMAL");
        config.getModel().setOutputMode("COMPLETE");
        assertEquals("STREAM", json.copy(snapshot, com.astra.freyja.agent.model.AgentProtocol.Snapshot.class).config().getModel().getOutputMode());
        assertTrue(validator.validate("core-assistant", config, true).valid());
        config.getModel().setOutputMode("invalid");
        assertTrue(validator.validate("core-assistant", config, true).errors().stream().anyMatch(e -> e.field().equals("model.outputMode")));
        config.getModel().setOutputMode(null);
        assertFalse(validator.validate("core-assistant", config, true).valid());
    }
    @Test void configurationTestsRemoveWriteToolsAndProjectMemory() {
        var snapshot = service.snapshot("core-assistant", config, "CONFIG_TEST");
        assertFalse(snapshot.toolVersions().containsKey("save_session_note"));
        assertTrue(snapshot.config().getMemory().getScopes().isEmpty());
        assertTrue(config.getTools().getNames().contains("save_session_note"));
    }
    @Test void saveRejectsStaleRevisionWithoutOverwriting() {
        AgentConfig stored = new AgentConfig(); stored.setAgentCode("core-assistant"); stored.setRevision(8);
        stored.setModelOptionsJson(json.write(config.getModel())); stored.setToolPolicyJson(json.write(config.getTools()));
        stored.setSkillPolicyJson(json.write(config.getSkills())); stored.setMemoryPolicyJson(json.write(config.getMemory()));
        stored.setRuntimePolicyJson(json.write(config.getRuntime())); stored.setAvatarConfigJson(json.write(config.getAvatar()));
        when(mapper.selectOne(any())).thenReturn(stored); when(mapper.lockByCode("core-assistant")).thenReturn(stored);
        config.setExpectedRevision(7);
        BizException conflict = assertThrows(BizException.class, () -> service.save("core-assistant", config));
        assertEquals(409, conflict.getCode());
        verify(mapper, never()).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
    @Test void unsupportedModelAndUnregisteredToolAreRejected() {
        model.setParamsJson("{\"capabilities\":{\"toolCalling\":false}}");
        config.getTools().setNames(List.of("render_video"));
        var result = validator.validate("core-assistant", config, true);
        assertFalse(result.valid()); assertTrue(result.errors().stream().anyMatch(e -> "UNKNOWN_TOOL".equals(e.code())));
        assertTrue(result.errors().stream().anyMatch(e -> "TOOL_CALLING_DISABLED".equals(e.code())));
    }
    @Test void missingModelIsAllowedOnlyForDisabledConfiguration() {
        config.getModel().setProviderId(null); config.getModel().setModelCode(null); config.setEnabled(false);
        assertTrue(validator.validate("core-assistant", config, false).valid());
        assertFalse(validator.validate("core-assistant", config, true).valid());
    }
    @Test void nullNestedValuesAndUnsafeAvatarReferencesReturnFieldErrors() {
        config.getModel().setTokenMode(null);
        assertTrue(validator.validate("core-assistant", config, true).errors().stream().anyMatch(e -> e.field().equals("model.tokenMode")));
        config.getModel().setTokenMode("CUSTOM");
        config.getTools().setNames(new ArrayList<>(java.util.Arrays.asList((String) null)));
        assertFalse(validator.validate("core-assistant", config, true).valid());
        var media = new AgentConfigDTO.StateMedia(); media.setUrl("javascript:alert(1)"); media.setContentType("image/gif");
        assertFalse(AgentConfigValidator.validMedia(media));
        media.setUrl("/media/idle.gif"); assertTrue(AgentConfigValidator.validMedia(media));
    }
    private AgentConfig stored(AgentConfigDTO dto, int version) {
        var row = new AgentConfig(); row.setId(123L); row.setAgentCode(dto.getAgentCode()); row.setProfileVersion(version);
        row.setDisplayName(dto.getDisplayName()); row.setDescription(dto.getDescription()); row.setEnabled(dto.isEnabled()); row.setRevision(8);
        row.setSystemPrompt(dto.getInstructions().getSystemPrompt()); row.setModelOptionsJson(json.write(dto.getModel()));
        row.setToolPolicyJson(json.write(dto.getTools())); row.setSkillPolicyJson(json.write(dto.getSkills()));
        row.setMemoryPolicyJson(json.write(dto.getMemory())); row.setRuntimePolicyJson(json.write(dto.getRuntime())); row.setAvatarConfigJson(json.write(dto.getAvatar()));
        return row;
    }
    @Test void currentConfigurationKeepsModelAndAvatarWithoutLegacyMigration() {
        config.setDisplayName("我的助手"); config.setEnabled(false);
        config.getAvatar().getPet().setSpritesheetUrl("https://example.test/my-pet.webp");
        config.getTools().setNames(List.of("get_project", "get_shot", "start_script_decomposition", "inspect_preparation"));
        when(mapper.selectOne(any())).thenReturn(stored(config, 1));
        var result = service.get("core-assistant");
        assertEquals("我的助手", result.getDisplayName()); assertFalse(result.isEnabled());
        assertEquals(config.getModel().getProviderId(), result.getModel().getProviderId());
        assertEquals(config.getAvatar().getPet().getSpritesheetUrl(), result.getAvatar().getPet().getSpritesheetUrl());
        assertTrue(result.getTools().getNames().containsAll(List.of("get_project", "get_shot", "inspect_preparation")));
        verify(mapper, never()).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
    @Test void retiredInternalProfileIsNotAliased() {
        assertEquals(404, assertThrows(com.astra.freyja.common.BizException.class, () -> service.get("production-preparation")).getCode());
    }
    @Test void oneRegisteredAssistantHasQueryAndProductionCapabilities() {
        var profile = AgentTestSupport.profile(); var registry = new AgentProfileRegistry(List.of(profile));
        assertEquals(1, registry.all().size()); assertSame(profile, registry.require("core-assistant"));
        assertEquals("FREYJA 助手", profile.defaults().getDisplayName());
        assertTrue(profile.allowedTools().containsAll(List.of("search_assets", "ensure_text_assets", "prepare_shot_prompts")));
    }
    @Test void questionsUpgradePreservesExistingSettingsWithoutMergingLegacyAgain() {
        config.setEnabled(false); config.getRuntime().setMaxModelTurns(12); config.getMemory().setContextTokens(16000);
        config.getTools().setNames(AgentTestSupport.profile().allowedTools().stream().filter(n -> !List.of("request_user_input", "list_project_assets").contains(n)).toList());
        var stored = stored(config, 2);
        when(mapper.selectOne(any())).thenReturn(stored); when(mapper.lockByCode("core-assistant")).thenReturn(stored);
        var upgraded = service.get("core-assistant");
        assertFalse(upgraded.isEnabled()); assertEquals(12, upgraded.getRuntime().getMaxModelTurns());
        assertEquals(16000, upgraded.getMemory().getContextTokens()); assertEquals(config.getModel(), upgraded.getModel());
        assertEquals(config.getAvatar(), upgraded.getAvatar());
        assertTrue(upgraded.getTools().getNames().contains("request_user_input"));
        assertFalse(upgraded.getTools().getNames().contains("list_project_assets"));
        verify(mapper, times(1)).selectOne(any());
    }
    @Test void chatDecompositionUpgradeAddsRequiredToolAndPreservesUserConfiguration() {
        config.setDisplayName("我的助手"); config.setEnabled(false); config.getRuntime().setMaxModelTurns(12);
        config.getMemory().setContextTokens(16000); config.getInstructions().setSystemPrompt("遵循我的拆解要求");
        config.getTools().setNames(AgentTestSupport.profile().allowedTools().stream()
                .filter(n -> !List.of("prepare_script_decomposition", "list_project_assets").contains(n)).toList());
        var previous = stored(config, 3);
        when(mapper.selectOne(any())).thenReturn(previous); when(mapper.lockByCode("core-assistant")).thenReturn(previous);
        var upgraded = service.get("core-assistant");
        assertTrue(upgraded.getTools().getNames().containsAll(List.of("prepare_script_decomposition", "request_user_input")));
        assertFalse(upgraded.getTools().getNames().contains("list_project_assets"));
        assertFalse(upgraded.isEnabled()); assertEquals(config.getModel(), upgraded.getModel());
        assertEquals(config.getAvatar(), upgraded.getAvatar()); assertEquals(config.getRuntime(), upgraded.getRuntime());
        assertEquals(config.getMemory(), upgraded.getMemory()); assertEquals(config.getInstructions(), upgraded.getInstructions());
        assertEquals("我的助手", upgraded.getDisplayName()); assertEquals(8, upgraded.getRevision());
        verify(mapper, times(1)).selectOne(any());
    }
    @Test void progressUpgradeAddsPlanToolAndKeepsUnlimitedTurnsAndOtherSettings() {
        config.getRuntime().setMaxModelTurns(0); config.setEnabled(false);
        config.getTools().setNames(config.getTools().getNames().stream().filter(n -> !n.equals("update_task_plan")).toList());
        var previous = stored(config, 4);
        when(mapper.selectOne(any())).thenReturn(previous); when(mapper.lockByCode("core-assistant")).thenReturn(previous);
        var upgraded = service.get("core-assistant");
        assertTrue(upgraded.getTools().getNames().contains("update_task_plan"));
        assertEquals(0, upgraded.getRuntime().getMaxModelTurns()); assertFalse(upgraded.isEnabled());
        assertEquals(config.getModel(), upgraded.getModel()); assertEquals(config.getAvatar(), upgraded.getAvatar());
        assertTrue(validator.validate("core-assistant", upgraded, false).valid());
        upgraded.getRuntime().setMaxModelTurns(-1); assertFalse(validator.validate("core-assistant", upgraded, false).valid());
    }
}
