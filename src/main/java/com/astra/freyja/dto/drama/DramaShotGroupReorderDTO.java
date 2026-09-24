package com.astra.freyja.dto.drama;

import lombok.Data;

import java.util.List;

/**
 * 镜头组内分镜重排或场次内镜头组重排 DTO。
 */
@Data
public class DramaShotGroupReorderDTO {

    /** 归属场次 ID */
    private Long sceneId;

    /** 镜头组 ID 列表 (按期望的显示与时间线顺序) */
    private List<Long> groupIds;
}
