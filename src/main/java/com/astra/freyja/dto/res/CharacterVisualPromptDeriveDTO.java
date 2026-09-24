package com.astra.freyja.dto.res;

import lombok.Data;

import java.util.List;

/**
 * 角色身份层视觉提示词 AI 智能衍生请求 DTO。
 */
@Data
public class CharacterVisualPromptDeriveDTO {

    /** 角色ID (可选，若传入且其它属性为空则自动从数据库读取) */
    private Long characterId;

    /** 角色名称 */
    private String name;

    /** 角色性别: MALE, FEMALE, OTHER, UNKNOWN */
    private String gender;

    /** 年龄段: TEENAGER, YOUTH, MIDDLE_AGED, ELDERLY */
    private String ageGroup;

    /** 角色定位: PROTAGONIST, ANTAGONIST, SUPPORTING, EXTRA */
    private String roleType;

    /** 内在性格与处事原则 (可选) */
    private String personality;

    /** 中文外貌视觉特征描述 (原著外貌真实源 SSOT，必填) */
    private String appearanceDesc;

    /** 归属短剧ID (可选) */
    private Long dramaId;

    /** 短剧全局画风预设 (可选) */
    private String stylePreset;

    /** 短剧视觉风格基调/导演风格指南 (可选) */
    private String styleTone;

    /** 短剧视觉风格/题材 (兼容字段) */
    private String visualStyle;

    /** AI 供应商 ID (可选) */
    private Long providerId;

    /** AI 模型代码 (可选) */
    private String modelCode;

    /** API 模式下用户明确要求预加载的 Skill 名称。 */
    private List<String> requiredSkillNames;

    /** MANUAL 模式下要展开到复制 Prompt 的 Skill 名称。 */
    private List<String> selectedSkillNames;
}
