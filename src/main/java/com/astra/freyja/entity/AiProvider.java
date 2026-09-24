package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 提供商配置表 ai_provider。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_provider")
public class AiProvider extends BaseEntity {

    /** 提供商编码，唯一（如 deepseek/openai/ollama） */
    private String providerCode;

    /** 提供商名称 */
    private String providerName;

    /** 接入类型 OPENAI-OpenAI规范兼容 OLLAMA */
    private String providerType;

    /** API Key（AES 加密密文，接口返回掩码） */
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
}