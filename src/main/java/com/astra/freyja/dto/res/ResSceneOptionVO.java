package com.astra.freyja.dto.res;

import lombok.Data;

/**
 * 场景下拉选项 VO。
 */
@Data
public class ResSceneOptionVO {

    private Long id;

    private Long dramaId;

    private String name;

    private String coverUrl;

    private String sceneType;

    private String timeOfDay;

    private String weatherAtmosphere;

    private String scenePrompt;

    private String loraName;

    /** 空间参考图 URL */
    private String referenceImageUrl;
}
