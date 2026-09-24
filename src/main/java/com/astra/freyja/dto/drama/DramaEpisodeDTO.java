package com.astra.freyja.dto.drama;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 剧集新增/更新请求 DTO。
 */
@Data
public class DramaEpisodeDTO {

    private Long id;

    /** 归属短剧 ID */
    private Long dramaId;

    /** 集数序号 (第N集) */
    private Integer episodeNo;

    /** 本集标题 */
    private String title;

    /** 本集剧情简介 */
    private String summary;

    /** 本集原始剧本文本 */
    private String scriptContent;

    /** 目标时长 (秒) */
    private Integer targetDuration;

    /** 累计分镜实际时长 (秒) */
    private BigDecimal actualDuration;

    /** 剧集状态 (DRAFT/SCRIPT_PARSED/SHOTS_READY/RENDERING/COMPLETED) */
    private String status;

    /** 显示排序 */
    private Integer sortOrder;

    /** 备注 */
    private String remark;
}
