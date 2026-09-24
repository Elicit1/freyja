package com.astra.freyja.dto.video;

import lombok.Data;

/**
 * 视频源信息探测入参 DTO。
 */
@Data
public class VideoProcessProbeSourceDTO {

    /** 来源类型: DIRECT_URL / SHOT_CURRENT / SHOT_VIDEO_TAKE (默认 DIRECT_URL) */
    private String sourceType;

    /** 直接输入的视频 URL (DIRECT_URL 时使用) */
    private String sourceVideoUrl;

    /** 来源分镜 ID (SHOT_CURRENT / SHOT_VIDEO_TAKE 时使用) */
    private Long sourceShotId;

    /** 指定来源 Take ID (SHOT_VIDEO_TAKE 时使用) */
    private Long sourceVideoTakeId;
}
