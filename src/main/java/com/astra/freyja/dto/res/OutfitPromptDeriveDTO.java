package com.astra.freyja.dto.res;

import lombok.Data;

import java.util.List;

/**
 * 角色造型/服装变体提示词 AI 智能衍生请求 DTO。
 */
@Data
public class OutfitPromptDeriveDTO {

    /** 造型ID (可选，若传入且其它属性为空则自动读取) */
    private Long lookId;

    /** 关联角色ID (可选，若传入则自动读取角色基本信息作为上下文) */
    private Long characterId;

    /** 造型名称 (如: 日常便服 / 战术作战服 / 晚礼服) */
    private String outfitName;

    /** 视觉状态类型: BASE/COSTUME/AGE_PHASE/DISGUISE/DAMAGE/CUSTOM */
    private String lookType;

    /** 造型视觉概念描述 (颜色、面料、版型轮廓、配饰配件、穿戴层次、妆发状态) */
    private String designDesc;

    /** 角色名称 (可选上下文) */
    private String characterName;

    /** 角色性别 (可选上下文) */
    private String characterGender;

    /** 角色基础外貌特征描述 (可选上下文) */
    private String characterAppearanceDesc;

    /** 归属短剧ID (可选) */
    private Long dramaId;

    /** 短剧全局画风预设 (可选) */
    private String stylePreset;

    /** 短剧视觉风格基调 (可选) */
    private String styleTone;

    /** AI 供应商 ID (可选) */
    private Long providerId;

    /** AI 模型代码 (可选) */
    private String modelCode;

    /** API 模式下用户明确要求预加载的 Skill 名称。 */
    private List<String> requiredSkillNames;

    /** MANUAL 模式下要展开到复制 Prompt 的 Skill 名称。 */
    private List<String> selectedSkillNames;
}
