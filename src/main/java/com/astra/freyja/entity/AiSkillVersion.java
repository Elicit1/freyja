package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 技能版本实体 (ai_skill_version)。
 * 每个版本是一份不可变的标准 SKILL.md 与 Skill 包快照，数据库是入口正文的事实来源。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_skill_version")
public class AiSkillVersion extends BaseEntity {

    /** 归属逻辑 Skill ID */
    private Long skillId;

    /** 服务端递增版本号，从 1 开始。 */
    private Integer revisionNo;

    /** 标准 SKILL.md 原文（包含 YAML frontmatter）。 */
    private String content;

    /** 整个标准 Skill 包的 SHA-256 哈希值。 */
    private String contentHash;

    /** 整个 Skill 包解压后的总字节数。 */
    private Long contentSize;

}
