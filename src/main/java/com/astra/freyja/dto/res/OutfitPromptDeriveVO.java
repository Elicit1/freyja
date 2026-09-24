package com.astra.freyja.dto.res;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色造型/服装变体提示词 AI 智能衍生响应 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutfitPromptDeriveVO {

    @JsonPropertyDescription("服饰装扮Prompt (英文，详尽描述服装款式、面料材质、剪裁层次、色彩纹理、配饰与穿戴细节)")
    private String outfitPrompt;

    @JsonPropertyDescription("附加视觉状态描述 (英文，如特定发型、妆容、伤痕、血迹、泥渍、战损或年龄变动特征，可选)")
    private String appearancePrompt;

    @JsonPropertyDescription("造型专属负向约束Prompt (英文，排除错误服装风格、现代杂物、违背造型设定的元素)")
    private String negativePrompt;
}
