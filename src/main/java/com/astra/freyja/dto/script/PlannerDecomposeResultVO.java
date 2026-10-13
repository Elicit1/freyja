package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Planner AI 章节大纲与剧情分段输出结果 (PlannerDecomposeResultVO)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlannerDecomposeResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 短剧/章节建议标题 */
    private String dramaTitle;

    /** 自由题材名称或已有字典值。 */
    @JsonPropertyDescription("题材自由文本或已有字典值，最长64字符；未收录题材填写自然语言名称，不编造枚举代码，已有项目继承原题材。")
    private String genre;

    /** 本章剧情总纲 */
    private String synopsis;

    /** 识别提取出的角色资产清单 (含外貌、造型与设定) */
    @Builder.Default
    private List<DecomposedCharacterVO> characters = new ArrayList<>();

    /** 识别提取出的场景资产清单 */
    @Builder.Default
    private List<DecomposedSceneVO> scenes = new ArrayList<>();

    /** 识别提取出的关键道具清单 */
    @Builder.Default
    private List<DecomposedPropVO> props = new ArrayList<>();

    /** 剧情分段列表 (StorySegments) */
    @Builder.Default
    private List<StorySegment> segments = new ArrayList<>();
}
