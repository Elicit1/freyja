package com.astra.freyja.skill.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

/** 读取当前版本 Skill 包中 references/ 下的文本文件。 */
@Data
public class ReadSkillFileRequest {

    @JsonProperty(required = true)
    @JsonPropertyDescription("当前请求中已加载的 Skill 名称，与 load_skill 的 name 一致")
    private String name;

    @JsonProperty(required = true)
    @JsonPropertyDescription("SKILL.md 指引的 Skill 包内 references/ 相对路径")
    private String path;
}
