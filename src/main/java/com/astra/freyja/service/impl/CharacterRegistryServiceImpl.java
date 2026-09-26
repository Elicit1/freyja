package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.dto.script.CharacterRegistryItemVO;
import com.astra.freyja.dto.script.DecomposedCharacterVO;
import com.astra.freyja.entity.*;
import com.astra.freyja.entity.enums.AliasType;
import com.astra.freyja.entity.enums.EvidenceType;
import com.astra.freyja.entity.enums.IdentityStatus;
import com.astra.freyja.entity.enums.ResolutionStatus;
import com.astra.freyja.service.CharacterRegistryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 角色注册表管理与实体合并服务实现类。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterRegistryServiceImpl implements CharacterRegistryService {

    private final ResCharacterMapper characterMapper;
    private final ResCharacterAliasMapper aliasMapper;
    private final ResCharacterEvidenceMapper evidenceMapper;
    private final ResCharacterResolutionMapper resolutionMapper;
    private final ResCharacterOutfitMapper outfitMapper;
    private final DramaShotMapper shotMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<CharacterRegistryItemVO> buildRegistryContext(Long dramaId) {
        if (dramaId == null) {
            return Collections.emptyList();
        }

        List<ResCharacter> characters = characterMapper.selectList(new LambdaQueryWrapper<ResCharacter>()
                .eq(ResCharacter::getDramaId, dramaId)
                .ne(ResCharacter::getIdentityStatus, IdentityStatus.MERGED.name())
                .eq(ResCharacter::getStatus, 1)
                .orderByAsc(ResCharacter::getSortOrder)
                .orderByAsc(ResCharacter::getId));

        if (characters.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> charIds = characters.stream().map(ResCharacter::getId).toList();

        List<ResCharacterAlias> aliases = aliasMapper.selectList(new LambdaQueryWrapper<ResCharacterAlias>()
                .in(ResCharacterAlias::getCharacterId, charIds)
                .eq(ResCharacterAlias::getStatus, 1));

        Map<Long, List<String>> aliasMap = aliases.stream()
                .collect(Collectors.groupingBy(
                        ResCharacterAlias::getCharacterId,
                        Collectors.mapping(ResCharacterAlias::getAlias, Collectors.toList())
                ));

        List<CharacterRegistryItemVO> items = new ArrayList<>(characters.size());
        for (ResCharacter c : characters) {
            Set<String> aliasSet = new LinkedHashSet<>();
            if (StringUtils.isNotBlank(c.getCanonicalName())) {
                aliasSet.add(c.getCanonicalName().trim());
            }
            if (StringUtils.isNotBlank(c.getName())) {
                aliasSet.add(c.getName().trim());
            }
            if (StringUtils.isNotBlank(c.getDisplayName())) {
                aliasSet.add(c.getDisplayName().trim());
            }
            if (aliasMap.containsKey(c.getId())) {
                aliasSet.addAll(aliasMap.get(c.getId()));
            }

            items.add(CharacterRegistryItemVO.builder()
                    .id(c.getId())
                    .canonicalName(c.getCanonicalName())
                    .displayName(c.getDisplayName() != null ? c.getDisplayName() : c.getName())
                    .gender(c.getGender())
                    .ageGroup(c.getAgeGroup())
                    .roleType(c.getRoleType())
                    .identityStatus(c.getIdentityStatus() != null ? c.getIdentityStatus() : IdentityStatus.UNKNOWN.name())
                    .appearanceDesc(c.getAppearanceDesc())
                    .appearancePrompt(c.getAppearancePrompt())
                    .personality(c.getPersonality())
                    .triggerWords(c.getTriggerWords())
                    .aliases(new ArrayList<>(aliasSet))
                    .build());
        }

        return items;
    }

    @Override
    public String formatRegistryForPrompt(Long dramaId) {
        return formatRegistryItemsForPrompt(buildRegistryContext(dramaId));
    }

    static String formatRegistryItemsForPrompt(List<CharacterRegistryItemVO> list) {
        if (list.isEmpty()) {
            return "【无已有角色，本次拆解提取的人物为初次登场】";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【已有角色注册表 (Character Registry) - 共 ").append(list.size()).append(" 位已有角色实体】:\n");
        for (CharacterRegistryItemVO item : list) {
            sb.append(String.format("- [characterId: %d] 正式规范名: %s, 初次展示称谓: %s, 身份状态: %s, 性别: %s, 年龄段: %s, 角色定位: %s\n",
                    item.getId(),
                    StringUtils.defaultIfBlank(item.getCanonicalName(), "无(PARTIAL)"),
                    StringUtils.defaultIfBlank(item.getDisplayName(), "未命名"),
                    item.getIdentityStatus(),
                    StringUtils.defaultIfBlank(item.getGender(), "未知"),
                    StringUtils.defaultIfBlank(item.getAgeGroup(), "未知"),
                    StringUtils.defaultIfBlank(item.getRoleType(), "配角")
            ));
            if (item.getAliases() != null && !item.getAliases().isEmpty()) {
                sb.append("  * 历史称谓与别名: ").append(String.join(", ", item.getAliases())).append("\n");
            }
            // 外貌自由文本可能混有造型和衣着，角色消歧只传结构化身份信息。
        }
        sb.append("【资料说明】: 以上是当前短剧已登记角色及其 ID、名称、别名和资料。");
        return sb.toString();
    }

    @Override
    public Optional<ResCharacter> findByCanonicalName(Long dramaId, String canonicalName) {
        if (dramaId == null || StringUtils.isBlank(canonicalName)) {
            return Optional.empty();
        }
        ResCharacter character = characterMapper.selectOne(new LambdaQueryWrapper<ResCharacter>()
                .eq(ResCharacter::getDramaId, dramaId)
                .eq(ResCharacter::getCanonicalName, canonicalName.trim())
                .ne(ResCharacter::getIdentityStatus, IdentityStatus.MERGED.name())
                .last("LIMIT 1"));
        return Optional.ofNullable(character);
    }

    @Override
    public List<ResCharacter> findByAlias(Long dramaId, String alias) {
        if (dramaId == null || StringUtils.isBlank(alias)) {
            return Collections.emptyList();
        }
        List<ResCharacterAlias> aliasRecords = aliasMapper.selectList(new LambdaQueryWrapper<ResCharacterAlias>()
                .eq(ResCharacterAlias::getDramaId, dramaId)
                .eq(ResCharacterAlias::getAlias, alias.trim())
                .eq(ResCharacterAlias::getStatus, 1));

        if (aliasRecords.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> charIds = aliasRecords.stream().map(ResCharacterAlias::getCharacterId).distinct().toList();
        return characterMapper.selectList(new LambdaQueryWrapper<ResCharacter>()
                .in(ResCharacter::getId, charIds)
                .ne(ResCharacter::getIdentityStatus, IdentityStatus.MERGED.name())
                .eq(ResCharacter::getStatus, 1));
    }

    @Override
    public List<CharacterRegistryItemVO> findCandidates(Long dramaId, String gender) {
        List<CharacterRegistryItemVO> all = buildRegistryContext(dramaId);
        if (StringUtils.isBlank(gender) || "UNKNOWN".equalsIgnoreCase(gender)) {
            return all;
        }
        return all.stream()
                .filter(c -> StringUtils.isBlank(c.getGender()) || "UNKNOWN".equalsIgnoreCase(c.getGender()) || gender.equalsIgnoreCase(c.getGender()))
                .toList();
    }

    @Override
    public ResCharacterVO getCharacterContext(Long characterId) {
        if (characterId == null) {
            return null;
        }
        ResCharacter character = characterMapper.selectById(characterId);
        if (character == null) {
            return null;
        }
        ResCharacterVO vo = new ResCharacterVO();
        BeanUtils.copyProperties(character, vo);

        List<ResCharacterAliasVO> aliases = listAliasesByCharacterId(characterId);
        vo.setAliases(aliases);

        List<ResCharacterEvidenceVO> evidences = listEvidencesByCharacterId(characterId);
        vo.setEvidences(evidences);

        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindMention(CharacterResolutionContext context, Long characterId) {
        if (context == null || characterId == null) {
            return;
        }
        ResCharacter character = characterMapper.selectById(characterId);
        if (character == null) {
            return;
        }

        // 自动将提及词加入别名表
        if (StringUtils.isNotBlank(context.getMention())) {
            ResCharacterAliasDTO aliasDTO = new ResCharacterAliasDTO();
            aliasDTO.setDramaId(context.getDramaId());
            aliasDTO.setCharacterId(characterId);
            aliasDTO.setAlias(context.getMention().trim());
            aliasDTO.setAliasType(AliasType.DESCRIPTION.name());
            aliasDTO.setSourceEpisodeId(context.getEpisodeId());
            aliasDTO.setConfidence(BigDecimal.valueOf(0.95));
            aliasDTO.setStatus(1);
            addAlias(aliasDTO);
        }

        // 记录消歧依据
        if (StringUtils.isNotBlank(context.getSurroundingText())) {
            recordEvidence(
                    context.getDramaId(),
                    characterId,
                    context.getEpisodeId(),
                    context.getSceneId(),
                    context.getSurroundingText(),
                    EvidenceType.CONTEXT_MATCH.name(),
                    BigDecimal.valueOf(0.95),
                    "剧本消歧提及词绑定: " + context.getMention()
            );
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmIdentity(Long characterId, String canonicalName, String evidenceText, String evidenceType) {
        if (characterId == null || StringUtils.isBlank(canonicalName)) {
            return;
        }
        ResCharacter character = characterMapper.selectById(characterId);
        if (character == null) {
            return;
        }

        String trimmedName = canonicalName.trim();
        character.setCanonicalName(trimmedName);
        character.setName(trimmedName);
        character.setIdentityStatus(IdentityStatus.CONFIRMED.name());
        characterMapper.updateById(character);

        // 新增正式姓名别名
        ResCharacterAliasDTO aliasDTO = new ResCharacterAliasDTO();
        aliasDTO.setDramaId(character.getDramaId());
        aliasDTO.setCharacterId(characterId);
        aliasDTO.setAlias(trimmedName);
        aliasDTO.setAliasType(AliasType.NAME.name());
        aliasDTO.setConfidence(BigDecimal.ONE);
        aliasDTO.setStatus(1);
        addAlias(aliasDTO);

        // 记录身份确认证据
        recordEvidence(
                character.getDramaId(),
                characterId,
                null,
                null,
                StringUtils.defaultIfBlank(evidenceText, "确认正式规范姓名: " + trimmedName),
                StringUtils.defaultIfBlank(evidenceType, EvidenceType.SELF_INTRODUCTION.name()),
                BigDecimal.ONE,
                "角色身份由临时称谓正式升级为已确认姓名"
        );
        log.info("[CharacterRegistry] 角色 ID={} 身份确认为: {}", characterId, trimmedName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void mergeCharacter(CharacterMergeDTO dto) {
        if (dto == null || dto.getSourceCharacterId() == null || dto.getTargetCharacterId() == null) {
            throw new BizException("合并源角色 ID 与目标角色 ID 不能为空");
        }
        Long sourceId = dto.getSourceCharacterId();
        Long targetId = dto.getTargetCharacterId();

        if (sourceId.equals(targetId)) {
            throw new BizException("不能将角色合并到自身");
        }

        ResCharacter source = characterMapper.selectById(sourceId);
        if (source == null) {
            throw new BizException("源角色不存在: " + sourceId);
        }
        ResCharacter target = characterMapper.selectById(targetId);
        if (target == null) {
            throw new BizException("目标角色不存在: " + targetId);
        }

        log.info("[CharacterRegistry] 开始合并角色: Source ID={} ({}) -> Target ID={} ({})",
                sourceId, source.getName(), targetId, target.getName());

        // 1. 迁移别名 (res_character_alias)
        List<ResCharacterAlias> sourceAliases = aliasMapper.selectList(new LambdaQueryWrapper<ResCharacterAlias>()
                .eq(ResCharacterAlias::getCharacterId, sourceId));
        if (sourceAliases != null && !sourceAliases.isEmpty()) {
            for (ResCharacterAlias a : sourceAliases) {
                // 检查 target 是否已存在该别名
                Long count = aliasMapper.selectCount(new LambdaQueryWrapper<ResCharacterAlias>()
                        .eq(ResCharacterAlias::getCharacterId, targetId)
                        .eq(ResCharacterAlias::getAlias, a.getAlias()));
                if (count == null || count == 0) {
                    a.setCharacterId(targetId);
                    aliasMapper.updateById(a);
                } else {
                    aliasMapper.deleteById(a.getId());
                }
            }
        }
        // 将 source 的原名与 display_name 也作为 target 的别名登记
        if (StringUtils.isNotBlank(source.getName())) {
            ResCharacterAliasDTO aDto = new ResCharacterAliasDTO();
            aDto.setDramaId(target.getDramaId());
            aDto.setCharacterId(targetId);
            aDto.setAlias(source.getName());
            aDto.setAliasType(AliasType.NAME.name());
            aDto.setConfidence(BigDecimal.ONE);
            addAlias(aDto);
        }
        if (StringUtils.isNotBlank(source.getDisplayName()) && !source.getDisplayName().equals(source.getName())) {
            ResCharacterAliasDTO aDto = new ResCharacterAliasDTO();
            aDto.setDramaId(target.getDramaId());
            aDto.setCharacterId(targetId);
            aDto.setAlias(source.getDisplayName());
            aDto.setAliasType(AliasType.DESCRIPTION.name());
            aDto.setConfidence(BigDecimal.ONE);
            addAlias(aDto);
        }

        // 2. 迁移身份证据链 (res_character_evidence)
        evidenceMapper.update(null, new LambdaUpdateWrapper<ResCharacterEvidence>()
                .set(ResCharacterEvidence::getCharacterId, targetId)
                .eq(ResCharacterEvidence::getCharacterId, sourceId));

        // 3. 迁移服装造型 (res_character_outfit)
        List<ResCharacterOutfit> sourceOutfits = outfitMapper.selectList(new LambdaQueryWrapper<ResCharacterOutfit>()
                .eq(ResCharacterOutfit::getCharacterId, sourceId));
        if (sourceOutfits != null && !sourceOutfits.isEmpty()) {
            for (ResCharacterOutfit outfit : sourceOutfits) {
                outfit.setCharacterId(targetId);
                outfit.setIsDefault(0); // 迁移过来的造型不覆盖目标角色的默认造型
                outfitMapper.updateById(outfit);
            }
        }

        // 4. 扫描并更新分镜中的角色引用 (drama_shot.character_refs_json)
        List<DramaShot> allShots = shotMapper.selectList(new LambdaQueryWrapper<DramaShot>()
                .isNotNull(DramaShot::getCharacterRefsJson)
                .ne(DramaShot::getCharacterRefsJson, ""));

        if (allShots != null) {
            for (DramaShot shot : allShots) {
                String json = shot.getCharacterRefsJson();
                if (StringUtils.isBlank(json) || !json.contains(String.valueOf(sourceId))) {
                    continue;
                }
                try {
                    List<CharacterShotRefDTO> refs = objectMapper.readValue(json, new TypeReference<List<CharacterShotRefDTO>>() {});
                    boolean modified = false;
                    for (CharacterShotRefDTO ref : refs) {
                        if (sourceId.equals(ref.getCharacterId())) {
                            ref.setCharacterId(targetId);
                            modified = true;
                        }
                    }
                    if (modified) {
                        shot.setCharacterRefsJson(objectMapper.writeValueAsString(refs));
                        shotMapper.updateById(shot);
                    }
                } catch (Exception e) {
                    log.warn("[CharacterRegistry] 更新分镜 characterRefsJson 失败, shotId={}", shot.getId(), e);
                }
            }
        }

        // 5. 更新未决消歧项 (res_character_resolution)
        resolutionMapper.update(null, new LambdaUpdateWrapper<ResCharacterResolution>()
                .set(ResCharacterResolution::getResolvedCharacterId, targetId)
                .set(ResCharacterResolution::getResolutionStatus, ResolutionStatus.RESOLVED.name())
                .eq(ResCharacterResolution::getResolvedCharacterId, sourceId));

        // 6. 为 target 角色沉淀一条合并证据
        recordEvidence(
                target.getDramaId(),
                targetId,
                null,
                null,
                "合并吸收角色 [" + source.getName() + "] (ID=" + sourceId + ")，合并原因: " + StringUtils.defaultIfBlank(dto.getReason(), "人工合并"),
                EvidenceType.RELATIONSHIP.name(),
                BigDecimal.ONE,
                "管理员执行角色合并迁移"
        );

        // 7. 更新 source 角色状态为 MERGED (不物理删除)
        source.setIdentityStatus(IdentityStatus.MERGED.name());
        source.setMergedToId(targetId);
        source.setStatus(0);
        characterMapper.updateById(source);

        log.info("[CharacterRegistry] 角色合并完成: Source ID={} -> Target ID={}", sourceId, targetId);
    }

    @Override
    public List<ResCharacterAliasVO> listAliasesByCharacterId(Long characterId) {
        if (characterId == null) {
            return Collections.emptyList();
        }
        List<ResCharacterAlias> list = aliasMapper.selectList(new LambdaQueryWrapper<ResCharacterAlias>()
                .eq(ResCharacterAlias::getCharacterId, characterId)
                .eq(ResCharacterAlias::getStatus, 1)
                .orderByAsc(ResCharacterAlias::getId));

        return list.stream().map(a -> {
            ResCharacterAliasVO vo = new ResCharacterAliasVO();
            BeanUtils.copyProperties(a, vo);
            return vo;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addAlias(ResCharacterAliasDTO dto) {
        if (dto == null || dto.getCharacterId() == null || StringUtils.isBlank(dto.getAlias())) {
            throw new BizException("角色别名与角色 ID 不能为空");
        }
        String aliasStr = dto.getAlias().trim();

        // 查重 (同一角色同一别名不重复插)
        ResCharacterAlias existing = aliasMapper.selectOne(new LambdaQueryWrapper<ResCharacterAlias>()
                .eq(ResCharacterAlias::getCharacterId, dto.getCharacterId())
                .eq(ResCharacterAlias::getAlias, aliasStr)
                .last("LIMIT 1"));

        if (existing != null) {
            if (existing.getStatus() != 1) {
                existing.setStatus(1);
                aliasMapper.updateById(existing);
            }
            return existing.getId();
        }

        ResCharacterAlias alias = new ResCharacterAlias();
        BeanUtils.copyProperties(dto, alias);
        alias.setAlias(aliasStr);
        alias.setAliasType(StringUtils.defaultIfBlank(dto.getAliasType(), AliasType.NAME.name()));
        alias.setConfidence(dto.getConfidence() != null ? dto.getConfidence() : BigDecimal.ONE);
        alias.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);
        aliasMapper.insert(alias);
        return alias.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAlias(ResCharacterAliasDTO dto) {
        if (dto == null || dto.getId() == null) {
            throw new BizException("别名 ID 不能为空");
        }
        ResCharacterAlias existing = aliasMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BizException("角色别名不存在: " + dto.getId());
        }

        if (StringUtils.isNotBlank(dto.getAlias())) {
            String aliasStr = dto.getAlias().trim();
            // 查重：同一角色下不能与其他别名记录重名
            ResCharacterAlias duplicate = aliasMapper.selectOne(new LambdaQueryWrapper<ResCharacterAlias>()
                    .eq(ResCharacterAlias::getCharacterId, existing.getCharacterId())
                    .eq(ResCharacterAlias::getAlias, aliasStr)
                    .ne(ResCharacterAlias::getId, dto.getId())
                    .last("LIMIT 1"));
            if (duplicate != null) {
                throw new BizException("该角色已存在相同别名: " + aliasStr);
            }
            existing.setAlias(aliasStr);
        }

        if (StringUtils.isNotBlank(dto.getAliasType())) {
            existing.setAliasType(dto.getAliasType().trim());
        }
        if (dto.getConfidence() != null) {
            existing.setConfidence(dto.getConfidence());
        }
        if (dto.getStatus() != null) {
            existing.setStatus(dto.getStatus());
        }
        if (dto.getRemark() != null) {
            existing.setRemark(dto.getRemark());
        }
        aliasMapper.updateById(existing);
        log.info("[CharacterRegistry] 更新角色别名成功: id={}, alias={}, type={}", existing.getId(), existing.getAlias(), existing.getAliasType());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeAlias(Long aliasId) {
        if (aliasId == null) return;
        aliasMapper.deleteById(aliasId);
    }

    @Override
    public List<ResCharacterEvidenceVO> listEvidencesByCharacterId(Long characterId) {
        if (characterId == null) {
            return Collections.emptyList();
        }
        List<ResCharacterEvidence> list = evidenceMapper.selectList(new LambdaQueryWrapper<ResCharacterEvidence>()
                .eq(ResCharacterEvidence::getCharacterId, characterId)
                .eq(ResCharacterEvidence::getStatus, 1)
                .orderByDesc(ResCharacterEvidence::getId));

        return list.stream().map(e -> {
            ResCharacterEvidenceVO vo = new ResCharacterEvidenceVO();
            BeanUtils.copyProperties(e, vo);
            return vo;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persistUnresolvedPreview(List<DecomposedCharacterVO> characters, Long dramaId, Long episodeId) {
        if (characters == null) return;
        for (DecomposedCharacterVO character : characters) {
            if (character == null || !IdentityStatus.UNRESOLVED.name().equals(character.getIdentityStatus())) continue;
            ResCharacterResolution resolution = new ResCharacterResolution();
            resolution.setDramaId(dramaId);
            resolution.setEpisodeId(episodeId);
            resolution.setSourceMention(StringUtils.firstNonBlank(
                    character.getCanonicalName(), character.getName(), character.getDisplayName()));
            resolution.setSourceText(character.getEvidenceText());
            try {
                resolution.setCandidateCharacterIds(objectMapper.writeValueAsString(character.getCandidateCharacterIds()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("序列化未决角色候选失败", e);
            }
            resolution.setResolutionStatus(ResolutionStatus.UNRESOLVED.name());
            resolution.setConfidence(BigDecimal.valueOf(character.getConfidence() != null ? character.getConfidence() : 0.50));
            resolutionMapper.insert(resolution);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long recordEvidence(Long dramaId, Long characterId, Long episodeId, Long sceneId,
                               String sourceText, String evidenceType, BigDecimal confidence, String reason) {
        if (characterId == null || StringUtils.isBlank(sourceText)) {
            return null;
        }
        ResCharacterEvidence evidence = new ResCharacterEvidence();
        evidence.setDramaId(dramaId);
        evidence.setCharacterId(characterId);
        evidence.setEpisodeId(episodeId);
        evidence.setSceneId(sceneId);
        evidence.setSourceText(sourceText);
        evidence.setEvidenceType(StringUtils.defaultIfBlank(evidenceType, EvidenceType.EXPLICIT_REFERENCE.name()));
        evidence.setConfidence(confidence != null ? confidence : BigDecimal.ONE);
        evidence.setReason(reason);
        evidence.setStatus(1);
        evidenceMapper.insert(evidence);
        return evidence.getId();
    }

    @Override
    public List<ResCharacterResolutionVO> listPendingResolutions(Long dramaId) {
        List<ResCharacterResolution> list = resolutionMapper.selectList(new LambdaQueryWrapper<ResCharacterResolution>()
                .eq(ResCharacterResolution::getResolutionStatus, ResolutionStatus.UNRESOLVED.name())
                .eq(dramaId != null, ResCharacterResolution::getDramaId, dramaId)
                .orderByDesc(ResCharacterResolution::getId));

        return list.stream().map(r -> {
            ResCharacterResolutionVO vo = new ResCharacterResolutionVO();
            BeanUtils.copyProperties(r, vo);
            return vo;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resolveResolution(ResCharacterResolutionDTO dto) {
        if (dto == null || dto.getResolutionId() == null) {
            throw new BizException("决议项 ID 不能为空");
        }
        ResCharacterResolution resolution = resolutionMapper.selectById(dto.getResolutionId());
        if (resolution == null) {
            throw new BizException("消歧决议项不存在");
        }

        if ("RESOLVE".equalsIgnoreCase(dto.getAction())) {
            if (dto.getTargetCharacterId() == null) {
                throw new BizException("决议绑定角色 ID 不能为空");
            }
            resolution.setResolvedCharacterId(dto.getTargetCharacterId());
            resolution.setResolutionStatus(ResolutionStatus.RESOLVED.name());
            resolution.setReason(dto.getRemark());
            resolutionMapper.updateById(resolution);

            // 自动为目标角色追加该别名
            ResCharacterAliasDTO aliasDTO = new ResCharacterAliasDTO();
            aliasDTO.setDramaId(resolution.getDramaId());
            aliasDTO.setCharacterId(dto.getTargetCharacterId());
            aliasDTO.setAlias(resolution.getSourceMention());
            aliasDTO.setAliasType(AliasType.DESCRIPTION.name());
            aliasDTO.setConfidence(BigDecimal.ONE);
            addAlias(aliasDTO);

            // 记录决议证据
            recordEvidence(
                    resolution.getDramaId(),
                    dto.getTargetCharacterId(),
                    resolution.getEpisodeId(),
                    resolution.getSceneId(),
                    resolution.getSourceText(),
                    EvidenceType.EXPLICIT_REFERENCE.name(),
                    BigDecimal.ONE,
                    "人工消歧决议确认: " + StringUtils.defaultIfBlank(dto.getRemark(), "人工审核通过")
            );
        } else if ("IGNORE".equalsIgnoreCase(dto.getAction())) {
            resolution.setResolutionStatus(ResolutionStatus.IGNORED.name());
            resolution.setReason(dto.getRemark());
            resolutionMapper.updateById(resolution);
        }
    }
}
