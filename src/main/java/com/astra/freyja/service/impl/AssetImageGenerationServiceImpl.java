package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dao.ResPropMapper;
import com.astra.freyja.dao.ResSceneMapper;
import com.astra.freyja.dto.drama.DramaShotFirstFrameDTO;
import com.astra.freyja.dto.res.AssetImageGenerationRequest;
import com.astra.freyja.dto.res.AssetImageGenerationVO;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.entity.ResCharacterOutfit;
import com.astra.freyja.entity.ResProp;
import com.astra.freyja.entity.ResScene;
import com.astra.freyja.service.AiImageApiService;
import com.astra.freyja.service.AssetImageGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetImageGenerationServiceImpl implements AssetImageGenerationService {
    private final AiImageApiService aiImageApiService;
    private final ResCharacterMapper characterMapper;
    private final com.astra.freyja.dao.ResCharacterOutfitMapper outfitMapper;
    private final ResSceneMapper sceneMapper;
    private final ResPropMapper propMapper;
    private final com.astra.freyja.service.RenderTaskService renderTaskService;
    private final Map<String, AssetImageGenerationVO> taskStore = new ConcurrentHashMap<>();

    @Override
    public AssetImageGenerationVO submit(AssetImageGenerationRequest request) {
        validate(request);
        String taskId = "ASSET_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 如果是造型生图且未显式指定参考图，自动注入人物身份参考图作为垫图/面部保持基准
        if (("CHARACTER_OUTFIT".equalsIgnoreCase(request.getTargetType()) || "OUTFIT".equalsIgnoreCase(request.getTargetType()))
                && (request.getReferenceImageUrls() == null || request.getReferenceImageUrls().isEmpty())) {
            ResCharacterOutfit outfit = outfitMapper.selectById(request.getTargetId());
            if (outfit != null && outfit.getCharacterId() != null) {
                ResCharacter character = characterMapper.selectById(outfit.getCharacterId());
                if (character != null && StringUtils.isNotBlank(character.getReferenceImageUrl())) {
                    request.setReferenceImageUrls(List.of(character.getReferenceImageUrl()));
                }
            }
        }

        Long requestedProviderId = request.getProviderId() != null ? Long.valueOf(request.getProviderId()) : null;
        com.astra.freyja.entity.AiProvider actualProvider = null;
        String actualModelCode = null;
        try {
            actualProvider = aiImageApiService.resolveProvider(requestedProviderId);
            DramaShotFirstFrameDTO tempDto = new DramaShotFirstFrameDTO();
            tempDto.setProviderId(actualProvider.getId());
            tempDto.setModelCode(request.getModelCode());
            actualModelCode = aiImageApiService.resolveModelCode(tempDto, actualProvider.getId());
        } catch (Exception e) {
            log.warn("[AssetImageGeneration] 预解析提供商与模型警告: {}", e.getMessage());
        }

        com.astra.freyja.dto.render.RenderTaskVO rTask = com.astra.freyja.dto.render.RenderTaskVO.builder()
                .taskId(taskId)
                .taskType("ASSET_IMAGE")
                .taskName(String.format("[%s资产 %s] 生图渲染", request.getTargetType(), request.getSlot()))
                .assetType(request.getTargetType())
                .assetId(request.getTargetId())
                .assetSlot(request.getSlot())
                .prompt(request.getPrompt())
                .negativePrompt(request.getNegativePrompt())
                .providerId(actualProvider != null ? actualProvider.getId() : requestedProviderId)
                .providerName(actualProvider != null ? actualProvider.getProviderName() : null)
                .modelCode(actualModelCode != null ? actualModelCode : request.getModelCode())
                .status("RENDERING")
                .progress(25)
                .currentNode("AI生图模型运算中...")
                .build();
        renderTaskService.createTask(rTask);

        DramaShotFirstFrameDTO api = new DramaShotFirstFrameDTO();
        api.setTaskId(taskId);
        api.setProviderId(rTask.getProviderId());
        api.setModelCode(rTask.getModelCode());
        api.setSize(StringUtils.defaultIfBlank(request.getSize(), "1024x1024"));
        api.setSeed(request.getSeed());
        api.setNegativePrompt(request.getNegativePrompt());
        api.setReferenceImageUrls(request.getReferenceImageUrls());

        com.astra.freyja.service.RenderTaskThreadRegistry.register(taskId, Thread.currentThread());
        String output;
        try {
            renderTaskService.updateProgress(taskId, 50, "正在生成资产图并持久化归档至 MinIO...");
            output = aiImageApiService.generateAndArchiveAssetImage(request.getTargetType(), request.getTargetId(),
                    request.getSlot(), request.getPrompt(), api);
            renderTaskService.finishTask(taskId, output, null);
        } catch (Exception e) {
            renderTaskService.failTask(taskId, e.getMessage(), org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace(e));
            AssetImageGenerationVO errVo = AssetImageGenerationVO.builder().taskId(taskId).mode("API")
                    .status("FAILED").errorMessage(e.getMessage()).build();
            taskStore.put(taskId, errVo);
            throw e;
        } finally {
            com.astra.freyja.service.RenderTaskThreadRegistry.unregister(taskId);
        }

        AssetImageGenerationVO result = AssetImageGenerationVO.builder().taskId(taskId).mode("API")
                .status("SUCCESS").progress(100).outputUrl(output).build();
        taskStore.put(taskId, result);
        return result;
    }

    @Override
    public AssetImageGenerationVO getTask(String taskId) {
        AssetImageGenerationVO result = taskStore.get(taskId);
        if (result == null) throw new BizException(404, "资产生图任务不存在: " + taskId);
        return result;
    }

    @Override
    public void apply(String targetType, Long targetId, String slot, String imageUrl) {
        if (targetId == null || StringUtils.isBlank(imageUrl)) throw new BizException(400, "资产和图片地址不能为空");
        if ("CHARACTER".equalsIgnoreCase(targetType)) {
            ResCharacter character = characterMapper.selectById(targetId);
            if (character == null) throw new BizException(404, "人物角色不存在");
            switch (slot.toUpperCase()) {
                case "REFERENCE", "AVATAR" -> character.setReferenceImageUrl(imageUrl);
                default -> throw new BizException(400, "不支持的角色图片槽位: " + slot);
            }
            characterMapper.updateById(character);
        } else if ("CHARACTER_OUTFIT".equalsIgnoreCase(targetType) || "OUTFIT".equalsIgnoreCase(targetType)) {
            ResCharacterOutfit outfit = outfitMapper.selectById(targetId);
            if (outfit == null) throw new BizException(404, "人物造型不存在");
            switch (slot.toUpperCase()) {
                case "REFERENCE", "PREVIEW" -> outfit.setReferenceImageUrl(imageUrl);
                default -> throw new BizException(400, "不支持的造型图片槽位: " + slot);
            }
            outfitMapper.updateById(outfit);
        } else if ("SCENE".equalsIgnoreCase(targetType)) {
            ResScene scene = sceneMapper.selectById(targetId);
            if (scene == null) throw new BizException(404, "场景不存在");
            switch (slot.toUpperCase()) {
                case "SCENE_COVER" -> scene.setCoverUrl(imageUrl);
                case "SCENE_REFERENCE" -> scene.setReferenceImageUrl(imageUrl);
                default -> throw new BizException(400, "不支持的场景图片槽位: " + slot);
            }
            sceneMapper.updateById(scene);
        } else if ("PROP".equalsIgnoreCase(targetType)) {
            ResProp prop = propMapper.selectById(targetId);
            if (prop == null) throw new BizException(404, "道具资产不存在");
            switch (slot.toUpperCase()) {
                case "PROP_COVER", "COVER" -> prop.setCoverUrl(imageUrl);
                default -> throw new BizException(400, "不支持的道具图片槽位: " + slot);
            }
            propMapper.updateById(prop);
        } else {
            throw new BizException(400, "不支持的资产类型: " + targetType);
        }
    }

    private void validate(AssetImageGenerationRequest request) {
        if (request == null || request.getTargetId() == null || StringUtils.isBlank(request.getTargetType())
                || StringUtils.isBlank(request.getSlot()) || StringUtils.isBlank(request.getPrompt())) {
            throw new BizException(400, "资产、图片槽位和生成提示词不能为空");
        }
    }
}
