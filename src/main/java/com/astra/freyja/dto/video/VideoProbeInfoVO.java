package com.astra.freyja.dto.video;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 视频元数据探测响应 VO。
 */
@Data
public class VideoProbeInfoVO {

    /** 视频宽度 (px) */
    private Integer width;

    /** 视频高度 (px) */
    private Integer height;

    /** 视频帧率 (FPS) */
    private Double fps;

    /** 视频时长 (秒) */
    private BigDecimal duration;

    /** 视频编码格式 (如 h264 / hevc) */
    private String videoCodec;

    /** 音频编码格式 (如 aac / mp3，为空表示无音频) */
    private String audioCodec;

    /** 是否包含音频轨道 */
    private Boolean hasAudio = false;

    /** 原始文件名或 URL */
    private String sourceUrl;
}
