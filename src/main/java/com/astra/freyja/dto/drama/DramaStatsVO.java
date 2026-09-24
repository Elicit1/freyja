package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 短剧整体制作统计看板 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DramaStatsVO {

    private Long dramaId;
    private String title;
    private Integer targetEpisodes;
    private Integer actualEpisodes;
    private Integer totalScenes;
    private Integer totalShots;
    private Integer renderedShots;
    private Integer renderingShots;
    private Integer failedShots;
    private Integer initShots;
    private Integer progressPercentage;
    private BigDecimal totalEstimatedDuration;
}
