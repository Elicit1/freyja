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
    @Bean AgentTool<EmptyInput> projectOverviewTool() {
        return new ReadTool<>("get_project_overview", "读取当前绑定项目的概要与分镜数量，无需项目 ID 参数。", EmptyInput.class, true) {
            public ToolResult execute(EmptyInput input, ToolContext context) {
                Drama drama = dramas.selectById(context.dramaId());
                if (drama == null) return ToolResult.error("NOT_FOUND", "项目不存在");
                return ToolResult.success(Map.of("project", drama, "shotCount",
                        shots.selectCount(new LambdaQueryWrapper<DramaShot>().eq(DramaShot::getDramaId, context.dramaId()))));
            }
        };
    }
    public record Asset(Long id, String type, String name, String description, String imageUrl, Long dramaId) {}
    @Bean AgentTool<AssetsInput> projectAssetsTool() {
        return new ReadTool<>("list_project_assets", "分页读取当前项目人物、场景、道具、关键图，可同时读取公共库。limit 每类最多 50。", AssetsInput.class, true) {
            public ToolResult execute(AssetsInput input, ToolContext context) {
                int limit = input.limit() == null ? 20 : Math.clamp(input.limit(), 1, 50);
                Long id = context.dramaId();
                var characterQuery = new LambdaQueryWrapper<ResCharacter>().and(q -> {
                    q.eq(ResCharacter::getDramaId, id); if (input.includePublic()) q.or().isNull(ResCharacter::getDramaId);
                }).orderByAsc(ResCharacter::getId).last("LIMIT " + limit);
                var sceneQuery = new LambdaQueryWrapper<ResScene>().and(q -> {
                    q.eq(ResScene::getDramaId, id); if (input.includePublic()) q.or().isNull(ResScene::getDramaId);
                }).orderByAsc(ResScene::getId).last("LIMIT " + limit);
                var propQuery = new LambdaQueryWrapper<ResProp>().and(q -> {
                    q.eq(ResProp::getDramaId, id); if (input.includePublic()) q.or().isNull(ResProp::getDramaId);
                }).orderByAsc(ResProp::getId).last("LIMIT " + limit);
                var keyframeQuery = new LambdaQueryWrapper<ResKeyframe>().and(q -> {
                    q.eq(ResKeyframe::getDramaId, id); if (input.includePublic()) q.or().isNull(ResKeyframe::getDramaId);
                }).orderByAsc(ResKeyframe::getId).last("LIMIT " + limit);
                return ToolResult.success(Map.of(
                        "characters", characters.selectList(characterQuery).stream().map(v -> new Asset(v.getId(), "CHARACTER", v.getName(), v.getAppearanceDesc(), v.getReferenceImageUrl(), v.getDramaId())).toList(),
                        "scenes", scenes.selectList(sceneQuery).stream().map(v -> new Asset(v.getId(), "SCENE", v.getName(), v.getDescription(), v.getReferenceImageUrl(), v.getDramaId())).toList(),
                        "props", props.selectList(propQuery).stream().map(v -> new Asset(v.getId(), "PROP", v.getName(), v.getDescription(), v.getCoverUrl(), v.getDramaId())).toList(),
                        "keyframes", keyframes.selectList(keyframeQuery).stream().map(v -> new Asset(v.getId(), "KEYFRAME", v.getName(), v.getDescription(), v.getFrameUrl(), v.getDramaId())).toList(),
                        "limitPerType", limit));
            }
        };
    }
    @Bean AgentTool<ShotInput> shotDetailTool() {
        return new ReadTool<>("get_shot_detail", "读取当前项目的一个分镜，shotId 是字符串形式的业务 ID。", ShotInput.class, true) {
            public ToolResult execute(ShotInput input, ToolContext context) {
                DramaShot shot = input.shotId() == null ? null : shots.selectById(input.shotId());
                if (shot == null) return ToolResult.error("NOT_FOUND", "分镜不存在");
                if (!Objects.equals(context.dramaId(), shot.getDramaId())) return ToolResult.error("SCOPE_VIOLATION", "分镜不属于当前项目");
                return ToolResult.success(shot);
            }
        };
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
                    loaded = steps.selectList(new LambdaQueryWrapper<AgentStep>().eq(AgentStep::getRunId, context.runId())
                            .eq(AgentStep::getToolName, "load_skill").eq(AgentStep::getStatus, "SUCCEEDED")).stream().anyMatch(step -> {
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
