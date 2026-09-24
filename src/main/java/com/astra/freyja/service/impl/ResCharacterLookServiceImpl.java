package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.ResCharacterLookMapper;
import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dto.res.AssetPromptPackageVO;
import com.astra.freyja.dto.res.OutfitPromptDeriveDTO;
import com.astra.freyja.dto.res.OutfitPromptDeriveVO;
import com.astra.freyja.dto.res.ResCharacterLookDTO;
import com.astra.freyja.dto.res.ResCharacterLookVO;
import com.astra.freyja.engine.sse.ConcurrentSseBridge;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.entity.ResCharacterLook;
import com.astra.freyja.service.AiModelFactory;
import com.astra.freyja.service.ResCharacterLookService;
import com.astra.freyja.service.SysConfigService;
import com.astra.freyja.skill.model.SkillPromptContext;
import com.astra.freyja.skill.service.SkillPromptContextService;
import com.astra.freyja.skill.tool.LoadSkillToolFactory;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import com.astra.freyja.util.JsonExtractionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResCharacterLookServiceImpl implements ResCharacterLookService {

    private final ResCharacterLookMapper lookMapper;
    private final ResCharacterMapper characterMapper;
    private final DramaMapper dramaMapper;
    private final AiModelFactory aiModelFactory;
    private final SysConfigService sysConfigService;

    @Autowired(required = false)
    private SkillPromptContextService skillPromptContextService;

    @Autowired(required = false)
    private LoadSkillToolFactory loadSkillToolFactory;

    @Override
    public List<ResCharacterLookVO> listByCharacterId(Long characterId) {
        if (characterId == null) {
            return List.of();
        }
        LambdaQueryWrapper<ResCharacterLook> wrapper = new LambdaQueryWrapper<ResCharacterLook>()
                .eq(ResCharacterLook::getCharacterId, characterId)
                .orderByDesc(ResCharacterLook::getIsDefault)
                .orderByAsc(ResCharacterLook::getSortOrder)
                .orderByAsc(ResCharacterLook::getId);

        List<ResCharacterLook> list = lookMapper.selectList(wrapper);
        return list.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public ResCharacterLookVO getById(Long id) {
        ResCharacterLook entity = lookMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "人物造型不存在");
        }
        return toVO(entity);
    }

    @Override
    public ResCharacterLookVO getDefaultLook(Long characterId) {
        if (characterId == null) {
            return null;
        }
        ResCharacterLook look = lookMapper.selectOne(new LambdaQueryWrapper<ResCharacterLook>()
                .eq(ResCharacterLook::getCharacterId, characterId)
                .eq(ResCharacterLook::getIsDefault, 1)
                .eq(ResCharacterLook::getStatus, 1)
                .last("LIMIT 1"));
        if (look == null) {
            look = lookMapper.selectOne(new LambdaQueryWrapper<ResCharacterLook>()
                    .eq(ResCharacterLook::getCharacterId, characterId)
                    .eq(ResCharacterLook::getStatus, 1)
                    .orderByAsc(ResCharacterLook::getSortOrder)
                    .last("LIMIT 1"));
        }
        return look != null ? toVO(look) : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ResCharacterLookDTO dto) {
        if (dto.getCharacterId() == null) {
            throw new BizException(400, "所属人物 ID 不能为空");
        }
        String name = dto.getLookName();
        if (StringUtils.isBlank(name)) {
            throw new BizException(400, "造型名称不能为空");
        }
        if (StringUtils.isBlank(dto.getDesignDesc())) {
            throw new BizException(400, "造型视觉概念描述不能为空");
        }

        Long existingCount = lookMapper.selectCount(new LambdaQueryWrapper<ResCharacterLook>()
                .eq(ResCharacterLook::getCharacterId, dto.getCharacterId()));
        boolean makeDefault = existingCount == 0 || Integer.valueOf(1).equals(dto.getIsDefault());

        if (makeDefault) {
            clearDefaultForCharacter(dto.getCharacterId());
        }

        ResCharacterLook look = new ResCharacterLook();
        BeanUtils.copyProperties(dto, look);
        look.setLookName(name);
        look.setIsDefault(makeDefault ? 1 : 0);
        if (look.getLoraWeight() == null && StringUtils.isNotBlank(look.getLoraName())) {
            look.setLoraWeight(new BigDecimal("1.00"));
        }
        if (look.getSortOrder() == null) {
            look.setSortOrder(0);
        }
        if (look.getStatus() == null) {
            look.setStatus(1);
        }
        if (look.getVisualVersion() == null) {
            look.setVisualVersion(1);
        }

        if (StringUtils.isNotBlank(look.getReferenceImageUrl())) {
            look.setImageStatus("SYNCED");
            look.setImageVisualVersion(look.getVisualVersion());
        } else {
            look.setImageStatus("MISSING");
            look.setImageVisualVersion(0);
        }

        lookMapper.insert(look);
        return look.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(ResCharacterLookDTO dto) {
        Long id = dto.getId();
        if (id == null) {
            throw new BizException(400, "造型 ID 不能为空");
        }
        ResCharacterLook exist = lookMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "人物造型不存在");
        }
        if (StringUtils.isBlank(dto.getDesignDesc())) {
            throw new BizException(400, "造型视觉概念描述不能为空");
        }

        if (dto.getCharacterId() != null && !dto.getCharacterId().equals(exist.getCharacterId())) {
            throw new BizException(400, "不允许把其他人物的造型绑定到当前人物");
        }

        boolean willBeDefault = Integer.valueOf(1).equals(dto.getIsDefault());
        boolean wasDefault = Integer.valueOf(1).equals(exist.getIsDefault());
        boolean willBeDisabled = dto.getStatus() != null && dto.getStatus() == 0;

        if (wasDefault && willBeDisabled) {
            throw new BizException(400, "禁止停用当前默认造型，请先设置其他可用造型为默认");
        }

        if (willBeDefault) {
            clearDefaultForCharacter(exist.getCharacterId());
        } else if (wasDefault && Integer.valueOf(0).equals(dto.getIsDefault())) {
            promoteNextDefault(exist.getCharacterId(), exist.getId());
        }

        ResCharacterLook look = new ResCharacterLook();
        BeanUtils.copyProperties(dto, look);
        look.setId(exist.getId());
        look.setCharacterId(exist.getCharacterId());
        if (StringUtils.isNotBlank(dto.getLookName())) {
            look.setLookName(dto.getLookName());
        }

        int currentVersion = exist.getVisualVersion() != null ? exist.getVisualVersion() : 1;
        boolean promptChanged = !Objects.equals(StringUtils.trimToEmpty(dto.getOutfitPrompt()), StringUtils.trimToEmpty(exist.getOutfitPrompt()))
                || !Objects.equals(StringUtils.trimToEmpty(dto.getAppearancePrompt()), StringUtils.trimToEmpty(exist.getAppearancePrompt()))
                || !Objects.equals(StringUtils.trimToEmpty(dto.getDesignDesc()), StringUtils.trimToEmpty(exist.getDesignDesc()));

        if (promptChanged) {
            currentVersion++;
            look.setVisualVersion(currentVersion);
        }

        String newImg = StringUtils.isNotBlank(dto.getReferenceImageUrl()) ? dto.getReferenceImageUrl() : exist.getReferenceImageUrl();
        if (StringUtils.isBlank(newImg)) {
            look.setImageStatus("MISSING");
            look.setImageVisualVersion(0);
        } else if (!Objects.equals(dto.getReferenceImageUrl(), exist.getReferenceImageUrl())) {
            look.setImageStatus("SYNCED");
            look.setImageVisualVersion(currentVersion);
        } else if (promptChanged) {
            look.setImageStatus("STALE");
        }

        lookMapper.updateById(look);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ResCharacterLook exist = lookMapper.selectById(id);
        if (exist == null) {
            return;
        }

        lookMapper.deleteById(id);

        if (Integer.valueOf(1).equals(exist.getIsDefault())) {
            promoteNextDefault(exist.getCharacterId(), exist.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long id) {
        ResCharacterLook exist = lookMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "人物造型不存在");
        }
        clearDefaultForCharacter(exist.getCharacterId());
        exist.setIsDefault(1);
        if (exist.getStatus() != null && exist.getStatus() == 0) {
            exist.setStatus(1);
        }
        lookMapper.updateById(exist);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateLookImage(Long id, String imageUrl) {
        ResCharacterLook exist = lookMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "人物造型不存在");
        }
        exist.setReferenceImageUrl(imageUrl);
        int v = exist.getVisualVersion() != null ? exist.getVisualVersion() : 1;
        exist.setImageVisualVersion(v);
        exist.setImageStatus(StringUtils.isNotBlank(imageUrl) ? "SYNCED" : "MISSING");
        lookMapper.updateById(exist);
    }

    private void clearDefaultForCharacter(Long characterId) {
        lookMapper.update(null, new LambdaUpdateWrapper<ResCharacterLook>()
                .set(ResCharacterLook::getIsDefault, 0)
                .eq(ResCharacterLook::getCharacterId, characterId));
    }

    private void promoteNextDefault(Long characterId, Long excludeId) {
        List<ResCharacterLook> remaining = lookMapper.selectList(new LambdaQueryWrapper<ResCharacterLook>()
                .eq(ResCharacterLook::getCharacterId, characterId)
                .ne(excludeId != null, ResCharacterLook::getId, excludeId)
                .eq(ResCharacterLook::getStatus, 1)
                .orderByAsc(ResCharacterLook::getSortOrder)
                .orderByAsc(ResCharacterLook::getId));

        if (remaining.isEmpty()) {
            remaining = lookMapper.selectList(new LambdaQueryWrapper<ResCharacterLook>()
                    .eq(ResCharacterLook::getCharacterId, characterId)
                    .ne(excludeId != null, ResCharacterLook::getId, excludeId)
                    .orderByAsc(ResCharacterLook::getSortOrder)
                    .orderByAsc(ResCharacterLook::getId));
        }

        if (!remaining.isEmpty()) {
            ResCharacterLook first = remaining.get(0);
            first.setIsDefault(1);
            lookMapper.updateById(first);
            log.info("人物 {} 默认造型已自动提升为: {} (id={})", characterId, first.getLookName(), first.getId());
        }
    }

    private ResCharacterLookVO toVO(ResCharacterLook entity) {
        ResCharacterLookVO vo = new ResCharacterLookVO();
        BeanUtils.copyProperties(entity, vo);
        if (StringUtils.isBlank(vo.getImageStatus())) {
            vo.setImageStatus(StringUtils.isNotBlank(entity.getReferenceImageUrl()) ? "SYNCED" : "MISSING");
        }
        return vo;
    }

    @Override
    public OutfitPromptDeriveVO deriveLookPrompt(OutfitPromptDeriveDTO dto) {
        validateDesignDesc(dto);
        Long providerId = dto.getProviderId();
        String modelCode = dto.getModelCode();

        ChatModel chatModel = aiModelFactory.getChatModelOrDefault(providerId, modelCode);
        String systemPrompt = sysConfigService.getConfigValue("PROMPT_OUTFIT_DERIVE_SYSTEM", DEFAULT_OUTFIT_SYSTEM_PROMPT);
        String userPrompt = buildOutfitUserPrompt(dto);

        String output = invokeApiWithSkills(chatModel, systemPrompt, userPrompt,
                "character-outfit-prompt", String.valueOf(dto.getLookId()), dto.getRequiredSkillNames());
        if (StringUtils.isBlank(output)) {
            throw new BizException(500, "AI 模型未能返回响应");
        }

        OutfitPromptDeriveVO vo = JsonExtractionUtil.cleanAndParseJson(output, OutfitPromptDeriveVO.class);
        if (vo == null || (StringUtils.isBlank(vo.getOutfitPrompt()) && StringUtils.isBlank(vo.getAppearancePrompt()))) {
            throw new BizException(500, "AI 模型未能返回有效的造型提示词结构，返回原始内容: " + output);
        }
        return vo;
    }

    @Override
    public SseEmitter deriveLookPromptStream(OutfitPromptDeriveDTO dto) {
        validateDesignDesc(dto);
        SseEmitter emitter = new SseEmitter(180_000L);
        emitter.onTimeout(() -> {
            log.warn("[deriveLookPromptStream] SSE 造型提示词衍生请求超时 (180s)");
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        });
        emitter.onError(ex -> log.debug("[deriveLookPromptStream] SSE 客户端断开连接: {}", ex.getMessage()));

        ConcurrentSseBridge sseBridge = new ConcurrentSseBridge(emitter);

        Long providerId = dto.getProviderId();
        String modelCode = dto.getModelCode();

        Executors.newVirtualThreadPerTaskExecutor().execute(() -> {
            try {
                ChatModel chatModel = aiModelFactory.getChatModelOrDefault(providerId, modelCode);
                String systemPrompt = sysConfigService.getConfigValue("PROMPT_OUTFIT_DERIVE_SYSTEM", DEFAULT_OUTFIT_SYSTEM_PROMPT);
                String userPrompt = buildOutfitUserPrompt(dto);
                String rawText = invokeApiWithSkills(chatModel, systemPrompt, userPrompt,
                        "character-outfit-prompt", String.valueOf(dto.getLookId()), dto.getRequiredSkillNames());
                if (StringUtils.isNotBlank(rawText)) {
                    sseBridge.sendChunk(rawText);
                }
                OutfitPromptDeriveVO vo = JsonExtractionUtil.cleanAndParseJson(rawText, OutfitPromptDeriveVO.class);
                if (vo == null) {
                    sseBridge.sendError("未能从 AI 输出中提取合法的 JSON 提示词结构");
                    return;
                }

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                sseBridge.sendResult(mapper.writeValueAsString(vo));
                sseBridge.complete();
            } catch (Exception e) {
                log.error("[deriveLookPromptStream] 流式衍生造型提示词异常: {}", e.getMessage(), e);
                sseBridge.sendError("流式衍生造型提示词异常: " + e.getMessage());
            }
        });

        return emitter;
    }

    @Override
    public AssetPromptPackageVO buildLookPromptPackage(OutfitPromptDeriveDTO dto) {
        validateDesignDesc(dto);
        String systemPrompt = sysConfigService.getConfigValue("PROMPT_OUTFIT_DERIVE_SYSTEM", DEFAULT_OUTFIT_SYSTEM_PROMPT);
        SkillPromptContext skillContext = loadSkillContext(dto);
        systemPrompt = appendSkillContext(systemPrompt, skillContext);
        String userPrompt = buildOutfitUserPrompt(dto);
        String combined = "=== SYSTEM PROMPT ===\n" + systemPrompt + "\n\n=== USER PROMPT ===\n" + userPrompt;

        String fingerprint = null;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(combined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            fingerprint = sb.toString();
        } catch (Exception e) {
            fingerprint = String.valueOf(combined.hashCode());
        }

        return AssetPromptPackageVO.builder()
                .assetType("CHARACTER_OUTFIT")
                .assetId(dto.getLookId())
                .assetName(StringUtils.firstNonBlank(dto.getOutfitName(), "新造型"))
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .combinedPrompt(combined)
                .outputFormat("JSON (outfitPrompt, appearancePrompt, negativePrompt)")
                .templateVersion("1.0.0")
                .contextFingerprint(fingerprint)
                .build();
    }

    private void validateDesignDesc(OutfitPromptDeriveDTO dto) {
        if (dto == null || StringUtils.isBlank(dto.getDesignDesc())) {
            throw new BizException(400, "中文造型视觉概念描述不能为空");
        }
    }

    private SkillPromptContext loadSkillContext(OutfitPromptDeriveDTO dto) {
        if (skillPromptContextService == null) {
            return SkillPromptContext.empty();
        }
        Long requestId = dto == null ? null : dto.getLookId();
        return skillPromptContextService.loadSelected(
                "character-outfit-prompt", String.valueOf(requestId), dto == null ? null : dto.getSelectedSkillNames());
    }

    private String appendSkillContext(String systemPrompt, SkillPromptContext skillContext) {
        if (skillPromptContextService == null) {
            return systemPrompt;
        }
        return skillPromptContextService.appendToSystemPrompt(systemPrompt, skillContext);
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
        String apiSystemPrompt = appendSkillContext(systemPrompt, requiredContext);
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

    private String buildOutfitUserPrompt(OutfitPromptDeriveDTO dto) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下人物的具体造型生成高质量的 Midjourney/Stable Diffusion 英文提示词：\n\n");

        if (dto.getCharacterId() != null && dto.getCharacterId() > 0) {
            ResCharacter character = characterMapper.selectById(dto.getCharacterId());
            if (character != null) {
                sb.append("【基础人物背景】\n");
                sb.append("- 人物姓名: ").append(character.getName()).append("\n");
                if (StringUtils.isNotBlank(character.getGender())) sb.append("- 性别: ").append(character.getGender()).append("\n");
                if (StringUtils.isNotBlank(character.getAgeGroup())) sb.append("- 年龄段: ").append(character.getAgeGroup()).append("\n");
                if (StringUtils.isNotBlank(character.getAppearanceDesc())) sb.append("- 原著外貌: ").append(character.getAppearanceDesc()).append("\n");
                if (StringUtils.isNotBlank(character.getAppearancePrompt())) sb.append("- 基础身份外观Prompt: ").append(character.getAppearancePrompt()).append("\n\n");
            }
        }

        sb.append("【造型设定目标】\n");
        String name = dto.getOutfitName();
        if (StringUtils.isNotBlank(name)) sb.append("- 造型名称: ").append(name).append("\n");
        if (StringUtils.isNotBlank(dto.getLookType())) sb.append("- 视觉状态类型: ").append(dto.getLookType()).append("\n");
        if (StringUtils.isNotBlank(dto.getDesignDesc())) sb.append("- 视觉概念描述: ").append(dto.getDesignDesc()).append("\n\n");

        sb.append("请输出严格的 JSON 格式：\n{\n");
        sb.append("  \"outfitPrompt\": \"英文服装材质、轮廓、配饰细节，不要包含基础五官长相\",\n");
        sb.append("  \"appearancePrompt\": \"此造型特有的妆发、年龄、伤痕或伪装特征（若无则为空）\",\n");
        sb.append("  \"negativePrompt\": \"造型专属负向词\"\n}");
        return sb.toString();
    }

    private static final String DEFAULT_OUTFIT_SYSTEM_PROMPT = """
            你是一名顶尖影视造型设计师与 AI 绘画提示词专家。
            你的职责是根据角色背景与造型概念，拆解并生成精准、富有质感的英文视觉提示词。
            请严格按照 JSON Schema 格式输出，不要附加任何闲聊或包裹外的说明。
            """;
}
