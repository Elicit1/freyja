package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dao.ResCharacterOutfitMapper;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.entity.ResCharacterOutfit;
import com.astra.freyja.entity.enums.AliasType;
import com.astra.freyja.entity.enums.EvidenceType;
import com.astra.freyja.entity.enums.IdentityStatus;
import com.astra.freyja.service.AiModelFactory;
import com.astra.freyja.service.CharacterRegistryService;
import com.astra.freyja.service.ResCharacterOutfitService;
import com.astra.freyja.service.ResCharacterService;
import com.astra.freyja.service.SysConfigService;
import com.astra.freyja.skill.model.SkillPromptContext;
import com.astra.freyja.skill.service.SkillPromptContextService;
import com.astra.freyja.skill.tool.LoadSkillToolFactory;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.astra.freyja.engine.sse.ConcurrentSseBridge;
import com.astra.freyja.util.JsonExtractionUtil;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResCharacterServiceImpl implements ResCharacterService {

    private final ResCharacterMapper characterMapper;
    private final ResCharacterOutfitMapper outfitMapper;
    private final ResCharacterOutfitService outfitService;
    private final CharacterRegistryService characterRegistryService;
    private final AiModelFactory aiModelFactory;
    private final SysConfigService sysConfigService;
    private final DramaMapper dramaMapper;

    @Autowired(required = false)
    private SkillPromptContextService skillPromptContextService;

    @Autowired(required = false)
    private LoadSkillToolFactory loadSkillToolFactory;

    @Override
    public Page<ResCharacterVO> page(ResCharacterQuery query) {
        Page<ResCharacter> pageParam = new Page<>(
                query.getCurrent() != null ? query.getCurrent() : 1,
                query.getSize() != null ? query.getSize() : 10
        );

        LambdaQueryWrapper<ResCharacter> wrapper = new LambdaQueryWrapper<ResCharacter>()
                .eq(query.getDramaId() != null, ResCharacter::getDramaId, query.getDramaId())
                .and(StringUtils.isNotBlank(query.getName()), w ->
                        w.like(ResCharacter::getName, query.getName())
                                .or().like(ResCharacter::getCanonicalName, query.getName())
                                .or().like(ResCharacter::getDisplayName, query.getName()))
                .eq(StringUtils.isNotBlank(query.getGender()), ResCharacter::getGender, query.getGender())
                .eq(StringUtils.isNotBlank(query.getRoleType()), ResCharacter::getRoleType, query.getRoleType())
                .eq(StringUtils.isNotBlank(query.getIdentityStatus()), ResCharacter::getIdentityStatus, query.getIdentityStatus())
                .eq(query.getStatus() != null, ResCharacter::getStatus, query.getStatus());

        if (Boolean.TRUE.equals(query.getExcludeMerged()) && StringUtils.isBlank(query.getIdentityStatus())) {
            wrapper.ne(ResCharacter::getIdentityStatus, IdentityStatus.MERGED.name());
        }

        wrapper.orderByAsc(ResCharacter::getSortOrder)
                .orderByDesc(ResCharacter::getId);

        Page<ResCharacter> entityPage = characterMapper.selectPage(pageParam, wrapper);

        Page<ResCharacterVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        if (entityPage.getRecords().isEmpty()) {
            voPage.setRecords(Collections.emptyList());
            return voPage;
        }

        // 批量加载造型信息
        List<Long> characterIds = entityPage.getRecords().stream().map(ResCharacter::getId).collect(Collectors.toList());
        List<ResCharacterOutfit> outfits = outfitMapper.selectList(new LambdaQueryWrapper<ResCharacterOutfit>()
                .in(ResCharacterOutfit::getCharacterId, characterIds)
                .orderByDesc(ResCharacterOutfit::getIsDefault)
                .orderByAsc(ResCharacterOutfit::getSortOrder));

        Map<Long, List<ResCharacterOutfitVO>> outfitMap = outfits.stream()
                .map(this::toOutfitVO)
                .collect(Collectors.groupingBy(ResCharacterOutfitVO::getCharacterId));

        List<ResCharacterVO> voList = entityPage.getRecords().stream().map(entity -> {
            ResCharacterVO vo = toVO(entity);
            List<ResCharacterOutfitVO> charOutfits = outfitMap.getOrDefault(entity.getId(), Collections.emptyList());
            vo.setOutfits(charOutfits);
            vo.setLooks(new ArrayList<>(charOutfits));
            vo.setOutfitCount(charOutfits.size());
            ResCharacterOutfitVO defLook = charOutfits.stream().filter(o -> Integer.valueOf(1).equals(o.getIsDefault())).findFirst().orElse(null);
            vo.setDefaultOutfit(defLook);
            vo.setDefaultLook(defLook);
            if (defLook != null && StringUtils.isNotBlank(defLook.getReferenceImageUrl())) {
                vo.setReferenceImageUrl(defLook.getReferenceImageUrl());
            }
            // 加载别名列表
            vo.setAliases(characterRegistryService.listAliasesByCharacterId(entity.getId()));
            return vo;
        }).collect(Collectors.toList());

        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public ResCharacterVO getById(Long id) {
        ResCharacter entity = characterMapper.selectById(id);
        if (entity == null) {
            throw new BizException("人物角色不存在");
        }
        ResCharacterVO vo = toVO(entity);
        List<ResCharacterOutfitVO> outfits = outfitService.listByCharacterId(id);
        vo.setOutfits(outfits);
        vo.setLooks(new ArrayList<>(outfits));
        vo.setOutfitCount(outfits.size());
        ResCharacterOutfitVO defLook = outfits.stream().filter(o -> Integer.valueOf(1).equals(o.getIsDefault())).findFirst().orElse(null);
        vo.setDefaultOutfit(defLook);
        vo.setDefaultLook(defLook);
        if (defLook != null && StringUtils.isNotBlank(defLook.getReferenceImageUrl())) {
            vo.setReferenceImageUrl(defLook.getReferenceImageUrl());
        }
        vo.setAliases(characterRegistryService.listAliasesByCharacterId(id));
        vo.setEvidences(characterRegistryService.listEvidencesByCharacterId(id));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ResCharacterDTO dto) {
        if (StringUtils.isBlank(dto.getName()) && StringUtils.isBlank(dto.getCanonicalName()) && StringUtils.isBlank(dto.getDisplayName())) {
            throw new BizException("人物名称不能为空");
        }

        String primaryName = StringUtils.firstNonBlank(dto.getCanonicalName(), dto.getName(), dto.getDisplayName());

        ResCharacter character = new ResCharacter();
        BeanUtils.copyProperties(dto, character);
        character.setName(primaryName);
        character.setCanonicalName(dto.getCanonicalName());
        character.setDisplayName(StringUtils.defaultIfBlank(dto.getDisplayName(), primaryName));

        if (character.getDramaId() == null) {
            character.setDramaId(0L); // 默认公共库
        }
        if (StringUtils.isBlank(character.getGender())) {
            character.setGender("UNKNOWN");
        }
        if (StringUtils.isBlank(character.getRoleType())) {
            character.setRoleType("PROTAGONIST");
        }
        if (StringUtils.isBlank(character.getIdentityStatus())) {
            character.setIdentityStatus(StringUtils.isNotBlank(character.getCanonicalName())
                    ? IdentityStatus.CONFIRMED.name() : IdentityStatus.PARTIAL.name());
        }
        if (character.getLoraWeight() == null && StringUtils.isNotBlank(character.getLoraName())) {
            character.setLoraWeight(new BigDecimal("1.00"));
        }
        if (character.getSortOrder() == null) {
            character.setSortOrder(0);
        }
        if (character.getStatus() == null) {
            character.setStatus(1);
        }

        characterMapper.insert(character);

        // 造型是可选视觉状态：角色创建时不再自动生成“默认造型”。
        // 只有用户明确建立造型，或拆解结果明确输出造型结构时，才创建造型记录。

        // 初始化别名
        ResCharacterAliasDTO aliasDTO = new ResCharacterAliasDTO();
        aliasDTO.setCharacterId(character.getId());
        aliasDTO.setAlias(primaryName);
        aliasDTO.setAliasType(AliasType.NAME.name());
        characterRegistryService.addAlias(aliasDTO);

        if (dto.getAliases() != null) {
            for (String alias : dto.getAliases()) {
                if (StringUtils.isNotBlank(alias) && !alias.trim().equalsIgnoreCase(primaryName)) {
                    ResCharacterAliasDTO extraAlias = new ResCharacterAliasDTO();
                    extraAlias.setCharacterId(character.getId());
                    extraAlias.setAlias(alias.trim());
                    extraAlias.setAliasType(AliasType.OTHER.name());
                    characterRegistryService.addAlias(extraAlias);
                }
            }
        }

        // 记录初始身份证据
        characterRegistryService.recordEvidence(
                character.getDramaId(),
                character.getId(),
                null,
                null,
                "创建角色实体: " + primaryName,
                EvidenceType.FIRST_APPEARANCE.name(),
                BigDecimal.ONE,
                "手动/业务创建角色"
        );

        return character.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(ResCharacterDTO dto) {
        if (dto.getId() == null) {
            throw new BizException("人物 ID 不能为空");
        }
        ResCharacter exist = characterMapper.selectById(dto.getId());
        if (exist == null) {
            throw new BizException("人物角色不存在");
        }

        ResCharacter character = new ResCharacter();
        BeanUtils.copyProperties(dto, character);
        if (StringUtils.isNotBlank(dto.getCanonicalName()) && StringUtils.isBlank(dto.getIdentityStatus())) {
            character.setIdentityStatus(IdentityStatus.CONFIRMED.name());
        }
        characterMapper.updateById(character);

        // 若更新了别名
        if (dto.getAliases() != null) {
            for (String alias : dto.getAliases()) {
                if (StringUtils.isNotBlank(alias)) {
                    ResCharacterAliasDTO extraAlias = new ResCharacterAliasDTO();
                    extraAlias.setCharacterId(dto.getId());
                    extraAlias.setAlias(alias.trim());
                    extraAlias.setAliasType(AliasType.OTHER.name());
                    characterRegistryService.addAlias(extraAlias);
                }
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ResCharacter exist = characterMapper.selectById(id);
        if (exist == null) {
            return;
        }

        // 删除角色本身
        characterMapper.deleteById(id);

        // 级联逻辑删除该角色的所有造型
        outfitMapper.delete(new LambdaQueryWrapper<ResCharacterOutfit>().eq(ResCharacterOutfit::getCharacterId, id));
    }

    @Override
    public List<ResCharacterOptionVO> options(Long dramaId) {
        LambdaQueryWrapper<ResCharacter> wrapper = new LambdaQueryWrapper<ResCharacter>()
                .ne(ResCharacter::getIdentityStatus, IdentityStatus.MERGED.name())
                .eq(ResCharacter::getStatus, 1);
        if (dramaId != null && dramaId > 0) {
            wrapper.and(w -> w.eq(ResCharacter::getDramaId, dramaId).or().eq(ResCharacter::getDramaId, 0L));
        }
        wrapper.orderByAsc(ResCharacter::getSortOrder).orderByDesc(ResCharacter::getId);

        List<ResCharacter> characters = characterMapper.selectList(wrapper);
        if (characters.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> characterIds = characters.stream().map(ResCharacter::getId).collect(Collectors.toList());
        List<ResCharacterOutfit> outfits = outfitMapper.selectList(new LambdaQueryWrapper<ResCharacterOutfit>()
                .in(ResCharacterOutfit::getCharacterId, characterIds)
                .eq(ResCharacterOutfit::getStatus, 1)
                .orderByDesc(ResCharacterOutfit::getIsDefault)
                .orderByAsc(ResCharacterOutfit::getSortOrder));

        Map<Long, List<ResCharacterOutfitVO>> outfitMap = outfits.stream()
                .map(this::toOutfitVO)
                .collect(Collectors.groupingBy(ResCharacterOutfitVO::getCharacterId));

        return characters.stream().map(c -> {
            ResCharacterOptionVO vo = new ResCharacterOptionVO();
            vo.setId(c.getId());
            vo.setDramaId(c.getDramaId());
            vo.setName(StringUtils.defaultIfBlank(c.getCanonicalName(), c.getName()));
            List<ResCharacterOutfitVO> charOutfits = outfitMap.getOrDefault(c.getId(), Collections.emptyList());
            ResCharacterOutfitVO defLook = charOutfits.stream().filter(o -> Integer.valueOf(1).equals(o.getIsDefault())).findFirst().orElse(null);
            if (defLook != null && StringUtils.isNotBlank(defLook.getReferenceImageUrl())) {
                vo.setReferenceImageUrl(defLook.getReferenceImageUrl());
            } else {
                vo.setReferenceImageUrl(c.getReferenceImageUrl());
            }
            vo.setRoleType(c.getRoleType());
            vo.setTriggerWords(c.getTriggerWords());
            vo.setAppearancePrompt(c.getAppearancePrompt());
            vo.setVoiceSampleUrl(c.getVoiceSampleUrl());
            vo.setVoiceSampleText(c.getVoiceSampleText());
            vo.setVoiceDesc(c.getVoiceDesc());
            vo.setOutfits(charOutfits);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void merge(CharacterMergeDTO dto) {
        characterRegistryService.mergeCharacter(dto);
    }

    private static final String DEFAULT_CHARACTER_IDENTITY_SYSTEM_PROMPT = """
            你是一名具备全画风掌控力的高级概念设计总监与顶尖 AI 生图提示词架构师 (Lead Character Designer & Senior Prompt Engineer)。
            你的任务是：根据创作者提供的【角色内在人设】、【中文外貌视觉设定】、以及最重要的【画风预设 (stylePreset)】与【视觉基调/导演指南 (styleTone)】，为角色生成一套极其纯粹的【角色稳定身份特征英文 Prompt】(appearancePrompt) 与【全局负向约束】(negativePrompt)。

            【核心铁律与职责边界】：
            1. 纯粹身份层原则：
               - 只描述角色的生物学与视觉身份稳定特征：骨相脸型、五官轮廓、眼睛形状与瞳色、眉形、发型与发色、体型体态、特定生物标记（如稳定胎记/痣）、固有神态与内在气质。
               - ★绝对严禁包含任何具体服装、日常服饰、鞋帽、具体装束、临时配饰、持握道具或武器！（服装与战损变体全部归属于多造型层，严禁在此污染身份层）。
               - ★绝对严禁包含任何构图、镜头、摄影机位词汇（如 close-up, medium shot, wide angle, high angle, masterpiece 等），镜头机位由下游分镜控制，严禁在此硬编码。
            2. 艺术媒介强统领原则：
               - 若画风为【2D动漫 / 日漫 / 二次元 / 插画风格 (如 anime-2d, 2d animation, anime)】：
                 * 默认采用【轻线稿插画风 (Soft Light Lineart Anime Style)】：线条细、干净、克制，以柔和色块塑形为主，边缘自然，保持清晰的二次元角色识别度；
                 * 正向 Prompt 【最开头】强制注入艺术媒介词，如: "anime style, soft anime illustration, light lineart, clean color blocks, gentle shading, polished 2D illustration"；
                 * 严禁出现任何写实摄影词（photorealistic, photograph, raw photo, pores 等）；
                 * 负向 Prompt 强制追加防写实与防重线稿词：photorealistic, realistic, real photo, 3d render, thick black outline, heavy lineart, harsh outlines, manga ink lines。
               - 若画风为【3D精美动画风格 (如 3d-animation, pixar, disney, 3d)】：
                 * 开头注入: "3D animated feature style, Pixar Disney aesthetic, smooth stylization, 3D character render, unreal engine 5 render"；
                 * 负向追加: "2d, flat drawing, real photo, photorealistic"。
               - 若画风为【电影级写实 / 胶片摄影风格 (如 cinematic-realism, retro-film, realistic)】：
                 * 开头注入真实的电影质感词: "cinematic film still, 35mm photograph, subtle cinematic film grain, soft studio lighting"；
                 * 允许写 "natural skin texture with subtle pores"。
               - 若为其他风格（国风水墨、美漫、赛博朋克等）：开头注入对应纯正媒介词。
            3. 正负向成对与自适应：
               - 若角色为女性/少女 (gender=FEMALE)，负向必须主动排除男性化与粗重体征: "masculine features, manly, muscular build, broad shoulders"；
               - 严禁出现无视觉意义的水词 (如 8k resolution, masterpiece)。
            """;

    private void enrichCharacterVisualContext(CharacterVisualPromptDeriveDTO dto) {
        if (dto.getCharacterId() != null) {
            ResCharacter character = characterMapper.selectById(dto.getCharacterId());
            if (character != null) {
                if (dto.getDramaId() == null && character.getDramaId() != null && character.getDramaId() > 0) {
                    dto.setDramaId(character.getDramaId());
                }
                if (StringUtils.isBlank(dto.getName())) dto.setName(StringUtils.defaultIfBlank(character.getCanonicalName(), character.getName()));
                if (StringUtils.isBlank(dto.getGender())) dto.setGender(character.getGender());
                if (StringUtils.isBlank(dto.getAgeGroup())) dto.setAgeGroup(character.getAgeGroup());
                if (StringUtils.isBlank(dto.getRoleType())) dto.setRoleType(character.getRoleType());
                if (StringUtils.isBlank(dto.getPersonality())) dto.setPersonality(character.getPersonality());
                if (StringUtils.isBlank(dto.getAppearanceDesc())) dto.setAppearanceDesc(character.getAppearanceDesc());
            }
        }
    }

    private void enrichDramaVisualStyle(CharacterVisualPromptDeriveDTO dto) {
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

    private String calculateCharacterVisualFingerprint(CharacterVisualPromptDeriveDTO dto, String templateVersion,
                                                       String skillFingerprint) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append(StringUtils.defaultString(templateVersion)).append("|");
            sb.append(dto.getCharacterId() != null ? dto.getCharacterId() : "").append("|");
            sb.append(StringUtils.defaultString(dto.getName())).append("|");
            sb.append(StringUtils.defaultString(dto.getGender())).append("|");
            sb.append(StringUtils.defaultString(dto.getAgeGroup())).append("|");
            sb.append(StringUtils.defaultString(dto.getRoleType())).append("|");
            sb.append(StringUtils.defaultString(dto.getPersonality())).append("|");
            sb.append(StringUtils.defaultString(dto.getAppearanceDesc())).append("|");
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

    private SkillPromptContext loadSkillContext(Long characterId, List<String> selectedSkillNames) {
        if (skillPromptContextService == null) {
            return SkillPromptContext.empty();
        }
        return skillPromptContextService.loadSelected(
                "character-identity-prompt", String.valueOf(characterId), selectedSkillNames);
    }

    @Override
    public AssetPromptPackageVO buildVisualPromptPackage(CharacterVisualPromptDeriveDTO dto) {
        if (dto == null) {
            throw new BizException("请求参数不能为空");
        }
        enrichCharacterVisualContext(dto);
        enrichDramaVisualStyle(dto);

        if (StringUtils.isBlank(dto.getName()) && StringUtils.isBlank(dto.getAppearanceDesc()) && StringUtils.isBlank(dto.getPersonality())) {
            throw new BizException("角色名称、性格或中文外貌描述至少填写一项");
        }

        BeanOutputConverter<CharacterVisualPromptDeriveVO> converter = new BeanOutputConverter<>(CharacterVisualPromptDeriveVO.class);
        String outputFormat = converter.getFormat();

        String systemPrompt = sysConfigService.getConfigValue("ai.prompt.character_identity_prompt_system", DEFAULT_CHARACTER_IDENTITY_SYSTEM_PROMPT);
        SkillPromptContext skillContext = loadSkillContext(dto.getCharacterId(), dto.getSelectedSkillNames());
        systemPrompt = skillPromptContextService != null
                ? skillPromptContextService.appendToSystemPrompt(systemPrompt, skillContext)
                : systemPrompt;

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("请根据以下角色稳定身份设定，输出角色专属纯正英文外观提示词 (appearancePrompt) 与负向约束 (negativePrompt)：\n");
        if (StringUtils.isNotBlank(dto.getStylePreset()) || StringUtils.isNotBlank(dto.getVisualStyle()) || StringUtils.isNotBlank(dto.getStyleTone())) {
            userPrompt.append("★【核心艺术画风与导演视觉基调 (最高优先级，必须以此风格为媒介统领全局)】:\n");
            if (StringUtils.isNotBlank(dto.getStylePreset()) || StringUtils.isNotBlank(dto.getVisualStyle())) {
                String preset = StringUtils.isNotBlank(dto.getStylePreset()) ? dto.getStylePreset() : dto.getVisualStyle();
                userPrompt.append("  - 画风预设 (stylePreset): ").append(preset).append("\n");
            }
            if (StringUtils.isNotBlank(dto.getStyleTone())) {
                userPrompt.append("  - 视觉风格基调/导演指南 (styleTone): ").append(dto.getStyleTone()).append("\n");
            }
        }
        userPrompt.append("- 角色姓名: ").append(StringUtils.defaultIfBlank(dto.getName(), "未命名")).append("\n");
        userPrompt.append("- 性别与年龄段: ").append(StringUtils.defaultIfBlank(dto.getGender(), "UNKNOWN"))
                .append(", ").append(StringUtils.defaultIfBlank(dto.getAgeGroup(), "YOUTH")).append("\n");
        userPrompt.append("- 角色定位: ").append(StringUtils.defaultIfBlank(dto.getRoleType(), "PROTAGONIST")).append("\n");
        if (StringUtils.isNotBlank(dto.getPersonality())) {
            userPrompt.append("- 内在性格与处事原则 (内在心智): ").append(dto.getPersonality()).append("\n");
        }
        if (StringUtils.isNotBlank(dto.getAppearanceDesc())) {
            userPrompt.append("- 中文外貌视觉特征描述 (原著外貌真实源 SSOT): ").append(dto.getAppearanceDesc()).append("\n");
        }
        userPrompt.append("\n★【重要提醒】：严格禁止生成具体服装、饰品、鞋帽或镜头视角，仅保留五官神态、发型发色、体型体态、气质与材质质感！\n");
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

        String templateVersion = "character-identity-prompt-v2";
        String contextFingerprint = calculateCharacterVisualFingerprint(dto, templateVersion, skillContext.fingerprint());

        return AssetPromptPackageVO.builder()
                .assetType("CHARACTER_IDENTITY")
                .assetId(dto.getCharacterId())
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
    public CharacterVisualPromptDeriveVO deriveVisualPrompt(CharacterVisualPromptDeriveDTO dto) {
        AssetPromptPackageVO packageVO = buildVisualPromptPackage(dto);
        ChatModel chatModel = aiModelFactory.getChatModelOrDefault(dto.getProviderId(), dto.getModelCode());

        try {
            String raw = invokeApiWithSkills(chatModel, packageVO.getSystemPrompt(), packageVO.getUserPrompt(),
                    "character-identity-prompt", String.valueOf(dto.getCharacterId()), dto.getRequiredSkillNames());
            if (StringUtils.isNotBlank(raw)) {
                CharacterVisualPromptDeriveVO vo = JsonExtractionUtil.cleanAndParseJson(raw, CharacterVisualPromptDeriveVO.class);
                if (vo != null) {
                    if (StringUtils.isNotBlank(vo.getAppearancePrompt())) {
                        vo.setAppearancePrompt(vo.getAppearancePrompt().trim());
                    }
                    if (StringUtils.isNotBlank(vo.getNegativePrompt())) {
                        vo.setNegativePrompt(vo.getNegativePrompt().trim());
                    }
                    return vo;
                }
            }
        } catch (Exception e) {
            log.error("[CharacterVisualPromptDerive] 调用大模型衍生角色身份提示词失败: {}", e.getMessage(), e);
            throw new BizException("AI 智能衍生角色身份提示词失败: " + e.getMessage());
        }

        throw new BizException("AI 模型未能返回有效的角色身份提示词结构");
    }

    @Override
    public SseEmitter deriveVisualPromptStream(CharacterVisualPromptDeriveDTO dto) {
        SseEmitter emitter = new SseEmitter(180_000L);
        emitter.onTimeout(() -> {
            log.warn("[CharacterVisualPromptDeriveStream] SSE 角色身份提示词衍生请求超时 (180s)");
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        });
        emitter.onError(ex -> log.debug("[CharacterVisualPromptDeriveStream] SSE 客户端断开连接: {}", ex.getMessage()));

        ConcurrentSseBridge sseBridge = new ConcurrentSseBridge(emitter);

        Executors.newVirtualThreadPerTaskExecutor().execute(() -> {
            try {
                sseBridge.sendChunk("⚡ 正在建立 AI 身份提示词推理连接...\n");

                AssetPromptPackageVO packageVO;
                try {
                    packageVO = buildVisualPromptPackage(dto);
                } catch (BizException bizEx) {
                    sseBridge.sendError(bizEx.getMessage());
                    return;
                }

                ChatModel chatModel = aiModelFactory.getChatModelOrDefault(dto.getProviderId(), dto.getModelCode());

                String rawText = invokeApiWithSkills(chatModel, packageVO.getSystemPrompt(), packageVO.getUserPrompt(),
                        "character-identity-prompt", String.valueOf(dto.getCharacterId()), dto.getRequiredSkillNames());
                if (StringUtils.isNotBlank(rawText)) {
                    sseBridge.sendChunk(rawText);
                }
                CharacterVisualPromptDeriveVO vo = JsonExtractionUtil.cleanAndParseJson(rawText, CharacterVisualPromptDeriveVO.class);
                if (vo == null || StringUtils.isBlank(vo.getAppearancePrompt())) {
                    sseBridge.sendError("AI 模型未能生成有效的角色身份外貌提示词");
                    return;
                }
                vo.setAppearancePrompt(vo.getAppearancePrompt().trim());
                if (StringUtils.isNotBlank(vo.getNegativePrompt())) {
                    vo.setNegativePrompt(vo.getNegativePrompt().trim());
                }

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                sseBridge.sendResult(mapper.writeValueAsString(vo));
                sseBridge.complete();
            } catch (Exception e) {
                log.error("[CharacterVisualPromptDeriveStream] 流式衍生角色身份提示词异常: {}", e.getMessage(), e);
                sseBridge.sendError("流式衍生角色身份提示词异常: " + e.getMessage());
            }
        });

        return emitter;
    }

    private String appendSkillContext(String systemPrompt, SkillPromptContext skillContext) {
        if (skillPromptContextService == null || skillContext == null || !skillContext.hasSkills()) {
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


    private ResCharacterVO toVO(ResCharacter entity) {
        ResCharacterVO vo = new ResCharacterVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private ResCharacterOutfitVO toOutfitVO(ResCharacterOutfit entity) {
        ResCharacterOutfitVO vo = new ResCharacterOutfitVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
