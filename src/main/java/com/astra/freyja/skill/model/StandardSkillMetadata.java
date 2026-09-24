package com.astra.freyja.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent Skills 标准 SKILL.md frontmatter。
 *
 * <p>name 与 description 是标准必填字段，其余字段按标准原样保留，
 * 不把客户端私有的 skill.yaml 作为导入前提。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardSkillMetadata implements Serializable {

    private String name;

    private String description;

    private String license;

    private String compatibility;

    @Builder.Default
    private Map<String, String> metadata = new LinkedHashMap<>();

    /** 标准中的 allowed-tools 实验字段，按空格分隔。 */
    private String allowedTools;
}
