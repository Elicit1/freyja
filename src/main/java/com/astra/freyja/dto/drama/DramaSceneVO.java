package com.astra.freyja.dto.drama;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 情景场次展示 VO。
 */
@Data
public class DramaSceneVO {

    private Long id;
    private Long dramaId;
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
    private String scriptContent;
    private Integer sortOrder;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 子分镜数量 */
    private Integer shotCount;

    /** 已渲染分镜数量 */
    private Integer renderedShotCount;
}
