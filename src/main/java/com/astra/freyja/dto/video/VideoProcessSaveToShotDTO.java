package com.astra.freyja.dto.video;

import lombok.Data;

/**
 * 视频后处理任务产物保存为分镜历史请求 DTO。
 */
@Data
public class VideoProcessSaveToShotDTO {

    /** 目标分镜 ID (可选，为空时默认使用处理任务关联的 sourceShotId) */
    private Long shotId;

    /** 是否设为分镜当前生效视频 (默认为 true) */
    private Boolean setAsCurrent = true;
}
