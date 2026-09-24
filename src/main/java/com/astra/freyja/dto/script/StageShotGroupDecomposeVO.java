package com.astra.freyja.dto.script;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Stage 2 镜头组拆解结果 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StageShotGroupDecomposeVO implements Serializable {

    /** 归属的情节 ID (如 P001) */
    private String plotId;

    /** 镜头组 ID (如 G001) */
    private String groupId;

    /** 镜头组序号 */
    private Integer groupNo;

    /** 镜头组名称/连续动作描述 */
    private String name;

    /** 导演意图/叙事目的 */
    private String purpose;

    /** 关联场景名称 */
    private String sceneName;

    /** 轻量分镜列表 */
    private List<LightweightShotVO> shots;
}
