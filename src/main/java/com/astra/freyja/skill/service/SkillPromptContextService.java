package com.astra.freyja.skill.service;

import com.astra.freyja.skill.model.SkillPromptContext;
import com.astra.freyja.skill.tool.LoadSkillToolSession;

import java.util.List;
import java.util.Map;

/**
 * 为所有 AI 提示词生成入口提供统一的本地 Skill 快照注入能力。
 */
public interface SkillPromptContextService {

    /**
     * 仅展开用户明确选择的 Skill，用于 MANUAL/复制模式。
     */
    SkillPromptContext loadSelected(String consumer, String requestId, List<String> skillNames);

    /** 使用已经创建的任务版本快照展开 Skill，保证目录、正文和指纹属于同一版本。 */
    SkillPromptContext loadSelected(String consumer, String requestId, List<String> skillNames,
                                    Map<String, Long> versionSnapshot);

    /**
     * 创建一次 API 任务的目录版本快照，并预加载用户明确要求的 Skill。
     * 其他 Skill 只会在 load_skill 被调用时读取正文。
     */
    LoadSkillToolSession createApiSession(String consumer, String requestId, List<String> requiredSkillNames);

    /** 仅向 API 模式追加 Skill name/description/version 目录，不读取 Skill 正文。 */
    String appendCatalogToSystemPrompt(String systemPrompt);

    String appendToSystemPrompt(String systemPrompt, SkillPromptContext context);
}
