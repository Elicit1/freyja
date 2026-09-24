package com.astra.freyja.dto;

import lombok.Data;

/**
 * AI 提供商分页查询参数。
 */
@Data
public class AiProviderQuery {

    /** 页码，从 1 开始 */
    private Integer pageNum = 1;

    /** 每页条数 */
    private Integer pageSize = 10;

    /** 提供商名称（模糊） */
    private String providerName;

    /** 接入类型（等值） */
    private String providerType;

    /** 状态 0-停用 1-启用 */
    private Integer status;
}