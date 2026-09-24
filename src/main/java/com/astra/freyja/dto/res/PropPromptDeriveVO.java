package com.astra.freyja.dto.res;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 道具专属生图/视觉提示词 AI 智能衍生响应 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PropPromptDeriveVO {

    @JsonPropertyDescription("核心道具微观特写专属英文生图Prompt (恪守动静分离与纯净契约，仅描述道具自身微观造型、材质质感、雕花纹理、磨损细节与微距摄影光影，严禁带入房间大环境陈设，如 ornate antique brass desk clock with intricate floral engravings, macro photography, sharp focus)")
    private String propPrompt;

    @JsonPropertyDescription("道具生图专属负向Prompt (排除人手、人体部位、服装、杂乱背景、模糊、畸变等干扰特征，如 hands, fingers, human, body, person, clothing, complex background, text, watermark)")
    private String negativePrompt;
}
