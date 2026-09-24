package com.astra.freyja.dto.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 组装后提取的 LoRA 加载项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoraItemVO {

    /** LoRA 模型名称 */
    private String loraName;

    /** 模型权重 */
    private BigDecimal weight;

    /** 来源类型：CHARACTER / OUTFIT / SCENE / CUSTOM */
    private String source;

    /** 关联的资产名称（便于前端展示说明） */
    private String assetName;
}
