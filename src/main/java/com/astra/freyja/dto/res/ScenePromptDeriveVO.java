package com.astra.freyja.dto.res;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 场景多模态专属提示词 AI 智能衍生响应 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenePromptDeriveVO {

    @JsonPropertyDescription("场景核心空间环境、氛围与光影质感一体化专属英文Prompt (仅描述空间格局、墙面地表顶棚材质、固定陈设及契合画风的光影质感，如 2D anime classroom, warm golden sunset casting long shadows across wooden desks)")
    private String scenePrompt;

    @Deprecated
    @JsonPropertyDescription("已废弃，统一整合进 scenePrompt 中，此字段返回 null")
    private String lightingPrompt;

    @JsonPropertyDescription("场景专属负向Prompt (规避与本场景时空冲突的特征，如 daylight, sunny, crowded, outdoor 等)")
    private String negativePrompt;
}
