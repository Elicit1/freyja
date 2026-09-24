package com.astra.freyja.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标准 Agent Skill 包经过安全校验后的内存表示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardSkillPackage {

    private StandardSkillMetadata metadata;

    /** 根目录入口文件 SKILL.md 的原始内容（包含 frontmatter）。 */
    private String entrypointContent;

    /** 规范化相对路径到文件字节内容的映射。 */
    @Builder.Default
    private Map<String, byte[]> files = new LinkedHashMap<>();

    /** 每个文件的 sha256:... 哈希。 */
    @Builder.Default
    private Map<String, String> fileHashes = new LinkedHashMap<>();

    /** 按路径和内容计算的整个 Skill 包哈希。 */
    private String packageHash;

    /** 解压后所有文件的总字节数。 */
    private long totalSize;
}
