package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 大纲树中的剧集节点 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EpisodeTreeVO {

    private Long id;
    private Long dramaId;
    private Integer episodeNo;
    private String title;
    private String summary;
    private Integer targetDuration;
    private BigDecimal actualDuration;
    private String status;
    private Integer sortOrder;

    /** 统计指标 */
    private Integer sceneCount;
    private Integer shotCount;
    private Integer renderedShotCount;

    /** 子场次列表 */
    private List<SceneTreeVO> scenes;
}
