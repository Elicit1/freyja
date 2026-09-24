package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dto.video.ResolvedVideoProcessingModel;
import com.astra.freyja.dto.video.VideoProcessingModelOptionVO;
import com.astra.freyja.dto.video.VideoProcessingModelParams;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.service.VideoProcessingModelResolver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 视频后处理模型安全解析器实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoProcessingModelResolverImpl implements VideoProcessingModelResolver {

    private final AiModelMapper modelMapper;
    private final ObjectMapper objectMapper;

    @Override
    public ResolvedVideoProcessingModel resolve(
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
            Integer userBatchSize) {

        if (StringUtils.isBlank(operation)) {
            throw new BizException(400, "操作类型 operation 不能为空");
        }
        String op = operation.trim().toUpperCase();
        boolean isUpscale = "VIDEO_UPSCALE".equals(op);
        boolean isInterpolation = "FRAME_INTERPOLATION".equals(op);
        if (!isUpscale && !isInterpolation) {
            throw new BizException(400, "不支持的操作类型: " + op + " (仅支持 VIDEO_UPSCALE 与 FRAME_INTERPOLATION)");
        }

        // 1. 定位并获取 AiModel
        AiModel model = null;
        if (modelId != null) {
            model = modelMapper.selectById(modelId);
            if (model == null) {
                throw new BizException(404, "指定的 AI 模型不存在 (modelId=" + modelId + ")");
            }
            if (model.getStatus() == null || model.getStatus() != 1) {
                throw new BizException(400, "指定的 AI 模型已停用");
            }
            if (providerId != null && !providerId.equals(model.getProviderId())) {
                throw new BizException(400, "指定的模型不属于当前 AI 提供商");
            }
        } else if (StringUtils.isNotBlank(modelCode)) {
            if (providerId == null) {
                throw new BizException(400, "通过 modelCode 解析模型时必须提供 providerId");
            }
            List<AiModel> matchedList = modelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                    .eq(AiModel::getProviderId, providerId)
                    .eq(AiModel::getModelCode, modelCode.trim())
                    .eq(AiModel::getModelType, op)
                    .eq(AiModel::getStatus, 1));
            if (matchedList == null || matchedList.isEmpty()) {
                throw new BizException(404, "在指定提供商下未找到已启用且类型匹配的模型 (modelCode=" + modelCode + ", operation=" + op + ")");
            }
            model = matchedList.get(0);
        } else {
            throw new BizException(400, "必须指定 modelId 或 modelCode");
        }

        // 2. 校验模型类型与当前操作一致
        if (!op.equalsIgnoreCase(model.getModelType())) {
            throw new BizException(400, "模型类型 (" + model.getModelType() + ") 与当前操作类型 (" + op + ") 不匹配");
        }

        // 3. 解析 paramsJson.videoProcessing
        VideoProcessingModelParams params = parseVideoProcessingParams(model.getParamsJson());
        if (params == null) {
            throw new BizException(500, "模型 " + model.getModelName() + " 未配置 videoProcessing 参数");
        }
        if (StringUtils.isBlank(params.getEngineModel())) {
            throw new BizException(500, "模型配置缺少 engineModel 参数");
        }
        if (StringUtils.isBlank(params.getFilenamePrefix())) {
            throw new BizException(500, "模型配置缺少 filenamePrefix 参数");
        }

        // 4. 解析与边界校验参数
        int sourceFps = (userSourceFps != null && userSourceFps > 0) ? userSourceFps : 24;
        int targetFps;
        int scale = 2;
        int multiplier = 2;
        int crf;
        int clearCacheFrames = 100;

        if (isUpscale) {
            // 超分参数
            if (userScale != null && userScale > 0) {
                scale = userScale;
            } else if (params.getDefaultScale() != null && params.getDefaultScale() > 0) {
                scale = params.getDefaultScale();
            }
            if (params.getAllowedScales() != null && !params.getAllowedScales().isEmpty()) {
                if (!params.getAllowedScales().contains(scale)) {
                    throw new BizException(400, "超分倍率 " + scale + " 不在模型允许的范围内: " + params.getAllowedScales());
                }
            }

            targetFps = (userTargetFps != null && userTargetFps > 0) ? userTargetFps : sourceFps;

            if (userCrf != null) {
                crf = userCrf;
            } else if (params.getDefaultCrf() != null) {
                crf = params.getDefaultCrf();
            } else {
                crf = 16;
            }
            checkCrfRange(crf, params);
        } else {
            // 补帧参数
            if (userMultiplier != null && userMultiplier > 0) {
                multiplier = userMultiplier;
            } else if (params.getDefaultMultiplier() != null && params.getDefaultMultiplier() > 0) {
                multiplier = params.getDefaultMultiplier();
            }
            if (params.getAllowedMultipliers() != null && !params.getAllowedMultipliers().isEmpty()) {
                if (!params.getAllowedMultipliers().contains(multiplier)) {
                    throw new BizException(400, "补帧倍率 " + multiplier + " 不在模型允许的范围内: " + params.getAllowedMultipliers());
                }
            }

            targetFps = (userTargetFps != null && userTargetFps > 0) ? userTargetFps : sourceFps * multiplier;

            if (userCrf != null) {
                crf = userCrf;
            } else if (params.getDefaultCrf() != null) {
                crf = params.getDefaultCrf();
            } else {
                crf = 19;
            }
            checkCrfRange(crf, params);

            if (userClearCacheFrames != null && userClearCacheFrames > 0) {
                clearCacheFrames = userClearCacheFrames;
            } else if (params.getDefaultClearCacheFrames() != null && params.getDefaultClearCacheFrames() > 0) {
                clearCacheFrames = params.getDefaultClearCacheFrames();
            }
            if (params.getMinClearCacheFrames() != null && clearCacheFrames < params.getMinClearCacheFrames()) {
                throw new BizException(400, "显存清理帧数 " + clearCacheFrames + " 小于模型最小限制 " + params.getMinClearCacheFrames());
            }
            if (params.getMaxClearCacheFrames() != null && clearCacheFrames > params.getMaxClearCacheFrames()) {
                throw new BizException(400, "显存清理帧数 " + clearCacheFrames + " 超过模型最大限制 " + params.getMaxClearCacheFrames());
            }
        }

        // 5. 构造安全的 extra_body 白名单参数
        Map<String, Object> extraBody = new HashMap<>();
        if (isUpscale) {
            extraBody.put("upscale_model", params.getEngineModel());
            extraBody.put("force_rate", sourceFps);
            extraBody.put("frame_rate", targetFps);
            extraBody.put("crf", crf);
            extraBody.put("filename_prefix", params.getFilenamePrefix());
        } else {
            extraBody.put("force_rate", sourceFps);
            extraBody.put("multiplier", multiplier);
            extraBody.put("frame_rate", targetFps);
            extraBody.put("crf", crf);
            extraBody.put("clear_cache_after_n_frames", clearCacheFrames);
            Integer batchSize = userBatchSize != null ? userBatchSize : params.getDefaultBatchSize();
            if (batchSize != null) {
                extraBody.put("batch_size", batchSize);
            }
            extraBody.put("filename_prefix", params.getFilenamePrefix());
        }

        return ResolvedVideoProcessingModel.builder()
                .model(model)
                .modelId(model.getId())
                .modelCode(model.getModelCode())
                .modelName(model.getModelName())
                .params(params)
                .sourceFps(sourceFps)
                .targetFps(targetFps)
                .scale(scale)
                .multiplier(multiplier)
                .crf(crf)
                .clearCacheFrames(clearCacheFrames)
                .extraBody(extraBody)
                .build();
    }

    @Override
    public List<VideoProcessingModelOptionVO> listModelOptions(Long providerId, String operation) {
        if (providerId == null || StringUtils.isBlank(operation)) {
            return Collections.emptyList();
        }
        String op = operation.trim().toUpperCase();
        List<AiModel> list = modelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, providerId)
                .eq(AiModel::getModelType, op)
                .eq(AiModel::getStatus, 1)
                .orderByAsc(AiModel::getSortOrder));

        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }

        List<VideoProcessingModelOptionVO> result = new ArrayList<>();
        for (AiModel m : list) {
            VideoProcessingModelParams p = parseVideoProcessingParams(m.getParamsJson());
            if (p == null) {
                p = new VideoProcessingModelParams();
            }

            VideoProcessingModelOptionVO.SafeModelConfig config = VideoProcessingModelOptionVO.SafeModelConfig.builder()
                    .defaultScale(p.getDefaultScale())
                    .allowedScales(p.getAllowedScales() != null ? p.getAllowedScales() : ("VIDEO_UPSCALE".equals(op) ? List.of(2, 4) : null))
                    .defaultMultiplier(p.getDefaultMultiplier())
                    .allowedMultipliers(p.getAllowedMultipliers() != null ? p.getAllowedMultipliers() : ("FRAME_INTERPOLATION".equals(op) ? List.of(2, 4) : null))
                    .defaultCrf(p.getDefaultCrf() != null ? p.getDefaultCrf() : ("VIDEO_UPSCALE".equals(op) ? 16 : 19))
                    .minCrf(p.getMinCrf() != null ? p.getMinCrf() : 10)
                    .maxCrf(p.getMaxCrf() != null ? p.getMaxCrf() : 30)
                    .defaultClearCacheFrames(p.getDefaultClearCacheFrames() != null ? p.getDefaultClearCacheFrames() : 100)
                    .minClearCacheFrames(p.getMinClearCacheFrames() != null ? p.getMinClearCacheFrames() : 50)
                    .maxClearCacheFrames(p.getMaxClearCacheFrames() != null ? p.getMaxClearCacheFrames() : 300)
                    .defaultPreserveAudio(p.getDefaultPreserveAudio() != null ? p.getDefaultPreserveAudio() : true)
                    .defaultBatchSize(p.getDefaultBatchSize())
                    .build();

            result.add(VideoProcessingModelOptionVO.builder()
                    .id(String.valueOf(m.getId()))
                    .providerId(String.valueOf(m.getProviderId()))
                    .modelCode(m.getModelCode())
                    .modelName(m.getModelName())
                    .modelType(m.getModelType())
                    .sortOrder(m.getSortOrder())
                    .config(config)
                    .build());
        }
        return result;
    }

    private VideoProcessingModelParams parseVideoProcessingParams(String paramsJson) {
        if (StringUtils.isBlank(paramsJson)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(paramsJson);
            JsonNode vpNode = root.path("videoProcessing");
            if (vpNode.isObject()) {
                return objectMapper.treeToValue(vpNode, VideoProcessingModelParams.class);
            }
        } catch (Exception e) {
            log.warn("[VideoProcessingModelResolver] 解析 paramsJson 异常: {}", e.getMessage());
        }
        return null;
    }

    private void checkCrfRange(int crf, VideoProcessingModelParams params) {
        if (params.getMinCrf() != null && crf < params.getMinCrf()) {
            throw new BizException(400, "CRF 质量压缩参数 " + crf + " 小于模型设定的最小值 " + params.getMinCrf());
        }
        if (params.getMaxCrf() != null && crf > params.getMaxCrf()) {
            throw new BizException(400, "CRF 质量压缩参数 " + crf + " 超过模型设定的最大值 " + params.getMaxCrf());
        }
    }
}
