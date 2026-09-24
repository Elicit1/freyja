package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 角色身份依据与消歧证据表 res_character_evidence。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_character_evidence")
public class ResCharacterEvidence extends BaseEntity {

    /** 归属短剧ID */
    private Long dramaId;

    /** 归属角色ID */
    private Long characterId;

    /** 证据所在剧集ID */
    private Long episodeId;

    /** 证据所在场次ID */
    private Long sceneId;

    /** 剧本证据原文 (如: “我叫林婉清”) */
    private String sourceText;

    /** 证据类型: FIRST_APPEARANCE, SELF_INTRODUCTION, EXPLICIT_NAME, EXPLICIT_REFERENCE, RELATIONSHIP, APPEARANCE_MATCH, CONTEXT_MATCH, SCENE_CONTINUITY, OTHER */
    private String evidenceType;

    /** 依据置信度 0.00 ~ 1.00 */
    private BigDecimal confidence;

    /** 推理判断说明 */
    private String reason;

    /** 状态 1-有效 0-无效 */
    private Integer status;
}
