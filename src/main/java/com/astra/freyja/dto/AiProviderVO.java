package com.astra.freyja.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 提供商响应体，apiKey 仅返回掩码，绝不返回明文。
 */
@Data
public class AiProviderVO {

    /** 主键 */
    private Long id;

    /** 提供商编码 */
    private String providerCode;

    /** 提供商名称 */
    private String providerName;

    /** 接入类型 */
    private String providerType;

    /** 掩码后的 API Key，如 sk-****1234；未配置为空串 */
    private String maskedApiKey;

    /** 是否已配置 API Key */
    private Boolean hasApiKey;

    /** Base URL */
    private String baseUrl;

    /** 请求超时（秒） */
    private Integer timeout;

    /** 最大重试次数 */
    private Integer maxRetries;

    /** 是否熔断 0-关 1-开 */
    private Integer enableBreaker;

    /** 熔断阈值 */
    private Integer breakerThreshold;

    /** 熔断恢复时间（秒） */
    private Integer breakerTimeout;

    /** 状态 0-停用 1-启用 */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}