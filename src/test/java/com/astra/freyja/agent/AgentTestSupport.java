package com.astra.freyja.agent;

import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.dto.agent.AgentConfigDTO;
import com.astra.freyja.agent.tool.AgentTool;
import com.astra.freyja.agent.tool.AgentToolRegistry;
import com.astra.freyja.capability.model.CapabilityModels.*;
import com.astra.freyja.capability.service.CapabilityCatalogService;
import com.astra.freyja.entity.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

final class AgentTestSupport {
    /** Published capability fixture for tests that exercise the real Agent platform adapter. */
    static CapabilityCatalogService capabilities(List<AgentTool<?>> tools) {
        var catalog = mock(CapabilityCatalogService.class);
        var bindings = tools.stream().map(tool -> new Binding(tool.name(), "1", "1", "INTERNAL_AGENT:" + tool.name(),
                Consumer.INTERNAL_AGENT, "core-assistant", tool.name(), tool.name(), Map.of(), true, tool.requiredByDefault(),
                ContextMode.AGENT_RUNTIME, tool.projectRequired(), tool.effect(), tool.recoveryMode())).toList();
        var release = new Release("1", 1, bindings, "平台能力说明", "平台公共规则");
        when(catalog.current()).thenReturn(release);
        when(catalog.release("1")).thenReturn(release);
        when(catalog.agentSnapshot("core-assistant")).thenReturn(new AgentSnapshot("1", bindings));
        when(catalog.agentSnapshot(eq("core-assistant"), anyList())).thenAnswer(call -> {
            List<String> selected = call.getArgument(1);
            if (!bindings.stream().map(Binding::name).toList().containsAll(selected))
                throw new com.astra.freyja.common.BizException(403, "Agent配置包含未授权能力");
            return new AgentSnapshot("1", bindings.stream().filter(b -> selected.contains(b.name())).toList());
        });
        when(catalog.requireCurrent(anyString())).thenAnswer(call -> bindings.stream()
                .filter(b -> b.id().equals(call.getArgument(0))).findFirst().orElseThrow());
        when(catalog.agentTool(any())).thenAnswer(call -> {
            Binding binding = call.getArgument(0);
            return tools.stream().filter(t -> binding.handlerKey().equals("INTERNAL_AGENT:" + t.name())).findFirst().orElseThrow();
        });
        return catalog;
    }
    static AgentToolRegistry registry(List<AgentTool<?>> tools) { return new AgentToolRegistry(capabilities(tools)); }
    static com.astra.freyja.agent.profile.CoreAssistantProfile profile() {
        return new com.astra.freyja.agent.profile.CoreAssistantProfile(
                org.mockito.Mockito.mock(com.astra.freyja.dao.AgentPreparationJobMapper.class),
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));
    }
    static final Long SESSION = 2032095628944588801L;
    static final Long RUN = 2032095628944588802L;
    static final Long STEP = 2032095628944588803L;
    static void metadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> type : List.of(AgentConfig.class, AgentSession.class, AgentRun.class, AgentMessage.class,
                AgentStep.class, AgentMemory.class, AiModel.class, Drama.class)) TableInfoHelper.initTableInfo(assistant, type);
    }
    static Snapshot snapshot(List<String> tools, String type) {
        AgentConfigDTO config = new AgentConfigDTO();
        config.setEnabled(true); config.setRevision(1); config.setAgentCode("core-assistant");
        config.getTools().setNames(tools); config.getRuntime().setMaxRetries(0);
        config.getModel().setProviderId(2032095628944588804L); config.getModel().setModelCode("test-model");
        AiModel model = new AiModel(); model.setModelCode("test-model"); model.setModelType("CHAT"); model.setStatus(1);
        return new Snapshot("core-assistant", 1, 1, config, "固定约束", Map.of(),
                tools.stream().collect(java.util.stream.Collectors.toMap(name -> name, name -> 1)), model, type, null,
                new com.astra.freyja.capability.model.CapabilityModels.AgentSnapshot("1", tools.stream().map(name ->
                        new com.astra.freyja.capability.model.CapabilityModels.Binding(name, "1", "1", "INTERNAL_AGENT:" + name,
                                com.astra.freyja.capability.model.CapabilityModels.Consumer.INTERNAL_AGENT, "core-assistant", name, name, Map.of(), true, false,
                                com.astra.freyja.capability.model.CapabilityModels.ContextMode.AGENT_RUNTIME, false, Effect.READ, RecoveryMode.REPLAY_SAFE)).toList()));
    }
    static AgentRun run(Snapshot snapshot, com.astra.freyja.agent.model.AgentJson json) {
        AgentRun run = new AgentRun();
        run.setId(RUN); run.setSessionId(SESSION); run.setRunType(snapshot.runType()); run.setProfileSnapshotJson(json.write(snapshot));
        run.setStatus("RUNNING"); run.setLeaseOwner("test-owner"); run.setLeaseEpoch(1);
        run.setLeaseUntil(java.time.LocalDateTime.now().plusSeconds(30)); run.setCancelRequested(false);
        run.setObjective("查看项目"); run.setObjectiveMessageId(100L); run.setConstraintVersion(0);
        run.setModelTurns(0); run.setToolCalls(0); run.setNextStepOrder(1); run.setExecutionMillis(0L);
        run.setPromptTokens(0); run.setCompletionTokens(0); run.setUsageUnknown(false);
        return run;
    }
}
