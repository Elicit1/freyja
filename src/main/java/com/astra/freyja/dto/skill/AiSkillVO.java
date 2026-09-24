package com.astra.freyja.dto.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 技能视图展示对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSkillVO implements Serializable {

    private Long id;

    /** 唯一稳定标识，如 cinematography */
    private String name;

    /** 页面展示名称 */
    private String displayName;

    /** 标准 SKILL.md frontmatter description。 */
    private String description;

    /** 全局开关 0-停用 1-启用 */
    private Integer enabled;

    /** 当前生效版本 ID */
    private Long currentVersionId;

    /** 当前生效版本号，如 1.0.0 */
    private String currentVersion;

    /** 排序号 */
    private Integer sortOrder;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
