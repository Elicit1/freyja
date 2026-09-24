package com.astra.freyja.entity.enums;

/**
 * 视频后处理任务源视频类型枚举。
 */
public enum VideoProcessSourceType {

    /** 直接输入视频 URL (MinIO 或网络源地址) */
    DIRECT_URL("直接输入URL"),

    /** 分镜当前视频 (取自 drama_shot.video_url / 当前 Take) */
    SHOT_CURRENT("分镜当前视频"),

    /** 分镜指定历史 Take (取自 drama_shot_video_take) */
    SHOT_VIDEO_TAKE("分镜指定Take");

    private final String description;

    VideoProcessSourceType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
