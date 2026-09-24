package com.astra.freyja.dto;

import lombok.Data;

/**
 * AI 模型分页查询参数。
 */
@Data
public class AiModelQuery {

    /** 页码，从 1 开始 */
    private Integer pageNum = 1;

    /** 每页条数 */
    private Integer pageSize = 10;

    /** 归属提供商 ID（等值） */
    private Long providerId;

    /** 模型名称（模糊） */
    private String modelName;

    /** 模型类型（等值） */
    private String modelType;

    /** 状态 0-停用 1-启用 */
    private Integer status;
}