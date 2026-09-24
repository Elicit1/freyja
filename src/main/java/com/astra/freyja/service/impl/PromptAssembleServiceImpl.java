package com.astra.freyja.service.impl;

import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dao.ResCharacterOutfitMapper;
import com.astra.freyja.dao.ResSceneMapper;
import com.astra.freyja.dto.res.CharacterShotRefDTO;
import com.astra.freyja.dto.res.ControlImageVO;
import com.astra.freyja.dto.res.LoraItemVO;
import com.astra.freyja.dto.res.PromptAssembleRequestDTO;
import com.astra.freyja.dto.res.PromptAssembleResultVO;
import com.astra.freyja.dto.res.ResCharacterOutfitVO;
import com.astra.freyja.dto.res.ResCharacterVO;
import com.astra.freyja.dto.res.ResSceneVO;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.entity.ResCharacterOutfit;
import com.astra.freyja.entity.ResScene;
import com.astra.freyja.service.CharacterVisualAssetResolver;
import com.astra.freyja.service.PromptAssembleService;
import com.astra.freyja.service.SysConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PromptAssembleServiceImpl implements PromptAssembleService {

    private final ResSceneMapper sceneMapper;
    private final ResCharacterMapper characterMapper;
    private final ResCharacterOutfitMapper outfitMapper;
    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;
    private final CharacterVisualAssetResolver characterVisualAssetResolver;

    @Autowired
    public PromptAssembleServiceImpl(ResSceneMapper sceneMapper,
                                     ResCharacterMapper characterMapper,
                                     ResCharacterOutfitMapper outfitMapper,
                                     SysConfigService sysConfigService,
                                     ObjectMapper objectMapper,
                                     CharacterVisualAssetResolver characterVisualAssetResolver) {
        this.sceneMapper = sceneMapper;
        this.characterMapper = characterMapper;
        this.outfitMapper = outfitMapper;
        this.sysConfigService = sysConfigService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.characterVisualAssetResolver = characterVisualAssetResolver != null ? characterVisualAssetResolver
                : new CharacterVisualAssetResolverImpl(characterMapper, outfitMapper);
    }

    public PromptAssembleServiceImpl(ResSceneMapper sceneMapper,
                                     ResCharacterMapper characterMapper,
                                     ResCharacterOutfitMapper outfitMapper,
                                     SysConfigService sysConfigService,
                                     ObjectMapper objectMapper) {
        this(sceneMapper, characterMapper, outfitMapper, sysConfigService, objectMapper, null);
    }

    public PromptAssembleServiceImpl(ResSceneMapper sceneMapper,
                                     ResCharacterMapper characterMapper,
                                     ResCharacterOutfitMapper outfitMapper,
                                     SysConfigService sysConfigService) {
        this(sceneMapper, characterMapper, outfitMapper, sysConfigService, new ObjectMapper(), null);
    }

    private static final String DEFAULT_BASE_NEGATIVE =
            "(worst quality, low quality:1.4), (deformed, distorted, disfigured:1.3), poorly drawn, bad anatomy, wrong anatomy, extra limb, missing limb, floating limbs, (mutated hands and fingers:1.4), disconnected limbs, mutation, blurry, watermark, text, signature";

    @Override
    public PromptAssembleResultVO assemble(PromptAssembleRequestDTO request) {
        log.debug("[PromptAssemble] 收到组装请求: {}", request);

        List<String> positiveSegments = new ArrayList<>();
        List<String> negativeSegments = new ArrayList<>();
        List<ControlImageVO> controlImages = new ArrayList<>();

        // 1. 场景环境信息解析与注入 (纯净场景空间)
        ResSceneVO sceneSummary = null;
        if (request.getSceneId() != null && request.getSceneId() > 0) {
            ResScene scene = sceneMapper.selectById(request.getSceneId());
            if (scene != null) {
                sceneSummary = toSceneVO(scene);
                if (StringUtils.isNotBlank(scene.getScenePrompt())) {
                    positiveSegments.add(scene.getScenePrompt());
                } else if (StringUtils.isNotBlank(scene.getDescription())) {
                    positiveSegments.add(scene.getDescription());
                } else if (StringUtils.isNotBlank(scene.getName())) {
                    positiveSegments.add(scene.getName());
                }

                if (StringUtils.isNotBlank(scene.getSceneType()) && !"UNKNOWN".equalsIgnoreCase(scene.getSceneType().trim())) {
                    positiveSegments.add(scene.getSceneType().toLowerCase() + " scene");
                }
                if (StringUtils.isNotBlank(scene.getWeatherAtmosphere())) {
                    positiveSegments.add(scene.getWeatherAtmosphere() + " atmosphere");
                }
                if (StringUtils.isNotBlank(scene.getTimeOfDay()) && !"UNKNOWN".equalsIgnoreCase(scene.getTimeOfDay())) {
                    positiveSegments.add(scene.getTimeOfDay() + " time");
                }

                if (StringUtils.isNotBlank(scene.getNegativePrompt())) {
                    negativeSegments.add(scene.getNegativePrompt());
                }
                // 场景参考图
                if (StringUtils.isNotBlank(scene.getReferenceImageUrl())) {
                    controlImages.add(ControlImageVO.builder()
                            .controlType("SCENE_REF")
                            .imageUrl(scene.getReferenceImageUrl())
                            .weight(new BigDecimal("0.80"))
                            .label("场景参考: " + scene.getName())
                            .build());
                }
            }
        } else if (StringUtils.isNotBlank(request.getCustomScenePrompt())) {
            positiveSegments.add(request.getCustomScenePrompt());
        }

        // 3. 角色与造型变体信息解析与注入 (通过 CharacterVisualAssetResolver 统一收口)
        List<ResCharacterVO> characterSummaries = new ArrayList<>();
        if (request.getCharacterRefs() != null && !request.getCharacterRefs().isEmpty()) {
            for (CharacterShotRefDTO ref : request.getCharacterRefs()) {
                if (ref.getCharacterId() == null) {
                    continue;
                }
                Long lookId = ref.getLookId();
                com.astra.freyja.dto.res.ResolvedCharacterVisual visual;
                try {
                    visual = characterVisualAssetResolver.resolve(ref.getCharacterId(), lookId);
                } catch (Exception e) {
                    log.warn("Prompt组装时解析角色视觉资产异常: characterId={}, lookId={}, error={}", ref.getCharacterId(), lookId, e.getMessage());
                    continue;
                }
                if (visual == null) {
                    continue;
                }

                ResCharacterVO charVO = toCharacterVO(visual.getCharacter());
                if (visual.getOutfit() != null) {
                    charVO.setDefaultOutfit(toOutfitVO(visual.getOutfit()));
                }
                characterSummaries.add(charVO);

                // 拼装该角色的 Prompt 片段 (基础外貌 + 造型外貌 + 造型服装 + 站位/动作/情绪)
                List<String> charTokens = new ArrayList<>(visual.getPositivePromptSegments());
                // 位置/构图
                if (StringUtils.isNotBlank(ref.getPositionTag())) {
                    charTokens.add(ref.getPositionTag().trim());
                }
                // 动作行为
                if (StringUtils.isNotBlank(ref.getActionPrompt())) {
                    charTokens.add(ref.getActionPrompt().trim());
                }
                // 情绪表情
                if (StringUtils.isNotBlank(ref.getEmotionPrompt())) {
                    charTokens.add(ref.getEmotionPrompt().trim());
                }

                if (!charTokens.isEmpty()) {
                    positiveSegments.add(String.join(", ", charTokens));
                }

                // 角色与造型合并负向词
                if (visual.getNegativePromptSegments() != null) {
                    negativeSegments.addAll(visual.getNegativePromptSegments());
                }

                // 单一参考图注入 (有造型图优先 COMBINED，缺造型图回退 IDENTITY)
                if (StringUtils.isNotBlank(visual.getReferenceImageUrl())) {
                    String label = "COMBINED".equals(visual.getReferenceRole())
                            ? "造型参考: " + visual.getCharacter().getName() + " - " + (visual.getOutfit() != null ? visual.getOutfit().getLookName() : "造型")
                            : "人物参考: " + visual.getCharacter().getName();
                    controlImages.add(ControlImageVO.builder()
                            .controlType("CHARACTER_REF")
                            .imageUrl(visual.getReferenceImageUrl())
                            .weight(new BigDecimal("0.90"))
                            .label(label)
                            .build());
                }
            }
        }

        // 4. 连续性状态 (StartState) 彻底解耦：
        // 状态看板专供短剧一致性体检与 ControlNet 姿态/条件参考，严禁将 looking at, holding, at 等碎片机械拼入生图提示词

        // 5. 镜头运镜/构图词注入
        if (StringUtils.isNotBlank(request.getShotPrompt())) {
            positiveSegments.add(request.getShotPrompt());
        }

        // 6. 附加自定义正向词
        if (StringUtils.isNotBlank(request.getCustomPositivePrompt())) {
            positiveSegments.add(request.getCustomPositivePrompt());
        }

        // 6. 基础与自定义负向词注入
        String baseNegative = sysConfigService.getConfigValue("res.prompt.default_base_negative", DEFAULT_BASE_NEGATIVE);
        negativeSegments.add(0, baseNegative);
        if (StringUtils.isNotBlank(request.getCustomNegativePrompt())) {
            negativeSegments.add(request.getCustomNegativePrompt());
        }

        // 7. Prompt 清洗、分词去重与格式化 (首图 T2I 强制纯英文与静态化)
        String finalPositivePrompt = cleanAndNormalizePrompt(positiveSegments, true);
        String finalNegativePrompt = cleanAndNormalizePrompt(negativeSegments, false);

        // 8. 视频动态运镜提示词 (Video Dynamics / I2V Prompt) 组装
        List<String> videoSegments = new ArrayList<>();
        if (StringUtils.isNotBlank(request.getShotPrompt())) {
            videoSegments.add(request.getShotPrompt());
        }
        if (sceneSummary != null && StringUtils.isNotBlank(sceneSummary.getWeatherAtmosphere())) {
            videoSegments.add(sceneSummary.getWeatherAtmosphere() + " dynamics, realistic physics");
        }
        videoSegments.add("cinematic shot, smooth motion, high quality, 4k");
        String finalVideoPrompt = cleanAndNormalizePrompt(videoSegments, false);

        return PromptAssembleResultVO.builder()
                .positivePrompt(finalPositivePrompt)
                .videoPrompt(finalVideoPrompt)
                .negativePrompt(finalNegativePrompt)
                .loraList(Collections.emptyList())
                .controlImages(controlImages)
                .sceneSummary(sceneSummary)
                .characterSummaries(characterSummaries)
                .build();
    }

    private String resolveStylePreset(String preset) {
        if (StringUtils.isBlank(preset)) {
            return "cinematic shot, natural lighting";
        }
        return switch (preset.trim().toLowerCase()) {
            case "cinematic-realism", "realism" ->
                    "cinematic shot, photorealistic, 35mm photography, raw photo, realistic lighting, film grain";
            case "3d-animation", "3d" ->
                    "3d render, unreal engine 5, octane render, pixar style, smooth shading";
            case "anime-makoto", "anime" ->
                    "anime aesthetic, makoto shinkai style, vibrant colors, clear lighting";
            case "cyberpunk-neon", "cyberpunk" ->
                    "cyberpunk aesthetic, neon glow, wet reflections, volumetric lighting";
            default -> "cinematic shot, " + preset;
        };
    }

    private String cleanAndNormalizePrompt(List<String> segments, boolean isStaticT2I) {
        if (segments == null || segments.isEmpty()) {
            return "";
        }

        List<String> processedTokens = new ArrayList<>();

        for (String seg : segments) {
            if (StringUtils.isBlank(seg)) continue;
            String[] parts = seg.split("[,，\\n]+");
            for (String p : parts) {
                String clean = p.trim();
                if (StringUtils.isBlank(clean)) continue;

                String lower = clean.toLowerCase();

                // 1. 过滤占位符与脏词 (阻断 full body shot 与无效机械占位词)
                if (lower.equals("full body shot")
                        || lower.equals("looking at")
                        || lower.equals("looking at null") || lower.equals("looking at none") || lower.equals("looking at 闭合")
                        || lower.equals("holding none") || lower.equals("holding empty") || lower.equals("holding 无")
                        || lower.equals("at null") || lower.equals("at center")
                        || lower.equals("无") || lower.equals("none") || lower.equals("null") || lower.equals("empty")
                        || lower.equals("默认") || lower.equals("default") || lower.equals("standing") || lower.equals("站立")) {
                    continue;
                }

                // 2. 纯英文保障：剥离中文字符与全角标点 (保留英文字母、数字与半角标点)
                if (clean.matches(".*[\\u4e00-\\u9fa5]+.*")) {
                    clean = clean.replaceAll("[\\u4e00-\\u9fa5]+", "").replaceAll("[，。！？【】（）“”‘’：；]+", "").trim();
                    if (StringUtils.isBlank(clean)) {
                        continue;
                    }
                }

                processedTokens.add(clean);
            }
        }

        // 3. 短语大小写无关去重与实体包含消解
        List<String> deduplicated = deduplicateTokens(processedTokens);

        return String.join(", ", deduplicated);
    }

    private List<String> deduplicateTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> distinct = new ArrayList<>();
        Set<String> seenLower = new HashSet<>();
        for (String t : tokens) {
            String trimmed = t.replaceAll("^[,，\\s]+|[,，\\s]+$", "");
            if (StringUtils.isBlank(trimmed)) continue;
            String lower = trimmed.toLowerCase();
            if (seenLower.add(lower)) {
                distinct.add(trimmed);
            }
        }

        List<String> subsumed = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i++) {
            String current = distinct.get(i);
            String currentLower = current.toLowerCase();

            boolean isSubsumed = false;
            for (int j = 0; j < distinct.size(); j++) {
                if (i == j) continue;
                String otherLower = distinct.get(j).toLowerCase();
                if (otherLower.contains(currentLower) && otherLower.length() > currentLower.length()) {
                    isSubsumed = true;
                    break;
                }
            }
            if (!isSubsumed) {
                subsumed.add(current);
            }
        }

        return subsumed;
    }

    private ResSceneVO toSceneVO(ResScene scene) {
        ResSceneVO vo = new ResSceneVO();
        BeanUtils.copyProperties(scene, vo);
        return vo;
    }

    private ResCharacterVO toCharacterVO(ResCharacter character) {
        ResCharacterVO vo = new ResCharacterVO();
        BeanUtils.copyProperties(character, vo);
        return vo;
    }

    private ResCharacterOutfitVO toOutfitVO(ResCharacterOutfit outfit) {
        ResCharacterOutfitVO vo = new ResCharacterOutfitVO();
        BeanUtils.copyProperties(outfit, vo);
        return vo;
    }
}
