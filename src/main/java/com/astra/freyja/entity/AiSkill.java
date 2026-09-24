package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 技能逻辑实体 (ai_skill)。
 * 作为 Skill 的逻辑聚合根，MySQL 为元数据与发布开关的事实来源。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_skill")
public class AiSkill extends BaseEntity {

    /** 唯一稳定标识，小写 slug，如 cinematography */
    private String name;

    /** 页面展示名称，如 电影摄影与分镜运镜 */
    private String displayName;

    /** 来自标准 SKILL.md frontmatter 的 description。 */
    private String description;

    /** 全局开关: 0-停用, 1-启用 */
    private Integer enabled;

    /** 当前发布的有效版本 ID (ai_skill_version.id) */
    private Long currentVersionId;

    /** 页面与目录排序序号 */
    private Integer sortOrder;
}
