package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 短剧剧集实体 drama_episode。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("drama_episode")
public class DramaEpisode extends BaseEntity {

    /** 归属短剧ID */
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
}
