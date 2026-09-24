package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 剧本拆解提取的连续镜头组 VO (ShotGroup)。
 * 承载一段连续动作链、连续对白或完整视觉事件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecomposedShotGroupVO {

    @JsonPropertyDescription("镜头组序号，从1递增")
    private Integer groupNo;

    @JsonPropertyDescription("镜头组名称/连续动作概述 (如: 葛明进入办公室并发现杜宁)")
    private String name;

    @JsonPropertyDescription("导演叙事意图/戏剧目的 (如: 建立人物空间关系并制造初次冲突)")
    private String purpose;

    @JsonPropertyDescription("本镜头组内包含的连续分镜镜头列表 (严禁机械切碎，按叙事节奏与动作对白合理规划时长与景别)")
    private List<DecomposedShotVO> shots;
}
