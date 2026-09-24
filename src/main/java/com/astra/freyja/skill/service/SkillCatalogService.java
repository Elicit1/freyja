package com.astra.freyja.skill.service;

import com.astra.freyja.skill.model.SkillCatalogItem;

import java.util.List;

/**
 * AI 可见技能目录服务。
 * 仅提供已启用且具备当前标准 SKILL.md 版本的轻量元数据。
 */
public interface SkillCatalogService {

    List<SkillCatalogItem> getEnabledCatalog();

    /** 清除启用 Skill 目录缓存。 */
    void refreshCache();

    String formatCatalogForPrompt();

    /** 使用同一次查询得到的目录生成提示词，避免请求期间版本切换造成目录与快照不一致。 */
    default String formatCatalogForPrompt(List<SkillCatalogItem> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return "【可用 AI 技能目录】: 当前无启用的外部技能";
        }
        StringBuilder sb = new StringBuilder("【可用 AI 技能目录 (如需专业领域知识，可通过 Tool Calling 调用 load_skill(name) 按需加载，勿凭空捏造)】：\n");
        for (SkillCatalogItem item : catalog) {
            sb.append(String.format("- %s (v%s): %s\n", item.getName(), item.getVersion(),
                    org.apache.commons.lang3.StringUtils.defaultString(item.getDescription(), "无描述")));
        }
        return sb.toString();
    }
}
