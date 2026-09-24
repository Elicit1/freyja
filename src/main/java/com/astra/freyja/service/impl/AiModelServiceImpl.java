package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.dto.AiModelDTO;
import com.astra.freyja.dto.AiModelQuery;
import com.astra.freyja.dto.video.VideoProcessingModelParams;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.service.AiModelFactory;
import com.astra.freyja.service.AiModelService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * AI 模型管理实现。
 */
@Service
@RequiredArgsConstructor
public class AiModelServiceImpl implements AiModelService {

    private static final String MODEL_CACHE_PREFIX = "freyja:ai:model:";

    private final AiModelMapper modelMapper;
    private final AiProviderMapper providerMapper;
    private final AiModelFactory aiModelFactory;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Page<AiModel> page(AiModelQuery query) {
        LambdaQueryWrapper<AiModel> wrapper = new LambdaQueryWrapper<AiModel>()
                .eq(query.getProviderId() != null, AiModel::getProviderId, query.getProviderId())
                .like(StringUtils.isNotBlank(query.getModelName()), AiModel::getModelName, query.getModelName())
                .eq(StringUtils.isNotBlank(query.getModelType()), AiModel::getModelType, query.getModelType())
                .eq(query.getStatus() != null, AiModel::getStatus, query.getStatus())
                .orderByAsc(AiModel::getProviderId)
                .orderByAsc(AiModel::getSortOrder);
        return modelMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
    }

    @Override
    public AiModel getById(Long id) {
        AiModel entity = modelMapper.selectById(id);
        if (entity == null) {
            throw new BizException("AI 模型不存在");
        }
        return entity;
    }

    @Override
    public List<AiModel> listByProviderId(Long providerId) {
        String key = MODEL_CACHE_PREFIX + providerId;
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof List<?>) {
            return castList(cached);
        }
        List<AiModel> list = modelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, providerId)
                .eq(AiModel::getStatus, 1)
                .orderByAsc(AiModel::getSortOrder));
        if (list == null) {
            list = Collections.emptyList();
        }
        redisTemplate.opsForValue().set(key, list);
        return list;
    }

    @Override
    public void create(AiModelDTO dto) {
        validate(dto);
        checkProvider(dto.getProviderId());
        checkUnique(dto.getProviderId(), dto.getModelCode(), null);
        AiModel entity = new AiModel();
        fill(entity, dto);
        modelMapper.insert(entity);
        evict(dto.getProviderId());
    }

    @Override
    public void update(AiModelDTO dto) {
        if (dto.getId() == null) {
            throw new BizException("主键不能为空");
        }
        validate(dto);
        AiModel existing = getById(dto.getId());
        if (dto.getProviderId() != null && !existing.getProviderId().equals(dto.getProviderId())) {
            checkProvider(dto.getProviderId());
        }
        Long providerId = dto.getProviderId() == null ? existing.getProviderId() : dto.getProviderId();
        checkUnique(providerId, dto.getModelCode(), dto.getId());
        existing.setProviderId(providerId);
        fill(existing, dto);
        modelMapper.updateById(existing);
        evict(providerId);
        evict(existing.getProviderId());
    }

    @Override
    public void delete(Long id) {
        AiModel existing = getById(id);
        modelMapper.deleteById(id);
        evict(existing.getProviderId());
    }

    private void fill(AiModel entity, AiModelDTO dto) {
        entity.setProviderId(dto.getProviderId());
        entity.setModelCode(dto.getModelCode());
        entity.setModelName(dto.getModelName());
        String modelType = StringUtils.isBlank(dto.getModelType()) ? "CHAT" : dto.getModelType();
        entity.setModelType(modelType);
        entity.setTemperature(dto.getTemperature());
        entity.setMaxTokens(dto.getMaxTokens());
        entity.setTopP(dto.getTopP());
        entity.setParamsJson(dto.getParamsJson());
        entity.setMaxImages(dto.getMaxImages());
        entity.setMaxAudios(dto.getMaxAudios());
        entity.setMaxVideos(dto.getMaxVideos());
        entity.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        entity.setStatus(dto.getStatus());
        entity.setRemark(dto.getRemark());
    }

    private void validate(AiModelDTO dto) {
        if (dto.getProviderId() == null) {
            throw new BizException("归属提供商不能为空");
        }
        if (StringUtils.isBlank(dto.getModelCode())) {
            throw new BizException("模型标识不能为空");
        }
        if (StringUtils.isBlank(dto.getModelName())) {
            throw new BizException("模型名称不能为空");
        }
        if (dto.getStatus() == null) {
            throw new BizException("状态不能为空");
        }

        String modelType = StringUtils.isBlank(dto.getModelType()) ? "CHAT" : dto.getModelType().trim().toUpperCase();
        if (isImageRefSupported(modelType)) {
            if (dto.getMaxImages() == null || dto.getMaxImages() < 0) {
                throw new BizException("图生图与图生视频类型模型必须配置最大参考图数，且必须大于等于0");
            }
        } else {
            // 只有图生图或图生视频类型的模型可以配置参考图、参考音频、参考视频数量
            dto.setMaxImages(null);
            dto.setMaxAudios(null);
            dto.setMaxVideos(null);
        }

        if (!isVideoModel(modelType)) {
            dto.setMaxAudios(null);
            dto.setMaxVideos(null);
        }

        if (isVideoProcessingModel(modelType)) {
            validateVideoProcessing(modelType, dto.getParamsJson());
        }
    }

    private boolean isVideoProcessingModel(String modelType) {
        return "VIDEO_UPSCALE".equalsIgnoreCase(modelType)
                || "FRAME_INTERPOLATION".equalsIgnoreCase(modelType);
    }

    private void validateVideoProcessing(String modelType, String paramsJson) {
        if (StringUtils.isBlank(paramsJson)) {
            throw new BizException("视频后处理模型必须配置 paramsJson.videoProcessing 参数");
        }
        try {
            JsonNode root = objectMapper.readTree(paramsJson);
            JsonNode vpNode = root.path("videoProcessing");
            if (!vpNode.isObject()) {
                throw new BizException("paramsJson 中必须包含合法的 videoProcessing 对象");
            }
            VideoProcessingModelParams vp = objectMapper.treeToValue(vpNode, VideoProcessingModelParams.class);
            if (vp == null) {
                throw new BizException("paramsJson.videoProcessing 解析失败");
            }
            if (StringUtils.isBlank(vp.getEngineModel())) {
                throw new BizException("videoProcessing.engineModel 不能为空 (例如 RealESRGAN_x2plus.pth 或 rife49.pth)");
            }
            if (StringUtils.isBlank(vp.getFilenamePrefix())) {
                throw new BizException("videoProcessing.filenamePrefix 不能为空");
            }
            if (vp.getMinCrf() != null && vp.getMaxCrf() != null) {
                if (vp.getMinCrf() > vp.getMaxCrf()) {
                    throw new BizException("videoProcessing.minCrf 不能大于 maxCrf");
                }
                if (vp.getDefaultCrf() != null) {
                    if (vp.getDefaultCrf() < vp.getMinCrf() || vp.getDefaultCrf() > vp.getMaxCrf()) {
                        throw new BizException("videoProcessing.defaultCrf 必须介于 minCrf 与 maxCrf 之间");
                    }
                }
            }
            if ("VIDEO_UPSCALE".equalsIgnoreCase(modelType)) {
                if (vp.getAllowedScales() != null && !vp.getAllowedScales().isEmpty() && vp.getDefaultScale() != null) {
                    if (!vp.getAllowedScales().contains(vp.getDefaultScale())) {
                        throw new BizException("videoProcessing.defaultScale 必须包含在 allowedScales 允许倍率列表中");
                    }
                }
            } else if ("FRAME_INTERPOLATION".equalsIgnoreCase(modelType)) {
                if (vp.getAllowedMultipliers() != null && !vp.getAllowedMultipliers().isEmpty() && vp.getDefaultMultiplier() != null) {
                    if (!vp.getAllowedMultipliers().contains(vp.getDefaultMultiplier())) {
                        throw new BizException("videoProcessing.defaultMultiplier 必须包含在 allowedMultipliers 列表中");
                    }
                }
                if (vp.getMinClearCacheFrames() != null && vp.getMaxClearCacheFrames() != null) {
                    if (vp.getMinClearCacheFrames() > vp.getMaxClearCacheFrames()) {
                        throw new BizException("videoProcessing.minClearCacheFrames 不能大于 maxClearCacheFrames");
                    }
                    if (vp.getDefaultClearCacheFrames() != null) {
                        if (vp.getDefaultClearCacheFrames() < vp.getMinClearCacheFrames() || vp.getDefaultClearCacheFrames() > vp.getMaxClearCacheFrames()) {
                            throw new BizException("videoProcessing.defaultClearCacheFrames 必须介于 minClearCacheFrames 与 maxClearCacheFrames 之间");
                        }
                    }
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("paramsJson 格式不合法: " + e.getMessage());
        }
    }

    private boolean isImageRefSupported(String modelType) {
        return "IMG2IMG".equalsIgnoreCase(modelType)
                || "TXT_IMG2IMG".equalsIgnoreCase(modelType)
                || "TXT2VIDEO_FIRST_LAST".equalsIgnoreCase(modelType)
                || "TXT2VIDEO_REF".equalsIgnoreCase(modelType);
    }

    private boolean isVideoModel(String modelType) {
        return "TXT2VIDEO_FIRST_LAST".equalsIgnoreCase(modelType)
                || "TXT2VIDEO_REF".equalsIgnoreCase(modelType);
    }

    private void checkProvider(Long providerId) {
        if (providerMapper.selectById(providerId) == null) {
            throw new BizException("AI 提供商不存在");
        }
    }

    private void checkUnique(Long providerId, String modelCode, Long excludeId) {
        LambdaQueryWrapper<AiModel> wrapper = new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, providerId)
                .eq(AiModel::getModelCode, modelCode)
                .ne(excludeId != null, AiModel::getId, excludeId);
        Long count = modelMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BizException("该提供商下模型标识已存在");
        }
    }

    private void evict(Long providerId) {
        redisTemplate.delete(MODEL_CACHE_PREFIX + providerId);
        aiModelFactory.evict(providerId);
    }

    @SuppressWarnings("unchecked")
    private List<AiModel> castList(Object cached) {
        return (List<AiModel>) cached;
    }
}