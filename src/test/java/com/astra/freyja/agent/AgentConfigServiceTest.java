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
        service = new AgentConfigService(mapper, models, profiles, tools, skills, validator, json, new TransactionTemplate(manager));
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
    @Test void upgradeCombinesCapabilitiesButKeepsPrimaryModelAndPet() {
        config.setDisplayName("我的助手"); config.setEnabled(false); config.getInstructions().setSystemPrompt("保持原文对白");
        config.getAvatar().getPet().setSpritesheetUrl("https://example.test/my-pet.webp");
        config.getTools().setNames(List.of("list_project_assets", "get_shot_detail"));
        var primary = stored(config, 1);
        var legacy = json.copy(config, AgentConfigDTO.class); legacy.setAgentCode("production-preparation"); legacy.setEnabled(true);
        legacy.getModel().setProviderId(999L); legacy.getModel().setModelCode("other-model");
        legacy.getAvatar().getPet().setSpritesheetUrl("https://example.test/other-pet.webp");
        legacy.getInstructions().setSystemPrompt("角色造型保持一致");
        when(mapper.selectOne(any())).thenReturn(primary, stored(legacy, 1)); when(mapper.lockByCode("core-assistant")).thenReturn(primary);
        var result = service.get("core-assistant");
        assertEquals("我的助手", result.getDisplayName()); assertTrue(result.isEnabled()); assertEquals(9, result.getRevision());
        assertEquals(config.getModel().getProviderId(), result.getModel().getProviderId()); assertEquals(config.getModel().getModelCode(), result.getModel().getModelCode());
        assertEquals(config.getAvatar().getPet().getSpritesheetUrl(), result.getAvatar().getPet().getSpritesheetUrl());
        assertTrue(result.getTools().getNames().containsAll(List.of("list_project_assets", "start_script_decomposition", "inspect_preparation")));
        assertEquals(48000, result.getMemory().getContextTokens()); assertEquals(60, result.getRuntime().getMaxModelTurns());
        assertTrue(result.getInstructions().getSystemPrompt().contains("角色造型保持一致"));
        verify(mapper, times(1)).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
    @Test void legacyIdentifierResolvesOneConfigurationAndNewSnapshotsUseUnifiedCode() {
        when(mapper.selectOne(any())).thenReturn(stored(config, 3));
        assertEquals("core-assistant", service.get("production-preparation").getAgentCode());
        verify(mapper, never()).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        var snapshot = service.snapshot("production-preparation", config, "NORMAL");
        assertEquals("core-assistant", snapshot.agentCode()); assertEquals(3, snapshot.profileVersion());
        assertEquals("core-assistant", snapshot.config().getAgentCode());
    }
    @Test void oneRegisteredAssistantHasQueryAndProductionCapabilities() {
        var profile = AgentTestSupport.profile(); var registry = new AgentProfileRegistry(List.of(profile));
        assertEquals(1, registry.all().size()); assertSame(profile, registry.require("production-preparation"));
        assertEquals("FREYJA 助手", profile.defaults().getDisplayName());
        assertTrue(profile.allowedTools().containsAll(List.of("list_project_assets", "ensure_text_assets", "prepare_shot_prompts")));
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
}
