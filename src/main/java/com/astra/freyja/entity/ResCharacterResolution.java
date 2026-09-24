package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 角色消歧未决项与人工决议表 res_character_resolution。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_character_resolution")
public class ResCharacterResolution extends BaseEntity {

    /** 归属短剧ID */
    private Long dramaId;

    /** 发生剧集ID */
    private Long episodeId;

    /** 发生场次ID */
    private Long sceneId;

    /** 待消歧提及称谓 (如: 那个女人) */
    private String sourceMention;

    /** 上下文剧本段落 */
    private String sourceText;

    /** 候选角色ID列表 JSON (如: "[101, 103]") */
    private String candidateCharacterIds;

    /** 消歧状态: UNRESOLVED, RESOLVED, IGNORED */
    private String resolutionStatus;

    /** 最终决议绑定的角色ID */
    private Long resolvedCharacterId;

    /** AI 评估置信度 0.00 ~ 1.00 */
    private BigDecimal confidence;

    /** AI 歧义分析理由 */
    private String reason;
}
