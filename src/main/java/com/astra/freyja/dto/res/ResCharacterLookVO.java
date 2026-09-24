package com.astra.freyja.dto.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 人物造型响应 VO。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResCharacterLookVO {

    private Long id;

    private Long characterId;

    /** 造型名称 */
    private String lookName;

    private Integer isDefault;

    /** 造型唯一参考图URL (MinIO) */
    private String referenceImageUrl;

    /** 图片一致性状态：MISSING/SYNCED/STALE */
    private String imageStatus;

    /** 造型 Prompt 当前版本 */
    private Integer visualVersion;

    /** 图片生成时对应的版本 */
    private Integer imageVisualVersion;

    private String outfitPrompt;

    private String lookType;

    /** 造型视觉概念描述 (颜色/面料/轮廓/配饰/妆发) */
    private String designDesc;

    private String appearancePrompt;

    private String negativePrompt;

    private String loraName;

    private BigDecimal loraWeight;

    private Integer sortOrder;

    private Integer status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
