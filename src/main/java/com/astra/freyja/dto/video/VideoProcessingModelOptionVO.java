package com.astra.freyja.dto.video;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 供工作台安全使用的模型选项及受控前端参数配置 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoProcessingModelOptionVO {

    /** 模型 ID (雪花 ID 字符串) */
    private String id;

    /** 所属提供商 ID (雪花 ID 字符串) */
    private String providerId;

    /** 模型代码快照 */
    private String modelCode;

    /** 模型展示名称 */
    private String modelName;

    /** 模型类型: VIDEO_UPSCALE / FRAME_INTERPOLATION */
    private String modelType;

    /** 排序号 */
    private Integer sortOrder;

    /** 受控前端配置范围与默认值 */
    private SafeModelConfig config;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SafeModelConfig {
        private Integer defaultScale;
        private List<Integer> allowedScales;
        private Integer defaultMultiplier;
        private List<Integer> allowedMultipliers;
        private Integer defaultCrf;
        private Integer minCrf;
        private Integer maxCrf;
        private Integer defaultClearCacheFrames;
        private Integer minClearCacheFrames;
        private Integer maxClearCacheFrames;
        private Boolean defaultPreserveAudio;
        private Integer defaultBatchSize;
    }
}
