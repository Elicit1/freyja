package com.astra.freyja.dto.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 角色身份证据响应 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResCharacterEvidenceVO {

    private Long id;

    private Long dramaId;

    private Long characterId;

    private Long episodeId;

    private Long sceneId;

    /** 剧本证据原文 */
    private String sourceText;

    /** 证据类型 */
    private String evidenceType;

    /** 依据置信度 */
    private BigDecimal confidence;

    /** 推理判断说明 */
    private String reason;

    private Integer status;

    private LocalDateTime createTime;
}
