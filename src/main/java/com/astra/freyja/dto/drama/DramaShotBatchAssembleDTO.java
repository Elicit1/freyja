package com.astra.freyja.dto.drama;

import lombok.Data;

import java.util.List;

/**
 * 批量组装分镜 Prompt 请求 DTO。
 */
@Data
public class DramaShotBatchAssembleDTO {

    /** 目标场次 ID (当 shotIds 为空时对整个场次的所有分镜组装) */
    private Long sceneId;

    /** 指定的分镜 ID 列表 (可选) */
    private List<Long> shotIds;
}
