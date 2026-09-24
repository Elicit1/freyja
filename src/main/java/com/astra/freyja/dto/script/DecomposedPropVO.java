package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 剧本拆解提取的核心道具信息 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecomposedPropVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonPropertyDescription("局部道具编号，例如: PR001, PR002")
    private String id;

    @JsonPropertyDescription("道具中文名称，例如: 斑驳大圆桌 / 繁复小座钟 / 老旧钨丝灯 / 黑色手提箱")
    private String name;

    @JsonPropertyDescription("道具类型: KEY_PROP(核心叙事道具), WEAPON(武器), COSTUME_ACCESSORY(服饰配饰), DAILY(日常杂物)")
    private String propType;

    @JsonPropertyDescription("道具特征与上下文作用描述")
    private String description;

    @JsonPropertyDescription("道具核心英文生图/视觉Prompt，例如: ornate antique brass desk clock with intricate engravings, ticking hands")
    private String propPrompt;

    @JsonPropertyDescription("若匹配到系统已有道具资产，回填对应已有道具ID，否则为null")
    private Long existingPropId;
}
