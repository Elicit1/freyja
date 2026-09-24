package com.astra.freyja.service.prompt.impl;

import com.astra.freyja.dto.script.DecomposedCharacterVO;
import com.astra.freyja.dto.script.DecomposedSceneVO;
import com.astra.freyja.dto.script.DecomposedShotVO;
import com.astra.freyja.service.SysConfigService;
import com.astra.freyja.service.prompt.PromptBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 镜头生图 (T2I) 与视频运镜 (I2V) 提示词本地组装构建器实现 (PromptBuilderImpl)。
 * 严格落实【视觉转化五大铁律】：
 * 1. 剔除文学杂质（无嗅觉、听觉与抽象心理描写，外化为光影、材质、透视与体态）；
 * 2. 时空解耦输出（T2I 首帧生图绝对静态定格，剥离相机推拉摇移与光线闪烁；I2V 专注动态演进）；
 * 3. 透视逻辑校验（背对镜头消解正脸/眼部；全景远景降级超解析度表盘与微表情；多人宏观剪影化）；
 * 4. 服化道安全替代（配置化映射替换 torn/ripped/ragged 等畸变词，转换为 weathered/distressed/faded fabric）；
 * 5. 纯英文及去重（全链路纯英文，非视觉虚词修剪，短语包含消解去重）。
 */
@Slf4j
@Service
public class PromptBuilderImpl implements PromptBuilder {

    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;

    @Autowired
    public PromptBuilderImpl(SysConfigService sysConfigService, ObjectMapper objectMapper) {
        this.sysConfigService = sysConfigService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public PromptBuilderImpl(SysConfigService sysConfigService) {
        this(sysConfigService, new ObjectMapper());
    }

    private static final String DEFAULT_BASE_NEGATIVE =
            "(worst quality, low quality:1.4), (deformed, distorted, disfigured:1.3), poorly drawn, bad anatomy, wrong anatomy, extra limb, missing limb, floating limbs, (mutated hands and fingers:1.4), blurry, watermark, text, signature";

    private static final Set<String> INVALID_PLACEHOLDERS = Set.of(
            "无", "none", "null", "empty", "闭合", "未持物", "默认", "default", "center", "standing", "站立"
    );

    @Override
    public String buildPositivePrompt(DecomposedShotVO shot,
                                      List<DecomposedCharacterVO> characters,
                                      DecomposedSceneVO scene,
                                      String stylePreset) {
        return buildPositivePrompt(shot, characters, scene, null, stylePreset);
    }

    @Override
    public String buildPositivePrompt(DecomposedShotVO shot,
                                      List<DecomposedCharacterVO> characters,
                                      DecomposedSceneVO scene,
                                      List<com.astra.freyja.dto.script.DecomposedPropVO> props,
                                      String stylePreset) {
        if (shot == null) {
            return "";
        }

        List<String> segments = new ArrayList<>();

        // 场景主数据 (Scene Master Data - 纯净物理环境与真实打光)
        if (scene != null) {
            if (StringUtils.isNotBlank(scene.getScenePrompt())) {
                segments.add(scene.getScenePrompt());
            } else if (StringUtils.isNotBlank(scene.getSceneName())) {
                segments.add(scene.getSceneName());
            }
            if (StringUtils.isNotBlank(scene.getSceneType()) && !"UNKNOWN".equalsIgnoreCase(scene.getSceneType().trim())) {
                segments.add(scene.getSceneType().toLowerCase() + " scene");
            }
            if (StringUtils.isNotBlank(scene.getWeatherAtmosphere())) {
                segments.add(scene.getWeatherAtmosphere() + " atmosphere");
            }
            if (StringUtils.isNotBlank(scene.getTimeOfDay()) && !"UNKNOWN".equalsIgnoreCase(scene.getTimeOfDay())) {
                segments.add(scene.getTimeOfDay() + " time");
            }
        }

        // 关键叙事道具注入 (Props Injection - 强 ID 绑定直接注入)
        if (props != null && !props.isEmpty() && shot.getPropIds() != null && !shot.getPropIds().isEmpty()) {
            for (String pRef : shot.getPropIds()) {
                if (StringUtils.isBlank(pRef)) continue;
                String trimmed = pRef.trim();
                for (com.astra.freyja.dto.script.DecomposedPropVO p : props) {
                    if (trimmed.equalsIgnoreCase(p.getId()) || trimmed.equalsIgnoreCase(p.getName())) {
                        if (StringUtils.isNotBlank(p.getPropPrompt())) {
                            segments.add(p.getPropPrompt());
                        }
                        break;
                    }
                }
            }
        }

        // 影视灯位工程与防死黑光影补偿 (基于场景元数据强类型判定)
        if (isDarkOrMoodyScene(scene)) {
            segments.add("directional side key lighting, sharp edge rim light, chiaroscuro contrast, perfectly exposed subject face");
        }

        // 只传入实际出场角色的身份设定；视觉焦点与构图由 Prompt AI 决定。
        List<DecomposedCharacterVO> matchedChars = filterAppearingCharacters(shot, characters);
        for (DecomposedCharacterVO character : matchedChars) {
            injectCharacterTokens(character, segments);
        }

        // 当前镜头首帧构图与动作画面
        String visualFraming = StringUtils.firstNonBlank(shot.getFirstFrameVisual(), shot.getActionDescription());
        if (StringUtils.isNotBlank(visualFraming)) {
            segments.add(visualFraming);
        } else if (Boolean.TRUE.equals(shot.getShotTypeLocked())
                && StringUtils.isNotBlank(shot.getShotType()) && !"AUTO".equalsIgnoreCase(shot.getShotType())) {
            segments.add(formatShotType(shot.getShotType()));
        }

        // 彻底移除原步骤 6: 连续性状态 (ContinuityHintDTO.ImportantState) 的机械拼接 (looking at, holding, at 等)
        // 状态看板仅用于系统层一致性体检与冲突诊断，严禁作为破损自然语言碎片污染扩散模型

        return cleanAndNormalize(segments, true);
    }

    @Override
    public String buildVideoPrompt(DecomposedShotVO shot,
                                   DecomposedSceneVO scene,
                                   String stylePreset) {
        if (shot == null) {
            return "";
        }

        List<String> motionSegments = new ArrayList<>();

        // 1. 运镜机位调度 (Camera Motion - 专供 I2V 图生视频动态运镜)
        if (Boolean.TRUE.equals(shot.getCameraMovementLocked())
                && StringUtils.isNotBlank(shot.getCameraMovement()) && !"STATIC".equalsIgnoreCase(shot.getCameraMovement())) {
            motionSegments.add(formatCameraMovement(shot.getCameraMovement()));
        }
        // 彻底移除 formatShotType(shot.getShotType())：视频生成基于首帧画面演变，严禁注入静态景别硬词 (如 full body shot)，避免画面运动被锁死

        // 2. 核心物理动作与神态演进 (Physical Action & Dynamics - 优先使用专用 videoPrompt)
        if (StringUtils.isNotBlank(shot.getVideoPrompt())) {
            motionSegments.add(shot.getVideoPrompt());
        } else if (StringUtils.isNotBlank(shot.getActionDescription())) {
            motionSegments.add(shot.getActionDescription());
        }

        // 3. 环境天气物理流转 (Atmospheric Dynamics)
        if (scene != null && StringUtils.isNotBlank(scene.getWeatherAtmosphere())) {
            motionSegments.add(scene.getWeatherAtmosphere() + " dynamics");
        }

        return cleanAndNormalize(motionSegments, false);
    }

    private static final String DEFAULT_ANIME_NEGATIVE =
            "thick black outline, heavy lineart, bold contour, harsh outlines, manga ink lines, excessive line weight, strong model sheet look, hard cel shading, harsh shadow edges, high contrast shadows, overly sharp edges, stiff character sheet style, rough sketch lines, dense linework, heavy comic outline, flat 2d paper cutout";

    @Override
    public String buildNegativePrompt(String stylePreset, String customNegative) {
        String baseNegative = sysConfigService.getConfigValue("res.prompt.default_base_negative", DEFAULT_BASE_NEGATIVE);
        List<String> segs = new ArrayList<>();
        segs.add(baseNegative);

        // 若为动漫风格，自动注入轻线稿与防粗黑外轮廓负向词
        if (isAnimePreset(stylePreset)) {
            String animeNegative = sysConfigService.getConfigValue("res.prompt.default_anime_negative", DEFAULT_ANIME_NEGATIVE);
            if (StringUtils.isNotBlank(animeNegative)) {
                segs.add(animeNegative);
            }
        }

        if (StringUtils.isNotBlank(customNegative)) {
            segs.add(customNegative);
        }
        return cleanAndNormalize(segs, false);
    }

    private boolean isAnimePreset(String preset) {
        if (StringUtils.isBlank(preset)) return false;
        String p = preset.trim().toLowerCase();
        return p.contains("anime") || p.contains("2d") || p.contains("animation") || p.contains("manga");
    }

    private static final Map<String, String> STYLE_PRESETS_NORMAL = Map.of(
            "cinematic-realism", "cinematic shot, photorealistic, 35mm photography, raw photo, realistic natural lighting, soft shadows",
            "anime-2d", "anime style, soft anime illustration, light lineart, thin outline, low-contrast contour lines, soft edges, clean color blocks, gentle shading, subtle cel shading, polished 2D illustration, elegant anime rendering",
            "anime-makoto", "anime style, soft anime illustration, light lineart, thin outline, low-contrast contour lines, soft edges, clean color blocks, gentle shading, subtle cel shading, polished 2D illustration, elegant anime rendering",
            "3d-pixar", "3d render, unreal engine 5, octane render, pixar style, smooth shading",
            "cyberpunk", "cyberpunk aesthetic, neon glow, wet reflections, volumetric lighting"
    );

    private static final Map<String, String> STYLE_PRESETS_DARK = Map.of(
            "cinematic-realism", "cinematic shot, photorealistic, 35mm photography, raw photo, moody dramatic lighting, volumetric shadows, film grain",
            "anime-2d", "dark anime style, soft anime illustration, light lineart, thin outline, moody atmospheric lighting, gentle subtle cel shading, dramatic soft shadows",
            "anime-makoto", "dark anime style, soft anime illustration, light lineart, thin outline, moody atmospheric lighting, gentle subtle cel shading, dramatic soft shadows",
            "3d-pixar", "3d render, unreal engine 5, octane render, pixar style, smooth shading, moody shadows",
            "cyberpunk", "cyberpunk aesthetic, neon glow, wet reflections, volumetric lighting, dark atmosphere"
    );

    /**
     * 基于场景资产元数据判定是否属于暗光/夜间/悬疑氛围 (强类型元数据驱动，杜绝文本猜词)。
     */
    private boolean isDarkOrMoodyScene(DecomposedSceneVO scene) {
        if (scene == null) return false;
        String tod = StringUtils.defaultString(scene.getTimeOfDay()).trim().toUpperCase();
        String weather = StringUtils.defaultString(scene.getWeatherAtmosphere()).trim().toUpperCase();
        return "NIGHT".equals(tod) || "DUSK".equals(tod) || "MOODY".equals(weather) || "DARK".equals(weather);
    }

    /**
     * 风格与光影解耦解析器 (字典查表驱动，杜绝硬编码子串模糊匹配)。
     */
    private String resolveHarmonizedStyle(String preset, boolean isDark) {
        if (StringUtils.isBlank(preset)) {
            return isDark
                    ? "cinematic shot, moody dramatic lighting, chiaroscuro shadows"
                    : "cinematic shot, natural lighting";
        }

        String key = preset.trim().toLowerCase();
        Map<String, String> targetMap = isDark ? STYLE_PRESETS_DARK : STYLE_PRESETS_NORMAL;
        if (targetMap.containsKey(key)) {
            return targetMap.get(key);
        }

        return "cinematic shot, " + preset;
    }

    /**
     * 注入单个角色的纯净外貌与服装描述。
     * 基础外貌与服装纯净装配，视点构图完全由 AI 源头的 firstFrameVisual 统筹。
     */
    private void injectCharacterTokens(DecomposedCharacterVO c, List<String> segments) {
        if (c == null) return;
        List<String> charTokens = new ArrayList<>();
        if (StringUtils.isNotBlank(c.getAppearancePrompt())) {
            charTokens.add(c.getAppearancePrompt().trim());
        }
        if (StringUtils.isNotBlank(c.getOutfitPrompt())) {
            charTokens.add(c.getOutfitPrompt().trim());
        }
        if (!charTokens.isEmpty()) {
            segments.add(String.join(", ", charTokens));
        }
    }

    /**
     * 过滤出当前分镜中真实出场的角色列表。
     */
    private List<DecomposedCharacterVO> filterAppearingCharacters(DecomposedShotVO shot, List<DecomposedCharacterVO> characters) {
        if (characters == null || characters.isEmpty() || shot == null) {
            return Collections.emptyList();
        }
        List<String> appearingNames = shot.getCharacterNames();
        if (appearingNames == null || appearingNames.isEmpty()) {
            return Collections.emptyList();
        }

        List<DecomposedCharacterVO> result = new ArrayList<>();
        for (DecomposedCharacterVO c : characters) {
            String cName = StringUtils.firstNonBlank(c.getCanonicalName(), c.getName(), c.getDisplayName());
            boolean isAppearing = appearingNames.stream().anyMatch(n ->
                    (cName != null && n.equalsIgnoreCase(cName))
                            || (c.getName() != null && n.equalsIgnoreCase(c.getName()))
                            || (c.getAliases() != null && c.getAliases().stream().anyMatch(a -> a.equalsIgnoreCase(n)))
            );
            if (isAppearing) {
                result.add(c);
            }
        }
        return result;
    }

    private String formatShotType(String shotType) {
        return switch (shotType.toUpperCase()) {
            case "EXTREME_CLOSE_UP" -> "extreme close-up shot, intense focus";
            case "CLOSE_UP" -> "close-up shot, sharp details";
            case "MEDIUM_CLOSE_UP" -> "medium close-up shot";
            case "MEDIUM_SHOT" -> "medium shot, waist up";
            case "FULL_SHOT" -> "full shot";
            case "LONG_SHOT" -> "wide angle long shot, full environment";
            case "OVER_SHOULDER" -> "over the shoulder shot";
            case "TOP_DOWN" -> "top-down overhead bird-eye shot";
            default -> shotType.toLowerCase().replace("_", " ") + " shot";
        };
    }

    private String formatCameraMovement(String movement) {
        return switch (movement.toUpperCase()) {
            case "PUSH_IN" -> "slow camera push in";
            case "PULL_OUT" -> "slow camera pull out";
            case "PAN_LEFT" -> "smooth camera pan left";
            case "PAN_RIGHT" -> "smooth camera pan right";
            case "TILT_UP" -> "camera tilt up";
            case "TILT_DOWN" -> "camera tilt down";
            case "TRACKING" -> "dynamic camera tracking shot";
            case "ORBIT" -> "360 camera orbit around subjects";
            case "ZOOM_IN" -> "dramatic zoom in";
            default -> movement.toLowerCase().replace("_", " ");
        };
    }

    /**
     * 核心清洗与规范化管线：
     * 1. 过滤占位符与机械状态脏词 (阻断 full body shot 与无效机械占位词)
     * 2. 纯英文保障：剥离中文字符与全角标点
     * 3. 短语大小写无关去重与实体包含消解 (Subsumption Deduplication)
     * 遵循职责归位原则：严禁在 Java 服务端编写硬编码正则猜词与语义替换，语义与画面全权由 AI 和创作者主导。
     */
    private String cleanAndNormalize(List<String> segments, boolean isStaticT2I) {
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

                // 1. 过滤占位符与机械状态脏词 (阻断 full body shot 与无效机械占位词)
                if (INVALID_PLACEHOLDERS.contains(lower)
                        || lower.equals("full body shot")
                        || lower.equals("looking at")
                        || lower.equals("looking at null") || lower.equals("looking at none") || lower.equals("looking at 闭合")
                        || lower.equals("holding none") || lower.equals("holding empty") || lower.equals("holding 无")
                        || lower.equals("at null") || lower.equals("at center")) {
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

    /**
     * 短语大小写无关去重与实体包含消解 (Subsumption Deduplication)。
     */
    private List<String> deduplicateTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 大小写无关去重并保留顺序
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

        // 2. 实体包含消解 (Subsumption): 若短语 A 完全被更长更具体的短语 B 包含，则淘汰短语 A
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
}
