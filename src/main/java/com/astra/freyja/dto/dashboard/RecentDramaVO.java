package com.astra.freyja.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工作台首页最近活跃短剧项目 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentDramaVO implements Serializable {

    private Long id;
    private String title;
    private String genre;
    private String coverUrl;
    private String status;
    private Integer episodeCount;
    private Integer shotCount;
    private LocalDateTime updateTime;
}
