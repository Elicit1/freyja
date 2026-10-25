package com.astra.freyja.dto.video;

import lombok.Data;

import java.util.List;

/**
 * AI 模型中心 ai_model.params_json 中 videoProcessing 配置对象。
 */
@Data
public class VideoProcessingModelParams {

    public static final int DEFAULT_BATCH_SIZE = 32;
    public static final int MIN_BATCH_SIZE = 1;
    public static final int MAX_BATCH_SIZE = 64;

    /** 底层工作流/网关加载的引擎模型文件 (如 RealESRGAN_x2plus.pth / rife49.pth) */
    private String engineModel;

    /** 超分默认倍率 (如 2) */
    private Integer defaultScale;

    /** 超分允许倍率列表 (如 [2, 4]) */
    private List<Integer> allowedScales;

    /** 补帧默认倍率 (如 2) */
    private Integer defaultMultiplier;

    /** 补帧允许倍率列表 (如 [2, 4]) */
    private List<Integer> allowedMultipliers;

    /** 默认画质压缩系数 (CRF) */
    private Integer defaultCrf;

    /** 最小允许 CRF */
    private Integer minCrf;

    /** 最大允许 CRF */
    private Integer maxCrf;

    /** 补帧默认显存清理间隔帧数 */
    private Integer defaultClearCacheFrames;

    /** 兼容旧配置字段，不再作为清理周期下限；周期仅要求正整数。 */
    private Integer minClearCacheFrames;

    /** 最大允许清理帧数 */
    private Integer maxClearCacheFrames;

    /** 默认是否保留音频 */
    private Boolean defaultPreserveAudio;

    /** 补帧默认批处理大小，未配置时沿用 RIFE 工作流的 32。 */
    private Integer defaultBatchSize;

    /** 补帧允许的最小批处理数量，默认 1。 */
    private Integer minBatchSize;

    /** 补帧允许的最大批处理数量，默认 64。 */
    private Integer maxBatchSize;

    /** 产物生成文件名前缀 (如 video_upscale/realesrgan_x2 / video_rife/rife_48fps) */
    private String filenamePrefix;
}
