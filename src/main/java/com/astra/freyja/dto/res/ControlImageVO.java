package com.astra.freyja.dto.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 组装后提取的多模态控制图载荷（FaceID / 参考图 / ControlNet 等）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ControlImageVO {

    /** 控制类型：FACE_ID / TRIVIEW / SCENE_REF / OUTFIT_REF / CONTROLNET */
    private String controlType;

    /** 图片 URL */
    private String imageUrl;

    /** 影响权重 (0.0 ~ 1.0) */
    private BigDecimal weight;

    /** 关联的角色/场景名称 */
    private String label;
}
