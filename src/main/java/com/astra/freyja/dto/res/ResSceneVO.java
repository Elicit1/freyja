package com.astra.freyja.dto.res;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 场景环境详情响应 VO。
 */
@Data
public class ResSceneVO {

    private Long id;

    private Long dramaId;

    private String name;

    private String description;

    private String coverUrl;

    private String sceneType;

    private String timeOfDay;

    private String weatherAtmosphere;

    private String scenePrompt;
    private String negativePrompt;

    private String loraName;

    private BigDecimal loraWeight;

    private String referenceImageUrl;

    private Integer sortOrder;

    private Integer status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
