package com.astra.freyja.dto.script;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 剧情大纲单元 VO (Stage 1 情节提取输出)。
 * 仅包含轻量大纲与实体引用，严格禁止输出完整分镜。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecomposedPlotVO implements Serializable {

    /** 情节唯一标识 (如 P001, P002) */
    private String plotId;

    /** 顺序号 */
    private Integer sequence;

    /** 情节标题/小标题 */
    private String title;

    /** 情节核心故事摘要 (1~2句话精炼总结) */
    private String summary;

    /** 涉及的人物提及列表 (如 ["林婉儿", "林震天", "年轻女人"]) */
    private List<String> characterRefs;

    /** 涉及的场景/地点列表 (如 ["林府书房", "走廊"]) */
    private List<String> locationRefs;

    /** 涉及的核心道具列表 (如 ["密信", "长剑"]) */
    private List<String> propRefs;

    /** 发生时段 (DAY/NIGHT/SUNSET/DAWN) */
    private String timeOfDay;

    /** 空间类型 (INDOOR/OUTDOOR/STUDIO/VIRTUAL) */
    private String sceneType;

    /** 剧情在原文中的起始偏移 */
    private Integer startTextOffset;

    /** 剧情在原文中的结束偏移 */
    private Integer endTextOffset;

    /** 本段情节对应的原始剧本文本 */
    private String rawText;
}
