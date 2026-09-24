package com.astra.freyja.skill.model;

import java.util.List;

/**
 * 一次提示词生成请求使用的本地 Skill 快照。
 *
 * <p>prompt 保存完整的标准 SKILL.md 正文，fingerprint 用于任务包指纹和结果追溯，
 * skillNames 用于日志与调用链审计。</p>
 */
public record SkillPromptContext(
        String prompt,
        String fingerprint,
        List<String> skillNames
) {

    public SkillPromptContext {
        prompt = prompt == null ? "" : prompt;
        fingerprint = fingerprint == null ? "none" : fingerprint;
        skillNames = skillNames == null ? List.of() : List.copyOf(skillNames);
    }

    public static SkillPromptContext empty() {
        return new SkillPromptContext("", "none", List.of());
    }

    public boolean hasSkills() {
        return !skillNames.isEmpty();
    }
}
