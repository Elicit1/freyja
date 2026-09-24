package com.astra.freyja.dto.res;

import lombok.Data;

import java.util.List;

/**
 * 道具专属生图/视觉提示词 AI 智能衍生请求 DTO。
 */
@Data
public class PropPromptDeriveDTO {

    /** 道具ID (可选，若传入且其它属性为空则自动从数据库读取) */
    private Long propId;

    /** 道具名称 */
    private String name;

    /** 道具类型: KEY_PROP(核心叙事道具), WEAPON(武器), COSTUME_ACCESSORY(服饰配饰), DAILY(日常杂物) */
    private String propType;

    /** 道具中文特征与作用描述 (原著视觉源 SSOT) */
    private String description;

    /** 归属短剧ID (可选) */
    private Long dramaId;

    /** 短剧全局画风预设 (可选，字典 drama_style_preset) */
    private String stylePreset;

    /** 短剧视觉风格基调/导演风格指南 (可选，光影、色彩、镜头质感等自然语言指引) */
    private String styleTone;

    /** 题材画风基调 (兼容字段，如: 电影级写实 Cinematic Realism, 2D 动漫 2D Anime, 赛博朋克 Cyberpunk, 复古胶片 Retro Film) */
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
