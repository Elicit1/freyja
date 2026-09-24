package com.astra.freyja.dto.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色消歧未决项展示 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResCharacterResolutionVO {

    private Long id;

    private Long dramaId;

    private Long episodeId;

    private Long sceneId;

    /** 待消歧提及称谓 */
    private String sourceMention;

    /** 上下文剧本段落 */
    private String sourceText;

    /** 候选角色 ID 列表 */
    private List<Long> candidateCharacterIds;

    /** 候选角色概要信息 */
    private List<ResCharacterOptionVO> candidateCharacters;

    /** 消歧状态: UNRESOLVED, RESOLVED, IGNORED */
    private String resolutionStatus;

    /** 最终决议绑定的角色ID */
    private Long resolvedCharacterId;

    /** 决议绑定角色名称 */
    private String resolvedCharacterName;

    /** AI 评估置信度 */
    private BigDecimal confidence;

    /** AI 歧义分析理由 */
    private String reason;

    private LocalDateTime createTime;
}
