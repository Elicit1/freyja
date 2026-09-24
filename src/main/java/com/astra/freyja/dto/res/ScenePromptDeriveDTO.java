package com.astra.freyja.dto.res;

import lombok.Data;

import java.util.List;

/**
 * 场景多模态专属提示词 AI 智能衍生请求 DTO。
 */
@Data
public class ScenePromptDeriveDTO {

    /** 场景ID (可选，若传入且其它属性为空则自动从数据库读取) */
    private Long sceneId;

    /** 场景名称 */
    private String name;

    /** 空间类型: INDOOR(室内), OUTDOOR(室外), STUDIO(影棚), VIRTUAL(虚构) */
    private String sceneType;

    /** 时间时段: DAY(日间), NIGHT(夜间), SUNSET(黄昏), DAWN(拂晓) */
    private String timeOfDay;

    /** 天气氛围 (如 SUNNY, RAINY, NEON, FOGGY, MOODY, SNOWY) */
    private String weatherAtmosphere;

    /** 场景中文背景与空间视觉细节描述 (原著视觉源 SSOT) */
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
