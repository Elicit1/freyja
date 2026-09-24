package com.astra.freyja.service;

import com.astra.freyja.dto.drama.CharacterShotRefInfoVO;
import com.astra.freyja.dto.res.ResolvedCharacterPromptContext;
import com.astra.freyja.dto.res.ResolvedCharacterVisual;

/**
 * 角色与造型视觉资产统一解析服务。
 * 负责在分镜首帧生图、视频渲染、Prompt 组装以及资产候选池装配中提供一致的决策。
 */
public interface CharacterVisualAssetResolver {

    /**
     * 根据角色ID与可选造型ID，解析最终的生图提示词片段与单一参考图。
     *
     * @param characterId 角色ID，必填
     * @param lookId      造型ID，可选 (为 null 或 <= 0 时自动回退启用态默认造型)
     * @return 决议后的角色视觉资产结果
     */
    ResolvedCharacterVisual resolve(Long characterId, Long lookId);

    /**
     * 解析分镜提示词衍生的角色与造型纯净文字上下文，遵循四态排他决策矩阵：
     * 1. 有 lookId：请求字段优先，缺失由造型库补充，严禁使用 appearanceDesc，严禁查询默认造型；
     * 2. 无 lookId 但传了造型字段：视为显式造型，仅使用请求中的造型描述，严禁使用 appearanceDesc；
     * 3. 完全无造型信息：使用角色 appearanceDesc 兜底，严禁自动装配默认造型；
     * 4. lookId 无效或不属于该角色：直接抛出异常，不静默回退。
     *
     * @param ref 分镜角色装配引用信息
     * @return 纯净的角色提示词上下文决议结果
     */
    ResolvedCharacterPromptContext resolvePromptContext(CharacterShotRefInfoVO ref);

    /**
     * 基于具体字段解析分镜提示词衍生的角色与造型纯净文字上下文。
     */
    ResolvedCharacterPromptContext resolvePromptContext(Long characterId, Long lookId,
                                                        String designDesc, String appearancePrompt, String outfitPrompt,
                                                        String actionPrompt, String emotionPrompt, String positionTag);
}

