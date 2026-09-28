package com.astra.freyja.dto.drama;

import lombok.Data;

/**
 * 分镜镜头轻量级下拉选项 VO (供资源库关键帧、视频处理等模块使用)。
 */
@Data
public class DramaShotOptionVO {

    /** 分镜ID */
    private Long id;

    /** 归属短剧ID */
    private Long dramaId;

    /** 归属剧集ID */
    private Long episodeId;

    /** 归属场次ID */
    private Long sceneId;

    /** 镜头序号 (如 1, 2, 3) */
    private Integer shotNo;

    /** 镜头标识 (如: S01-01) */
    private String shotName;

    /** 动作与画面描述 */
    private String actionDescription;

    /** 预览图URL */
    private String previewImageUrl;
}
