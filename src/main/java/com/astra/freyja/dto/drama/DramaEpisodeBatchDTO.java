package com.astra.freyja.dto.drama;

import lombok.Data;

/**
 * 剧集批量创建请求 DTO。
 */
@Data
public class DramaEpisodeBatchDTO {

    /** 归属短剧 ID */
    private Long dramaId;

    /** 起始集号 (如 1) */
    private Integer startEpisodeNo = 1;

    /** 创建集数数量 (如 10) */
    private Integer count = 10;

    /** 标题前缀 (如 "第") */
    private String titlePrefix = "第";

    /** 标题后缀 (如 "集") */
    private String titleSuffix = "集";

    /** 目标单集时长 (秒，默认 300) */
    private Integer targetDuration = 300;
}
