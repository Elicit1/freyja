package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.ResSceneMapper;
import com.astra.freyja.dto.res.ResSceneDTO;
import com.astra.freyja.dto.res.ResSceneOptionVO;
import com.astra.freyja.dto.res.ResSceneQuery;
import com.astra.freyja.dto.res.ResSceneVO;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.entity.ResScene;
import com.astra.freyja.service.ResSceneService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.engine.sse.ConcurrentSseBridge;
import com.astra.freyja.service.AiModelFactory;
import com.astra.freyja.service.AiProviderService;
import com.astra.freyja.service.SysConfigService;
import com.astra.freyja.skill.model.SkillPromptContext;
import com.astra.freyja.skill.service.SkillPromptContextService;
import com.astra.freyja.skill.tool.LoadSkillToolFactory;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import com.astra.freyja.util.JsonExtractionUtil;
import com.astra.freyja.util.JsonRepairUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResSceneServiceImpl implements ResSceneService {

    private final ResSceneMapper sceneMapper;
    private final AiProviderService aiProviderService;
    private final AiModelFactory aiModelFactory;
    private final SysConfigService sysConfigService;
    private final DramaMapper dramaMapper;

    @Autowired(required = false)
    private SkillPromptContextService skillPromptContextService;

    @Autowired(required = false)
    private LoadSkillToolFactory loadSkillToolFactory;

    @Override
    public Page<ResSceneVO> page(ResSceneQuery query) {
        Page<ResScene> pageParam = new Page<>(
                query.getCurrent() != null ? query.getCurrent() : 1,
                query.getSize() != null ? query.getSize() : 10
        );

        LambdaQueryWrapper<ResScene> wrapper = new LambdaQueryWrapper<ResScene>()
                .eq(query.getDramaId() != null, ResScene::getDramaId, query.getDramaId())
                .like(StringUtils.isNotBlank(query.getName()), ResScene::getName, query.getName())
                .eq(StringUtils.isNotBlank(query.getSceneType()), ResScene::getSceneType, query.getSceneType())
                .eq(StringUtils.isNotBlank(query.getTimeOfDay()), ResScene::getTimeOfDay, query.getTimeOfDay())
                .like(StringUtils.isNotBlank(query.getWeatherAtmosphere()), ResScene::getWeatherAtmosphere, query.getWeatherAtmosphere())
                .eq(query.getStatus() != null, ResScene::getStatus, query.getStatus())
                .orderByAsc(ResScene::getSortOrder)
                .orderByDesc(ResScene::getId);

        Page<ResScene> entityPage = sceneMapper.selectPage(pageParam, wrapper);

        Page<ResSceneVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        if (entityPage.getRecords().isEmpty()) {
            voPage.setRecords(Collections.emptyList());
            return voPage;
        }

        voPage.setRecords(entityPage.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public ResSceneVO getById(Long id) {
        ResScene entity = sceneMapper.selectById(id);
        if (entity == null) {
            throw new BizException("场景环境不存在");
        }
        return toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ResSceneDTO dto) {
        if (StringUtils.isBlank(dto.getName())) {
            throw new BizException("场景名称不能为空");
        }

        ResScene scene = new ResScene();
        BeanUtils.copyProperties(dto, scene);
        if (scene.getDramaId() == null) {
            scene.setDramaId(0L); // 默认公共库
        }
        if (StringUtils.isBlank(scene.getSceneType())) {
            scene.setSceneType(null);
        }
        if (scene.getLoraWeight() == null && StringUtils.isNotBlank(scene.getLoraName())) {
            scene.setLoraWeight(new BigDecimal("1.00"));
        }
        if (scene.getSortOrder() == null) {
            scene.setSortOrder(0);
        }
        if (scene.getStatus() == null) {
            scene.setStatus(1);
        }

        sceneMapper.insert(scene);
        return scene.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(ResSceneDTO dto) {
        if (dto.getId() == null) {
            throw new BizException("场景 ID 不能为空");
        }
        ResScene exist = sceneMapper.selectById(dto.getId());
        if (exist == null) {
            throw new BizException("场景环境不存在");
        }

        ResScene scene = new ResScene();
        BeanUtils.copyProperties(dto, scene);
        sceneMapper.updateById(scene);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        sceneMapper.deleteById(id);
    }

    @Override
    public List<ResSceneOptionVO> options(Long dramaId) {
        LambdaQueryWrapper<ResScene> wrapper = new LambdaQueryWrapper<ResScene>()
                .eq(ResScene::getStatus, 1);
        if (dramaId != null && dramaId > 0) {
            wrapper.and(w -> w.eq(ResScene::getDramaId, dramaId).or().eq(ResScene::getDramaId, 0L));
        }
        wrapper.orderByAsc(ResScene::getSortOrder).orderByDesc(ResScene::getId);

        List<ResScene> list = sceneMapper.selectList(wrapper);
        return list.stream().map(s -> {
            ResSceneOptionVO vo = new ResSceneOptionVO();
            vo.setId(s.getId());
            vo.setDramaId(s.getDramaId());
            vo.setName(s.getName());
            vo.setCoverUrl(s.getCoverUrl());
            vo.setSceneType(s.getSceneType());
            vo.setTimeOfDay(s.getTimeOfDay());
            vo.setWeatherAtmosphere(s.getWeatherAtmosphere());
            vo.setScenePrompt(s.getScenePrompt());
            vo.setLoraName(s.getLoraName());
            vo.setReferenceImageUrl(s.getReferenceImageUrl());
            return vo;
        }).collect(Collectors.toList());
    }

    private ResSceneVO toVO(ResScene entity) {
        ResSceneVO vo = new ResSceneVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private void enrichDramaStyle(ScenePromptDeriveDTO dto) {
        if (dto.getDramaId() != null && dto.getDramaId() > 0) {
            Drama drama = dramaMapper.selectById(dto.getDramaId());
            if (drama != null) {
                if (StringUtils.isBlank(dto.getStylePreset())) {
                    dto.setStylePreset(drama.getStylePreset());
                }
                if (StringUtils.isBlank(dto.getStyleTone())) {
                    dto.setStyleTone(drama.getStyleTone());
                }
                if (StringUtils.isBlank(dto.getVisualStyle())) {
                    dto.setVisualStyle(drama.getStylePreset());
                }
            }
        }
    }

    private static final String DEFAULT_SCENE_SYSTEM_PROMPT = """
            你是一名好莱坞工业级概念设计场景总监与视觉摄影指导 (Environment Concept Artist & Master Visual Designer)。
            根据提供的场景基本空间属性、时段天气与中文背景细节描述，遵循“画风至上”、“动静分离”与“空间物理质感”原则，严格按照 JSON Schema 格式输出 scenePrompt 与 negativePrompt。

            【画风至上与艺术媒介自适应第一铁律 (Highest Priority - Style Dominance)】：
            创作者传入的【短剧全局画风预设 (stylePreset)】与【视觉风格基调/导演指南 (styleTone)】具备最高支配权重！所有输出的英文 Prompt 必须以此艺术风格为基底，严禁被死板的泛写实摄影词带偏！
            1. 若为【2D动漫 / 日漫 / 二次元 / 插画风格 (如 anime-2d, 2d animation, anime)】：
               - 必须默认采用【轻线稿插画风 (Soft Light Lineart Anime Scenery)】：线条细、干净、克制，弱化粗黑外轮廓与建筑死墨线，避免厚重描边。整体以柔和色块与通透光影塑形为主，边缘自然，阴影过渡柔和，杜绝粗黑边缘、硬边赛璐璐切面或设定草图感；
               - scenePrompt 最开头必须强制注入强艺术媒介前缀，如: "anime scenery style, soft anime illustration background, light lineart, thin outline, low-contrast contour lines, soft edges, clean color blocks, gentle shading, beautiful anime scenery aesthetic, radiant atmospheric glow, polished 2D illustration"；
               - 严禁出现任何写实摄影词汇 (如: photorealistic, photograph, raw photo, 35mm film, dslr)；
               - negativePrompt 必须强制追加：
                 ① 防写实与防3D词: "photorealistic, realistic, real photo, 3d render, photograph"；
                 ② 防粗线条/重墨线/硬切面/设定稿感: "thick black outline, heavy lineart, bold contour, harsh outlines, manga ink lines, excessive line weight, strong model sheet look, hard cel shading, harsh shadow edges, high contrast shadows, overly sharp edges, stiff character sheet style, rough sketch lines, dense linework, heavy comic outline, flat 2d paper cutout"。
            2. 若为【3D精美动画风格 (如 3d-animation, pixar, disney, 3d)】：
               - scenePrompt 最开头必须强制注入: "3D animated environment, Pixar Disney style scenery, smooth stylization, stylized 3D render, unreal engine 5 render, octane render"；
               - 自然融入 3D 全局光照 (如: "stylized cinematic lighting, global illumination, ray traced reflections, warm stylized bloom")；
               - negativePrompt 必须强制追加: "2d, flat drawing, real photo, photorealistic, ugly 3d"。
            3. 若为【电影级写实 / 胶片摄影风格 (如 cinematic-realism, retro-film, realistic)】：
               - 运用真实的电影镜头与专业灯光语言: "cinematic film still, 35mm photography, master environment concept art, architectural photography, ultra realistic texture, volumetric lighting, atmospheric depth"；
            4. 若为【国风水墨 / 美漫 / 赛博朋克等其他风格】：
               - 必须深度提炼该风格的核心媒介词（如 "traditional Chinese ink wash painting background, atmospheric mist", "comic book background, bold ink lines, halftone", "cyberpunk cityscape, neon lights, rainy reflective pavement"）置于 Prompt 最前端。
            5. 【纯正度原则】：严禁使用 masterpiece、8k resolution、best quality 等无实质视觉意义的泛化废词。

            【核心规则】：
            1. scenePrompt 统一整合空间格局、地墙顶建筑材质、不可移动固定陈设与现场自然/人工光影氛围。严格执行动静分离，严禁出现人物角色、角色动作及手持活动道具；
            2. negativePrompt 精准规避与当前时空、空间冲突的元素；默认排除人物、人群、角色动作与手持道具。
            """;

    private void enrichSceneContext(ScenePromptDeriveDTO dto) {
        if (dto.getSceneId() != null) {
            ResScene scene = sceneMapper.selectById(dto.getSceneId());
            if (scene != null) {
                if (dto.getDramaId() == null && scene.getDramaId() != null && scene.getDramaId() > 0) {
                    dto.setDramaId(scene.getDramaId());
                }
                if (StringUtils.isBlank(dto.getName())) dto.setName(scene.getName());
                if (StringUtils.isBlank(dto.getSceneType())) dto.setSceneType(scene.getSceneType());
                if (StringUtils.isBlank(dto.getTimeOfDay())) dto.setTimeOfDay(scene.getTimeOfDay());
                if (StringUtils.isBlank(dto.getWeatherAtmosphere())) dto.setWeatherAtmosphere(scene.getWeatherAtmosphere());
                if (StringUtils.isBlank(dto.getDescription())) dto.setDescription(scene.getDescription());
            }
        }
    }

    private String calculateSceneFingerprint(ScenePromptDeriveDTO dto, String templateVersion,
                                             String skillFingerprint) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append(StringUtils.defaultString(templateVersion)).append("|");
            sb.append(dto.getSceneId() != null ? dto.getSceneId() : "").append("|");
            sb.append(StringUtils.defaultString(dto.getName())).append("|");
            sb.append(StringUtils.defaultString(dto.getSceneType())).append("|");
            sb.append(StringUtils.defaultString(dto.getTimeOfDay())).append("|");
            sb.append(StringUtils.defaultString(dto.getWeatherAtmosphere())).append("|");
            sb.append(StringUtils.defaultString(dto.getDescription())).append("|");
            sb.append(StringUtils.defaultString(dto.getStylePreset())).append("|");
            sb.append(StringUtils.defaultString(dto.getStyleTone())).append("|");
            sb.append(StringUtils.defaultString(skillFingerprint));
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "sha256:" + hex.substring(0, 16);
        } catch (Exception e) {
            return "sha256:unknown";
        }
    }

    private SkillPromptContext loadSkillContext(Long sceneId, List<String> selectedSkillNames) {
        if (skillPromptContextService == null) {
            return SkillPromptContext.empty();
        }
        return skillPromptContextService.loadSelected("scene-prompt", String.valueOf(sceneId), selectedSkillNames);
    }

    @Override
    public AssetPromptPackageVO buildPromptPackage(ScenePromptDeriveDTO dto) {
        if (dto == null) {
            throw new BizException("请求参数不能为空");
        }

        enrichSceneContext(dto);
        enrichDramaStyle(dto);

        if (StringUtils.isBlank(dto.getName()) && StringUtils.isBlank(dto.getDescription())) {
            throw new BizException("场景名称或场景中文描述至少填写一项");
        }

        BeanOutputConverter<ScenePromptDeriveVO> converter = new BeanOutputConverter<>(ScenePromptDeriveVO.class);
        String outputFormat = converter.getFormat();

        String systemPrompt = sysConfigService.getConfigValue("ai.prompt.scene_prompt_enrich_system", DEFAULT_SCENE_SYSTEM_PROMPT);
        SkillPromptContext skillContext = loadSkillContext(dto.getSceneId(), dto.getSelectedSkillNames());
        systemPrompt = skillPromptContextService != null
                ? skillPromptContextService.appendToSystemPrompt(systemPrompt, skillContext)
                : systemPrompt;

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("请根据以下场景设定，输出一套高精度英文场景生图 Prompt：\n");
        if (StringUtils.isNotBlank(dto.getStylePreset()) || StringUtils.isNotBlank(dto.getVisualStyle()) || StringUtils.isNotBlank(dto.getStyleTone())) {
            userPrompt.append("★【核心艺术画风与导演视觉基调 (最高优先级，必须以此风格为媒介统领全局)】:\n");
            if (StringUtils.isNotBlank(dto.getStylePreset()) || StringUtils.isNotBlank(dto.getVisualStyle())) {
                String preset = StringUtils.isNotBlank(dto.getStylePreset()) ? dto.getStylePreset() : dto.getVisualStyle();
                userPrompt.append("  - 短剧画风预设 (stylePreset): ").append(preset).append("\n");
            }
            if (StringUtils.isNotBlank(dto.getStyleTone())) {
                userPrompt.append("  - 视觉风格基调/导演指南 (styleTone): ").append(dto.getStyleTone()).append("\n");
            }
        }
        userPrompt.append("- 场景名称: ").append(StringUtils.defaultIfBlank(dto.getName(), "未命名场景")).append("\n");
        if (StringUtils.isNotBlank(dto.getSceneType())) {
            userPrompt.append("- 空间类型: ").append(dto.getSceneType().trim()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getTimeOfDay())) {
            userPrompt.append("- 时间时段: ").append(dto.getTimeOfDay()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getWeatherAtmosphere())) {
            userPrompt.append("- 天气与氛围: ").append(dto.getWeatherAtmosphere()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getDescription())) {
            userPrompt.append("- 中文环境与陈设视觉细节描述 (原著视觉源 SSOT): ").append(dto.getDescription()).append("\n");
        }
        userPrompt.append("\n【输出格式规范 (必须严格遵守 JSON Schema)】:\n").append(outputFormat);

        String combinedPrompt = String.format("""
                你必须同时遵守以下 SYSTEM INSTRUCTIONS 和 USER TASK。

                ================ SYSTEM INSTRUCTIONS ================
                %s

                ================ USER TASK ================
                %s

                ================ RESPONSE REQUIREMENT ================
                只返回一个合法 JSON 对象。
                不要添加 Markdown 代码块、解释、前言或结语。
                """, systemPrompt.trim(), userPrompt.toString().trim());

        String templateVersion = "scene-prompt-v1";
        String contextFingerprint = calculateSceneFingerprint(dto, templateVersion, skillContext.fingerprint());

        return AssetPromptPackageVO.builder()
                .assetType("SCENE")
                .assetId(dto.getSceneId())
                .assetName(dto.getName())
                .systemPrompt(systemPrompt.trim())
                .userPrompt(userPrompt.toString().trim())
                .combinedPrompt(combinedPrompt.trim())
                .outputFormat(outputFormat)
                .templateVersion(templateVersion)
                .contextFingerprint(contextFingerprint)
                .build();
    }

    @Override
    public ScenePromptValidationResult parseAndValidateDerivedPrompt(ScenePromptParseRequestDTO request) {
        if (request == null || StringUtils.isBlank(request.getRawResponse())) {
            List<String> errors = new ArrayList<>();
            errors.add("待解析的外部 AI 结果文本不能为空");
            return ScenePromptValidationResult.builder().errors(errors).build();
        }

        // 构建指纹比对上下文
        ScenePromptDeriveDTO contextDto = new ScenePromptDeriveDTO();
        contextDto.setSceneId(request.getSceneId());
        contextDto.setDramaId(request.getDramaId());
        contextDto.setName(request.getName());
        contextDto.setSceneType(request.getSceneType());
        contextDto.setTimeOfDay(request.getTimeOfDay());
        contextDto.setWeatherAtmosphere(request.getWeatherAtmosphere());
        contextDto.setDescription(request.getDescription());
        contextDto.setStylePreset(request.getStylePreset());
        contextDto.setStyleTone(request.getStyleTone());
        contextDto.setVisualStyle(request.getVisualStyle());
        contextDto.setSelectedSkillNames(request.getSelectedSkillNames());
        enrichSceneContext(contextDto);
        enrichDramaStyle(contextDto);

        SkillPromptContext skillContext = loadSkillContext(contextDto.getSceneId(), contextDto.getSelectedSkillNames());
        String currentFingerprint = calculateSceneFingerprint(contextDto, "scene-prompt-v1", skillContext.fingerprint());
        Boolean fingerprintMatched = null;
        if (StringUtils.isNotBlank(request.getContextFingerprint())) {
            fingerprintMatched = currentFingerprint.equalsIgnoreCase(request.getContextFingerprint().trim());
        }

        // 使用通用 JsonExtractionUtil 解析
        ScenePromptDeriveVO parsedVo = JsonExtractionUtil.cleanAndParseJson(request.getRawResponse(), ScenePromptDeriveVO.class);

        return validateAndNormalizeScenePrompt(parsedVo, fingerprintMatched);
    }

    public ScenePromptValidationResult validateAndNormalizeScenePrompt(ScenePromptDeriveVO vo, Boolean fingerprintMatched) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (vo == null) {
            errors.add("无法从文本中解析出有效的场景提示词 JSON 结构，请检查是否返回了合法的 JSON 对象");
            return ScenePromptValidationResult.builder()
                    .errors(errors)
                    .warnings(warnings)
                    .fingerprintMatched(fingerprintMatched)
                    .build();
        }

        if (fingerprintMatched != null && !fingerprintMatched) {
            warnings.add("当前场景上下文指纹与导出时不一致，场景基础设定可能在复制期间被修改");
        }

        // 规范化与字段检查
        if (StringUtils.isBlank(vo.getScenePrompt())) {
            warnings.add("场景空间生图 Prompt (scenePrompt) 未生成或为空");
        } else {
            vo.setScenePrompt(vo.getScenePrompt().trim());
        }
        if (StringUtils.isNotBlank(vo.getNegativePrompt())) {
            vo.setNegativePrompt(vo.getNegativePrompt().trim());
        }

        return ScenePromptValidationResult.builder()
                .result(vo)
                .errors(errors)
                .warnings(warnings)
                .fingerprintMatched(fingerprintMatched)
                .build();
    }

    @Override
    public ScenePromptDeriveVO derivePrompts(ScenePromptDeriveDTO dto) {
        AssetPromptPackageVO packageVO = buildPromptPackage(dto);
        ChatModel chatModel = aiModelFactory.getChatModelOrDefault(dto.getProviderId(), dto.getModelCode());

        try {
            String raw = invokeApiWithSkills(chatModel, packageVO.getSystemPrompt(), packageVO.getUserPrompt(),
                    "scene-prompt", String.valueOf(dto.getSceneId()), dto.getRequiredSkillNames());
            if (StringUtils.isNotBlank(raw)) {
                ScenePromptDeriveVO vo = JsonExtractionUtil.cleanAndParseJson(raw, ScenePromptDeriveVO.class);
                if (vo == null) vo = JsonExtractionUtil.cleanAndParseJson(JsonRepairUtil.repair(raw), ScenePromptDeriveVO.class);
                if (vo != null) {
                    ScenePromptValidationResult validation = validateAndNormalizeScenePrompt(vo, true);
                    if (validation.hasErrors()) throw new BizException(String.join("; ", validation.getErrors()));
                    return validation.getResult();
                }
            }
        } catch (Exception e) {
            log.error("[ScenePromptDerive] 调用大模型衍生场景提示词失败: {}", e.getMessage(), e);
            throw new BizException("AI 智能衍生场景提示词失败: " + e.getMessage());
        }

        throw new BizException("AI 模型未能返回有效的场景提示词结构");
    }

    @Override
    public SseEmitter derivePromptsStream(ScenePromptDeriveDTO dto) {
        SseEmitter emitter = new SseEmitter(180_000L);
        emitter.onTimeout(() -> {
            log.warn("[ScenePromptDeriveStream] SSE 场景提示词衍生请求超时 (180s)");
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        });
        emitter.onError(ex -> log.debug("[ScenePromptDeriveStream] SSE 客户端断开连接: {}", ex.getMessage()));

        ConcurrentSseBridge sseBridge = new ConcurrentSseBridge(emitter);

        Executors.newVirtualThreadPerTaskExecutor().execute(() -> {
            try {
                // 关键点：立即发送首帧心跳与握手片元，确保 Spring MVC 立即向客户端写出 HTTP 200 与 Content-Type: text/event-stream 响应头
                sseBridge.sendChunk("⚡ 正在建立 AI 提示词推理连接...\n");

                AssetPromptPackageVO packageVO;
                try {
                    packageVO = buildPromptPackage(dto);
                } catch (BizException bizEx) {
                    sseBridge.sendError(bizEx.getMessage());
                    return;
                }

                ChatModel chatModel = aiModelFactory.getChatModelOrDefault(dto.getProviderId(), dto.getModelCode());

                String rawText = invokeApiWithSkills(chatModel, packageVO.getSystemPrompt(), packageVO.getUserPrompt(),
                        "scene-prompt", String.valueOf(dto.getSceneId()), dto.getRequiredSkillNames());
                if (StringUtils.isNotBlank(rawText)) {
                    sseBridge.sendChunk(rawText);
                }
                ScenePromptDeriveVO vo = JsonExtractionUtil.cleanAndParseJson(rawText, ScenePromptDeriveVO.class);
                if (vo == null) {
                    // 容错降级使用 JsonRepairUtil
                    String repaired = JsonRepairUtil.repair(rawText);
                    vo = JsonExtractionUtil.cleanAndParseJson(repaired, ScenePromptDeriveVO.class);
                }

                ScenePromptValidationResult validationResult = validateAndNormalizeScenePrompt(vo, true);

                if (validationResult.hasErrors()) {
                    sseBridge.sendError(String.join("; ", validationResult.getErrors()));
                    return;
                }

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                sseBridge.sendResult(mapper.writeValueAsString(validationResult.getResult()));
                sseBridge.complete();
            } catch (Exception e) {
                log.error("[ScenePromptDeriveStream] 流式衍生异常: {}", e.getMessage(), e);
                sseBridge.sendError("衍生提示词异常: " + e.getMessage());
            } finally {
                sseBridge.complete();
            }
        });

        return emitter;
    }

    private String invokeApiWithSkills(ChatModel chatModel, String systemPrompt, String userPrompt,
                                       String consumer, String requestId, List<String> requiredSkillNames) {
        if (skillPromptContextService == null || loadSkillToolFactory == null) {
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(systemPrompt), new UserMessage(userPrompt))));
            return response != null && response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : null;
        }
        LoadSkillToolSession session = skillPromptContextService.createApiSession(consumer, requestId, requiredSkillNames);
        SkillPromptContext requiredContext = skillPromptContextService.loadSelected(
                consumer, requestId, requiredSkillNames, session.getVersionSnapshot());
        String apiSystemPrompt = skillPromptContextService.appendToSystemPrompt(systemPrompt, requiredContext);
        apiSystemPrompt = skillPromptContextService.appendCatalogToSystemPrompt(apiSystemPrompt);
        ToolCallback tool = loadSkillToolFactory.createTool(session);
        ChatClient.Builder clientBuilder = ChatClient.builder(chatModel);
        ChatOptions defaultOptions = chatModel.getOptions();
        if (defaultOptions != null) {
            clientBuilder.defaultOptions(defaultOptions.mutate());
        }
        String content = clientBuilder.build().prompt()
                .system(apiSystemPrompt)
                .user(userPrompt)
                .tools(tool)
                .call()
                .content();
        log.info("[SkillPrompt] consumer={}, requestId={}, apiLoadedSkills={}, toolCalls={}",
                consumer, requestId, session.getLoadedSkillNames(), session.getInvocationHistory());
        return content;
    }
}
