package com.astra.freyja.dto.drama;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短剧项目展示 VO。
 */
@Data
public class DramaVO {

    private Long id;
    private String title;
    private String coverUrl;
    private String genre;
    private Integer targetEpisodes;
    private String aspectRatio;
    private String stylePreset;
    private String styleTone;
    private String synopsis;
    private String status;
    private Integer sortOrder;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 统计字段：实际已创建集数 */
    private Integer episodeCount;

    /** 统计字段：实际总场次数 */
    private Integer sceneCount;

    /** 统计字段：实际总分镜数 */
    private Integer shotCount;

    /** 统计字段：已成功渲染分镜数 */
    private Integer renderedShotCount;

    /** 统计字段：生产进度百分比 (0-100) */
    private Integer progressPercentage;
}
