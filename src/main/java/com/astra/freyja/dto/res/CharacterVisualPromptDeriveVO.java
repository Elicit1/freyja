package com.astra.freyja.dto.res;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色身份层视觉提示词 AI 智能衍生响应 VO。
 * 聚焦跨造型稳定的生物与视觉特征（骨相、发型、五官、体态、气质），严禁包含具体服装或镜头构图。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharacterVisualPromptDeriveVO {

    @JsonPropertyDescription("角色跨造型稳定的身份外貌特征Prompt (英文，专注五官、骨相、发型发色、体型体态、气质与材质质感，严禁包含具体服装、饰品或镜头构图)")
    private String appearancePrompt;

    @JsonPropertyDescription("角色身份级全局负向约束Prompt (英文，排除畸变、低质感及违背角色生物身份的特征)")
    private String negativePrompt;
}
