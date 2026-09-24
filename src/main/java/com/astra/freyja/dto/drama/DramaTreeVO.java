package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 短剧四级大纲完整聚合树 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DramaTreeVO {

    private Long id;
    private String title;
    private String coverUrl;
    private String genre;
    private Integer targetEpisodes;
    private String aspectRatio;
    private String stylePreset;
    private String synopsis;
    private String status;

    /** 统计指标 */
    private Integer totalEpisodes;
    private Integer totalScenes;
    private Integer totalShots;
    private Integer renderedShots;
    private Integer progressPercentage;

    /** 剧集列表树 */
    private List<EpisodeTreeVO> episodes;
}
