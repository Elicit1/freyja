package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 剧本拆解提取的剧集下属场次信息 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecomposedEpisodeSceneVO {

    @JsonPropertyDescription("场次序号，从1递增")
    private Integer sceneNo;

    @JsonPropertyDescription("场次名称，例如: 场次1-酒店大堂主角受辱")
    private String sceneName;

    @JsonPropertyDescription("绑定的场景资产编号 (如 SC001)")
    private String sceneId;

    @JsonPropertyDescription("绑定的系统场景资产主键ID (res_scene.id)")
    private Long resSceneId;

    @JsonPropertyDescription("场次剧情摘要")
    private String summary;

    @JsonPropertyDescription("本场原始剧本文本与对白摘录")
    private String scriptContent;

    @JsonPropertyDescription("本场次下划分的连续镜头组列表 (ShotGroup)")
    private List<DecomposedShotGroupVO> shotGroups;

    @JsonPropertyDescription("本场次下划分的详细分镜卡片列表 (平铺兼容)")
    private List<DecomposedShotVO> shots;
}
