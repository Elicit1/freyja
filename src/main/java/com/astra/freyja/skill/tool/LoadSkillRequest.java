package com.astra.freyja.skill.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * load_skill 工具入参。
 * 严格只接受 skill 名称，拒绝任意路径或 URL。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoadSkillRequest implements Serializable {

    @JsonProperty(required = true)
    @JsonPropertyDescription("要按需加载的 AI 技能唯一名称标识，如 cinematography")
    private String name;
}
