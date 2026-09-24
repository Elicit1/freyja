package com.astra.freyja.dto.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 人物造型新增或修改请求 DTO。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResCharacterLookDTO {

    /** 造型ID，修改时必传 */
    private Long id;

    /** 归属人物ID，新增时必传 */
    private Long characterId;

    /** 造型名称 (如: 日常便服 / 晚礼服 / 战术装) */
    private String lookName;

    /** 是否默认造型 0-否 1-是 */
    private Integer isDefault;

    /** 造型唯一参考图URL (MinIO) */
    private String referenceImageUrl;

    /** 图片一致性状态：MISSING/SYNCED/STALE */
    private String imageStatus;

    /** 服饰装扮Prompt (英文) */
    private String outfitPrompt;

    /** 视觉状态类型 BASE/COSTUME/AGE_PHASE/DISGUISE/DAMAGE/CUSTOM */
    private String lookType;

    /** 造型视觉概念描述 (颜色/面料/轮廓/配饰/妆发) */
    private String designDesc;

    /** 非服装视觉状态描述，如妆发、年龄阶段、伤痕 */
    private String appearancePrompt;

    /** 造型专属负向约束 */
    private String negativePrompt;

    /** 服装专属LoRA (可选) */
    private String loraName;

    /** LoRA权重 */
    private BigDecimal loraWeight;

    /** 显示排序 */
    private Integer sortOrder;

    /** 状态 0-停用 1-启用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
