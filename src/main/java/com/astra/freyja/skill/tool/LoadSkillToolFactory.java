package com.astra.freyja.skill.tool;

import com.astra.freyja.skill.model.LoadedSkill;
import com.astra.freyja.skill.service.SkillContentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

/**
 * 生产只读 load_skill ToolCallback 的工程工厂。
 * 为单次导演规划请求创建绑定当前会话的只读工具实例，绝不作为全局工具泄露。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoadSkillToolFactory {

    private final SkillContentService skillContentService;

    public ToolCallback createTool(LoadSkillToolSession session) {
        return createTool(session, null);
    }

    public ToolCallback createTool(LoadSkillToolSession session, java.util.function.Consumer<String> stageListener) {
        return createTool(session, stageListener, null);
    }

    public ToolCallback createTool(LoadSkillToolSession session, java.util.function.Consumer<String> stageListener,
                                   java.util.function.Consumer<LoadSkillResponse> resultListener) {
        return FunctionToolCallback.builder("load_skill", (LoadSkillRequest request) -> {
            if (request == null || StringUtils.isBlank(request.getName())) {
                return report(LoadSkillResponse.builder()
                        .status("ERROR")
                        .message("必须提供要加载的技能名称 (name)")
                        .build(), resultListener);
            }

            String skillName = request.getName().trim().toLowerCase();
            if (stageListener != null) stageListener.accept("正在加载 Skill: " + skillName + "…\n");
            session.recordInvocation(skillName);
            log.info("[LoadSkillTool] 收到 Skill 调用: name={}, invocationNo={}",
                    skillName, session.getInvocationCount());

            // 1. 检查是否在本次会话中已重复加载
            if (session.hasLoaded(skillName)) {
                LoadedSkill existing = session.getLoaded(skillName);
                log.info("[LoadSkillTool] 技能 [{}] 在本次规划会话中已被加载过，返回轻量确认: loadedMarkdownFiles=[{}/SKILL.md]",
                        skillName, skillName);
                return report(LoadSkillResponse.builder()
                        .name(existing.getName())
                        .version(existing.getVersion())
                        .contentHash(existing.getContentHash())
                        .status("ALREADY_LOADED")
                        .message("该技能已在当前上下文中成功加载，请直接应用已获得的专业知识，无需重复调用")
                        .build(), resultListener);
            }

            // 2. 按需从数据库加载本次请求快照对应的 Markdown 正文。
            // 不回退到“当前最新版本”，避免目录与正文在一次任务中漂移。
            try {
                Long versionId = session.resolveVersion(skillName)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Skill [" + skillName + "] 不在本次任务的 Skill 目录版本快照中"));
                LoadedSkill loaded = skillContentService.loadSkillVersion(versionId);
                if (loaded == null) {
                    throw new IllegalStateException("Skill [" + skillName + "] 正文加载结果为空");
                }
                session.recordCall(skillName, loaded);
                if (stageListener != null) stageListener.accept("Skill 已加载: " + skillName + "；模型继续生成…\n");
                log.info("[LoadSkillTool] 成功加载技能: name={}, version={}, hash={}, loadedMarkdownFiles=[{}/SKILL.md]",
                        loaded.getName(), loaded.getVersion(), loaded.getContentHash(), loaded.getName());

                return report(LoadSkillResponse.builder()
                        .name(loaded.getName())
                        .version(loaded.getVersion())
                        .contentHash(loaded.getContentHash())
                        .content(loaded.getContent())
                        .status("SUCCESS")
                        .message("技能加载成功")
                        .build(), resultListener);
            } catch (Exception e) {
                if (stageListener != null) stageListener.accept("Skill 加载失败: " + skillName + "\n");
                log.warn("[LoadSkillTool] 加载技能 [{}] 失败: {}", skillName, e.getMessage());
                session.recordCall(skillName, null);
                return report(LoadSkillResponse.builder()
                        .name(skillName)
                        .status("ERROR")
                        .message("加载技能失败: " + e.getMessage())
                        .build(), resultListener);
            }
        })
        .description("按需动态加载指定的 AI 技能完整专业规范与决策知识 (如 cinematography)。当需要进行特定领域决策时调用此工具获取规则指南。参数仅接收技能名称 slug。")
        .inputType(LoadSkillRequest.class)
        .build();
    }

    private LoadSkillResponse report(LoadSkillResponse response,
                                     java.util.function.Consumer<LoadSkillResponse> listener) {
        if (listener != null) listener.accept(response);
        return response;
    }
}
