package com.astra.freyja.dto.drama;

import lombok.Data;

import java.util.List;

/**
 * 分镜拖拽/批量排序请求 DTO。
 */
@Data
public class DramaShotReorderDTO {

    /** 目标场次 ID (支持跨场次移动) */
    private Long sceneId;

    /** 排序后的分镜 ID 列表 (按期望的先后顺序排列) */
    private List<Long> shotIds;
}
