package com.astra.freyja.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 通过 load_skill 按需加载的 Skill 完整结构。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoadedSkill implements Serializable {

    /** 技能唯一标识，如 cinematography */
    private String name;

    /** 加载的递增版本号，如 1、2、3。 */
    private String version;

    /** 版本 SHA-256 哈希值 */
    private String contentHash;

    /** SKILL.md 完整正文（包含 YAML frontmatter 的 Markdown 纯文本）。 */
    private String content;

}
