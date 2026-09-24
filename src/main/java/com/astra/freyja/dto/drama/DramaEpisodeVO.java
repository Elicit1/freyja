package com.astra.freyja.dto.drama;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 剧集展示 VO。
 */
@Data
public class DramaEpisodeVO {

    private Long id;
    private Long dramaId;
    private Integer episodeNo;
    private String title;
    private String summary;
    private String scriptContent;
    private Integer targetDuration;
    private BigDecimal actualDuration;
    private String status;
    private Integer sortOrder;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 统计指标 */
    private Integer sceneCount;
    private Integer shotCount;
    private Integer renderedShotCount;
}
