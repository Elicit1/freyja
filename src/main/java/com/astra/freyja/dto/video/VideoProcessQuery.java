package com.astra.freyja.dto.video;

import lombok.Data;

/**
 * 视频后处理任务分页查询参数。
 */
@Data
public class VideoProcessQuery {

    private Integer current = 1;
    private Integer size = 10;

    /** 操作类型: VIDEO_UPSCALE / FRAME_INTERPOLATION */
    private String operation;

    /** 任务状态: QUEUED, PROCESSING, SUCCESS, FAILED, CANCELLED */
    private String status;

    /** 关键字搜索 (taskId, modelCode) */
    private String keyword;

    /** 起始提交时间 (YYYY-MM-DD HH:mm:ss) */
    private String startTime;

    /** 截止提交时间 (YYYY-MM-DD HH:mm:ss) */
    private String endTime;
}
