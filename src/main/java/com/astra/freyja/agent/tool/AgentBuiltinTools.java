package com.astra.freyja.agent.tool;

import com.astra.freyja.agent.memory.AgentMemoryService;
import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.dao.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.skill.service.SkillContentService;
import com.astra.freyja.skill.tool.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Configuration
@RequiredArgsConstructor
public class AgentBuiltinTools {
    public record EmptyInput() {}
    public record AssetsInput(Integer limit, boolean includePublic) {}
    public record ShotInput(Long shotId) {}
    private final DramaMapper dramas;
    private final DramaShotMapper shots;
    private final ResCharacterMapper characters;
    private final ResSceneMapper scenes;
    private final ResPropMapper props;
    private final ResKeyframeMapper keyframes;
    private final AgentStepMapper steps;
    private final AgentMemoryService memory;
    private final SkillContentService skillContent;
    private final LoadSkillToolFactory loadSkills;
    private final ReadSkillFileToolFactory readSkills;
    private final AgentJson json;

    private abstract static class ReadTool<I> implements AgentTool<I> {
        private final String name, description;
        private final Class<I> type;
        private final boolean projectRequired;
        ReadTool(String name, String description, Class<I> type, boolean projectRequired) {
            this.name = name; this.description = description; this.type = type; this.projectRequired = projectRequired;
        }
        public String name() { return name; }
        public String description() { return description; }
        public Class<I> inputType() { return type; }
        public boolean projectRequired() { return projectRequired; }
    }
    @Bean AgentTool<AgentMemoryService.SaveNote> saveNoteTool() {
        return new AgentTool<>() {
            public String name() { return "save_session_note"; }
            public String description() { return "保存、替换或撤回用户明确指定的规则。scope=SESSION/PROJECT，sourceMessageId 必须引用当前任务的用户消息。enabled=false 撤回。"; }
            public Class<AgentMemoryService.SaveNote> inputType() { return AgentMemoryService.SaveNote.class; }
            public Effect effect() { return Effect.WRITE; }
            public RecoveryMode recoveryMode() { return RecoveryMode.TRANSACTIONAL; }
            public ToolResult execute(AgentMemoryService.SaveNote input, ToolContext context) { return memory.save(input, context); }
        };
    }
    @Bean AgentTool<EmptyInput> listNotesTool() {
        return new ReadTool<>("list_session_notes", "读取当前会话和项目的有效用户规则。", EmptyInput.class, false) {
            public ToolResult execute(EmptyInput input, ToolContext context) { return ToolResult.success(memory.list(context)); }
        };
    }
    @Bean AgentTool<LoadSkillRequest> loadSkillAgentTool() {
        return new ReadTool<>("load_skill", "按名称读取本次任务固定版本的 Skill 正文，只能选择配置目录内的名称。", LoadSkillRequest.class, false) {
            public ToolResult execute(LoadSkillRequest input, ToolContext context) {
                if (!context.snapshot().config().getSkills().isDynamicLoading()) return ToolResult.error("DYNAMIC_LOADING_DISABLED", "已在上下文预加载配置的 Skills");
                return skillResult(loadSkills.createTool(new LoadSkillToolSession(context.snapshot().skillVersions())).call(json.write(input)));
            }
        };
    }
    @Bean AgentTool<ReadSkillFileRequest> readSkillAgentTool() {
        return new ReadTool<>("read_skill_file", "读取已加载 Skill 的 references/ 下的文本，使用本次任务固定版本。", ReadSkillFileRequest.class, false) {
            public ToolResult execute(ReadSkillFileRequest input, ToolContext context) {
                LoadSkillToolSession session = new LoadSkillToolSession(context.snapshot().skillVersions());
                String name = input == null ? null : input.getName();
                Long version = name == null ? null : context.snapshot().skillVersions().get(name);
                if (version == null) return ToolResult.error("SKILL_NOT_ALLOWED", "Skill 不在本次版本目录中");
                boolean loaded = !context.snapshot().config().getSkills().isDynamicLoading();
                if (!loaded) {
                    var loadingNames = context.snapshot().capabilities().bindings().stream()
                            .filter(b -> b.handlerKey().equals(com.astra.freyja.capability.model.BuiltinCapability.LOAD_SKILL.handlerKey())).map(b -> b.name()).toList();
                    loaded = !loadingNames.isEmpty() && steps.selectList(new LambdaQueryWrapper<AgentStep>().eq(AgentStep::getRunId, context.runId())
                            .in(AgentStep::getToolName, loadingNames).eq(AgentStep::getStatus, "SUCCEEDED")).stream().anyMatch(step -> {
                        ToolCall call = json.read(step.getInputJson(), ToolCall.class);
                        LoadSkillRequest previous = json.read(call.arguments(), LoadSkillRequest.class);
                        ToolResult result = json.read(step.getResultJson(), ToolResult.class);
                        return Objects.equals(name, previous.getName()) && result.errorCode() == null;
                    });
                }
                if (!loaded) return ToolResult.error("SKILL_NOT_LOADED", "请先成功调用 load_skill");
                session.preload(name, skillContent.loadSkillVersion(version));
                return skillResult(readSkills.createTool(session).call(json.write(input)));
            }
        };
    }
    @SuppressWarnings("unchecked")
    private ToolResult skillResult(String response) {
        Map<String, Object> result = json.read(response, Map.class);
        return "ERROR".equals(result.get("status")) ? ToolResult.error("SKILL_ERROR", Objects.toString(result.get("message"), "Skill 读取失败"))
                : ToolResult.success(result);
    }
}
