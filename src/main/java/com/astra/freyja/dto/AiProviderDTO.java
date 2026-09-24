package com.astra.freyja.dto;

import lombok.Data;

/**
 * AI 提供商新增/修改请求体。
 */
@Data
public class AiProviderDTO {

    /** 主键，修改时必传 */
    private Long id;

    /** 提供商编码，唯一 */
    private String providerCode;

    /** 提供商名称 */
    private String providerName;

    /** 接入类型 OPENAI/OLLAMA */
    private String providerType;

    /** API Key，新增必填；修改留空表示保持不变 */
    private String apiKey;

    /** Base URL */
    private String baseUrl;

    /** 请求超时（秒） */
    private Integer timeout;

    /** 最大重试次数 */
    private Integer maxRetries;

    /** 是否熔断 0-关 1-开 */
    private Integer enableBreaker;

    /** 熔断阈值（连续失败次数） */
    private Integer breakerThreshold;

    /** 熔断恢复时间（秒） */
    private Integer breakerTimeout;

    /** 状态 0-停用 1-启用 */
    private Integer status;

    /** 备注 */
    private String remark;
}