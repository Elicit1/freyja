package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 剧本拆解提取的环境场景信息 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecomposedSceneVO {

    @JsonPropertyDescription("局部场景编号，例如: SC001, SC002")
    private String id;

    @JsonPropertyDescription("场景名称，例如: 顶层总裁办公室 / 暴雨夜市街道")
    private String sceneName;

    @JsonPropertyDescription("空间类型: INDOOR(室内), OUTDOOR(室外), STUDIO(影棚), VIRTUAL(虚构)")
    private String sceneType;

    @JsonPropertyDescription("时间时段: DAY(日间), NIGHT(夜间), SUNSET(黄昏), DAWN(拂晓)")
    private String timeOfDay;

    @JsonPropertyDescription("天气/氛围，例如: SUNNY(晴朗), RAINY(暴雨), NEON(霓虹), MOODY(阴郁), CYBERPUNK(赛博朋克)")
    private String weatherAtmosphere;

    @JsonPropertyDescription("地点补充说明与视觉要素")
    private String locationName;

    @JsonPropertyDescription("场景中文背景与空间视觉细节描述 (原著视觉源，如: 极简主义黑白冷色调，巨大的落地窗外是暴雨中的摩天大楼)")
    private String description;

    @JsonPropertyDescription("场景生图英文Prompt (包含空间、陈设与光影一体化描述)，例如: modern luxury office, floor-to-ceiling windows, city skyline view, cinematic dramatic lighting")
    private String scenePrompt;

    @JsonPropertyDescription("若匹配到系统已有场景资产，回填对应已有场景ID，否则为null")
    private Long existingSceneId;
}
