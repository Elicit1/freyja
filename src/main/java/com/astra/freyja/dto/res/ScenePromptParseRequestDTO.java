package com.astra.freyja.dto.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 外部 AI 返回场景提示词解析与校验请求 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScenePromptParseRequestDTO {

    /** 场景ID (可选) */
    private Long sceneId;

    /** 归属短剧ID (可选) */
    private Long dramaId;

    /** 场景名称 */
    private String name;

    /** 空间类型: INDOOR, OUTDOOR, VIRTUAL, OTHER */
    private String sceneType;

    /** 时间时段 */
    private String timeOfDay;

    /** 天气与氛围 */
    private String weatherAtmosphere;

    /** 中文环境与陈设视觉细节描述 */
    private String description;

    /** 短剧画风预设 */
    private String stylePreset;

    /** 视觉风格基调/导演指南 */
    private String styleTone;

    /** 兼容字段 */
    private String visualStyle;

    /** 外部 AI 返回的原始纯文本 (支持包含 ```json 代码块或前后说明) */
    private String rawResponse;

    /** 导出任务包时的上下文指纹 (用于比对校验场景设定是否发生变动) */
    private String contextFingerprint;

    /** 导出任务包时用户选择并展开的 Skill 名称。 */
    private List<String> selectedSkillNames;
}
