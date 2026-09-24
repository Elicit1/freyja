package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.ResCharacterLookMapper;
import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dao.ResCharacterOutfitMapper;
import com.astra.freyja.dto.res.ResolvedCharacterPromptContext;
import com.astra.freyja.dto.res.ResolvedCharacterVisual;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.entity.ResCharacterLook;
import com.astra.freyja.service.CharacterVisualAssetResolver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色与造型视觉资产决策引擎。
 * 遵循严格的视觉纯正性与单一图源铁律：
 * 1. 明确指定 lookId:
 *    -> 只使用该造型的 Prompt 和图片
 *    -> 该造型没有图片就返回 null，绝对禁止借用其他造型图片！
 * 2. 没有指定 lookId:
 *    -> 查找默认造型 (isDefault=1, status=1)
 *    -> 使用默认造型的 Prompt 和图片 (若默认造型无图则返回 null)
 * 3. 没有默认造型:
 *    -> 只使用人物稳定身份文字 Prompt
 *    -> 不伪造、不借图、不回退其他图片
 */
@Slf4j
@Service
public class CharacterVisualAssetResolverImpl implements CharacterVisualAssetResolver {

    private final ResCharacterMapper characterMapper;
    private final ResCharacterLookMapper lookMapper;

    @Autowired
    public CharacterVisualAssetResolverImpl(ResCharacterMapper characterMapper, ResCharacterLookMapper lookMapper) {
        this.characterMapper = characterMapper;
        this.lookMapper = lookMapper;
        this.fallbackOutfitMapper = null;
    }

    public CharacterVisualAssetResolverImpl(ResCharacterMapper characterMapper, ResCharacterOutfitMapper outfitMapper) {
        this.characterMapper = characterMapper;
        this.lookMapper = null;
        this.fallbackOutfitMapper = outfitMapper;
    }

    private final ResCharacterOutfitMapper fallbackOutfitMapper;

    @Override
    public ResolvedCharacterVisual resolve(Long characterId, Long lookId) {
        if (characterId == null || characterId <= 0) {
            throw new BizException(400, "角色ID不能为空");
        }

        ResCharacter character = characterMapper.selectById(characterId);
        if (character == null) {
            throw new BizException(404, "人物角色不存在: " + characterId);
        }

        ResCharacterLook look = null;
        if (lookId != null && lookId > 0) {
            // 明确指定了造型
            if (lookMapper != null) {
                look = lookMapper.selectById(lookId);
            } else if (fallbackOutfitMapper != null) {
                look = fallbackOutfitMapper.selectById(lookId);
            }
            if (look == null || (look.getStatus() != null && look.getStatus() == 0)) {
                throw new BizException(400, "所选人物造型不存在或已停用，请重新选择造型");
            }
            if (!characterId.equals(look.getCharacterId())) {
                throw new BizException(400, "所选造型不属于指定人物");
            }
        } else {
            // 未明确指定造型，查找启用状态的默认造型
            if (lookMapper != null) {
                look = lookMapper.selectOne(new LambdaQueryWrapper<ResCharacterLook>()
                        .eq(ResCharacterLook::getCharacterId, characterId)
                        .eq(ResCharacterLook::getIsDefault, 1)
                        .eq(ResCharacterLook::getStatus, 1)
                        .last("LIMIT 1"));
            } else if (fallbackOutfitMapper != null) {
                look = fallbackOutfitMapper.selectOne(new LambdaQueryWrapper<com.astra.freyja.entity.ResCharacterOutfit>()
                        .eq(com.astra.freyja.entity.ResCharacterOutfit::getCharacterId, characterId)
                        .eq(com.astra.freyja.entity.ResCharacterOutfit::getIsDefault, 1)
                        .eq(com.astra.freyja.entity.ResCharacterOutfit::getStatus, 1)
                        .last("LIMIT 1"));
            }
        }

        List<String> positiveSegments = new ArrayList<>();
        List<String> negativeSegments = new ArrayList<>();

        // 1. 人物身份基础外貌 Prompt
        if (StringUtils.isNotBlank(character.getAppearancePrompt())) {
            positiveSegments.add(character.getAppearancePrompt().trim());
        }

        // 2. 造型特有外貌与服装 Prompt
        if (look != null) {
            if (StringUtils.isNotBlank(look.getAppearancePrompt())) {
                positiveSegments.add(look.getAppearancePrompt().trim());
            }
            if (StringUtils.isNotBlank(look.getOutfitPrompt())) {
                positiveSegments.add(look.getOutfitPrompt().trim());
            }
        }

        // 3. 负向词合并
        if (StringUtils.isNotBlank(character.getNegativePrompt())) {
            negativeSegments.add(character.getNegativePrompt().trim());
        }
        if (look != null && StringUtils.isNotBlank(look.getNegativePrompt())) {
            negativeSegments.add(look.getNegativePrompt().trim());
        }

        // 4. 参考图决策 (只能是当前造型图，若无图绝不回退或借用任何其他图片)
        String referenceImageUrl = null;
        String referenceRole = null;
        String imageStatus = "MISSING";
        String staleWarning = null;

        if (look != null && StringUtils.isNotBlank(look.getReferenceImageUrl())) {
            referenceImageUrl = look.getReferenceImageUrl();
            referenceRole = "COMBINED";
            imageStatus = StringUtils.defaultIfBlank(look.getImageStatus(), "SYNCED");
            if ("STALE".equalsIgnoreCase(imageStatus)) {
                staleWarning = "造型 [" + look.getLookName() + "] 设定图对应的提示词已修改，当前参考图可能与新描述存在偏差";
            }
        }

        return ResolvedCharacterVisual.builder()
                .character(character)
                .look(look)
                .positivePromptSegments(positiveSegments)
                .negativePromptSegments(negativeSegments)
                .referenceImageUrl(referenceImageUrl)
                .referenceRole(referenceRole)
                .imageStatus(imageStatus)
                .staleWarning(staleWarning)
                .build();
    }

    private ResCharacterLook findLookById(Long lookId) {
        if (lookId == null || lookId <= 0) return null;
        if (lookMapper != null) {
            return lookMapper.selectById(lookId);
        } else if (fallbackOutfitMapper != null) {
            return fallbackOutfitMapper.selectById(lookId);
        }
        return null;
    }

    @Override
    public com.astra.freyja.dto.res.ResolvedCharacterPromptContext resolvePromptContext(com.astra.freyja.dto.drama.CharacterShotRefInfoVO ref) {
        if (ref == null) {
            return null;
        }
        return resolvePromptContext(
                ref.getCharacterId(),
                ref.getLookId(),
                ref.getDesignDesc(),
                ref.getAppearancePrompt(),
                ref.getOutfitPrompt(),
                ref.getActionPrompt(),
                ref.getEmotionPrompt(),
                ref.getPositionTag()
        );
    }

    @Override
    public ResolvedCharacterPromptContext resolvePromptContext(Long characterId, Long lookId,
                                                                                        String designDesc, String appearancePrompt, String outfitPrompt,
                                                                                        String actionPrompt, String emotionPrompt, String positionTag) {
        if (characterId == null || characterId <= 0) {
            throw new BizException(400, "角色ID不能为空");
        }

        ResCharacter character = characterMapper.selectById(characterId);
        if (character == null) {
            return null;
        }

        // 状态 1 & 4: 有 lookId (含无效或归属错误校验)
        if (lookId != null && lookId > 0) {
            ResCharacterLook look = findLookById(lookId);
            if (look == null || (look.getStatus() != null && look.getStatus() == 0)) {
                throw new BizException(400, "所选人物造型不存在或已停用: " + lookId);
            }
            if (!characterId.equals(look.getCharacterId())) {
                throw new BizException(400, "所选造型不属于指定人物: lookId=" + lookId + ", characterId=" + characterId);
            }

            // 字段合并优先级: 请求中的字段 > lookId 对应数据库造型字段
            String finalDesignDesc = StringUtils.isNotBlank(designDesc) ? designDesc.trim() : StringUtils.trimToNull(look.getDesignDesc());
            String finalAppearancePrompt = StringUtils.isNotBlank(appearancePrompt) ? appearancePrompt.trim() : StringUtils.trimToNull(look.getAppearancePrompt());
            String finalOutfitPrompt = StringUtils.isNotBlank(outfitPrompt) ? outfitPrompt.trim() : StringUtils.trimToNull(look.getOutfitPrompt());

            return ResolvedCharacterPromptContext.builder()
                    .character(character)
                    .look(look)
                    .lookId(lookId)
                    .hasLook(true)
                    .appearanceDesc(null) // 严禁使用角色 appearanceDesc，严禁查询默认造型
                    .designDesc(finalDesignDesc)
                    .appearancePrompt(finalAppearancePrompt)
                    .outfitPrompt(finalOutfitPrompt)
                    .actionPrompt(StringUtils.trimToNull(actionPrompt))
                    .emotionPrompt(StringUtils.trimToNull(emotionPrompt))
                    .positionTag(StringUtils.trimToNull(positionTag))
                    .build();
        }

        // 状态 2: 无 lookId，但传了 designDesc/appearancePrompt/outfitPrompt
        boolean hasExplicitLookFields = StringUtils.isNotBlank(designDesc)
                || StringUtils.isNotBlank(appearancePrompt)
                || StringUtils.isNotBlank(outfitPrompt);

        if (hasExplicitLookFields) {
            return ResolvedCharacterPromptContext.builder()
                    .character(character)
                    .look(null)
                    .lookId(null)
                    .hasLook(true)
                    .appearanceDesc(null) // 严禁使用角色 appearanceDesc，严禁查询默认造型
                    .designDesc(StringUtils.trimToNull(designDesc))
                    .appearancePrompt(StringUtils.trimToNull(appearancePrompt))
                    .outfitPrompt(StringUtils.trimToNull(outfitPrompt))
                    .actionPrompt(StringUtils.trimToNull(actionPrompt))
                    .emotionPrompt(StringUtils.trimToNull(emotionPrompt))
                    .positionTag(StringUtils.trimToNull(positionTag))
                    .build();
        }

        // 状态 3: 完全没有造型信息 (使用角色 appearanceDesc 兜底，严禁自动装配默认造型)
        return ResolvedCharacterPromptContext.builder()
                .character(character)
                .look(null)
                .lookId(null)
                .hasLook(false)
                .appearanceDesc(StringUtils.trimToNull(character.getAppearanceDesc()))
                .designDesc(null)
                .appearancePrompt(null)
                .outfitPrompt(null)
                .actionPrompt(StringUtils.trimToNull(actionPrompt))
                .emotionPrompt(StringUtils.trimToNull(emotionPrompt))
                .positionTag(StringUtils.trimToNull(positionTag))
                .build();
    }
}
