package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 大纲树中的场次节点 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SceneTreeVO {

    private Long id;
    private Long episodeId;
    private Integer sceneNo;
    private String name;
    private Long resSceneId;
    private String resSceneName;
    private String resSceneCoverUrl;
    private String sceneType;
    private String timeOfDay;
    private String weatherAtmosphere;
    private String locationName;
    private String summary;
    private Integer sortOrder;

    /** 子镜头组列表 */
    private List<DramaShotGroupVO> shotGroups;

    /** 子分镜列表 (平铺兼容) */
    private List<ShotSummaryVO> shots;
}
