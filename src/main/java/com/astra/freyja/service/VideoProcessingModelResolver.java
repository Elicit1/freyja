package com.astra.freyja.service;

import com.astra.freyja.dto.video.ResolvedVideoProcessingModel;
import com.astra.freyja.dto.video.VideoProcessingModelOptionVO;

import java.util.List;

/**
 * 视频后处理模型安全解析器接口。
 */
public interface VideoProcessingModelResolver {

    /**
     * 解析并校验用于视频后处理的模型配置及构造安全的 extra_body 参数
     */
    default ResolvedVideoProcessingModel resolve(
            Long providerId,
            Long modelId,
            String modelCode,
            String operation,
            Integer userScale,
            Integer userMultiplier,
            Integer userCrf,
            Integer userSourceFps,
            Integer userTargetFps,
            Integer userClearCacheFrames) {
        return resolve(providerId, modelId, modelCode, operation, userScale, userMultiplier, userCrf,
                userSourceFps, userTargetFps, userClearCacheFrames, null);
    }

    /**
     * 解析并校验用于视频后处理的模型配置及构造安全的 extra_body 参数 (支持可选 batchSize)
     */
    ResolvedVideoProcessingModel resolve(
            Long providerId,
            Long modelId,
            String modelCode,
            String operation,
            Integer userScale,
            Integer userMultiplier,
            Integer userCrf,
            Integer userSourceFps,
            Integer userTargetFps,
            Integer userClearCacheFrames,
            Integer userBatchSize);

    /**
     * 查询指定提供商和操作类型的可用模型选项 (供工作台安全范围配置使用)
     */
    List<VideoProcessingModelOptionVO> listModelOptions(Long providerId, String operation);
}
