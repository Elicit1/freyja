package com.astra.freyja.dto.drama;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 镜头组展示 VO。
 */
@Data
public class DramaShotGroupVO {

    private Long id;
    private Long dramaId;
    private Long episodeId;
    private Long sceneId;
    private Integer groupNo;
    private String name;
    private String purpose;

    /** 组内包含的分镜镜头列表 */
    private List<DramaShotVO> shots = new ArrayList<>();

    /** 组内分镜总数 */
    private Integer shotCount;

    /** 组内已渲染分镜数 */
    private Integer renderedShotCount;

    /** 组内累计预估时长 (秒) */
    private BigDecimal totalDuration;

    private Integer sortOrder;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
