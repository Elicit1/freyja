package com.astra.freyja.service;

import org.springframework.ai.chat.model.ChatModel;

/**
 * AI 模型动态工厂：按数据库配置动态构建并缓存 ChatModel 实例。
 */
public interface AiModelFactory {

    /** 接入类型：OpenAI 规范兼容（含 DeepSeek 等） */
    String TYPE_OPENAI = "OPENAI";

    /** 接入类型：Ollama */
    String TYPE_OLLAMA = "OLLAMA";

    /**
     * 获取指定提供商+模型对应的 ChatModel，未命中则构建并缓存（外层包裹熔断器）。
     *
     * @param providerId 提供商 ID
     * @param modelCode  模型标识
     * @return ChatModel 实例
     */
    ChatModel getChatModel(Long providerId, String modelCode);

    /**
     * 获取指定提供商+模型对应的 ChatModel，支持兜底策略：
     * 若 providerId 为 null，则自动获取系统首个启用中的提供商；
     * 若 modelCode 为空，则自动获取该提供商下首个启用中的 CHAT 模型（无则兜底 deepseek-chat）。
     */
    ChatModel getChatModelOrDefault(Long providerId, String modelCode);

    /**
     * 失效某提供商下全部模型实例（提供商配置变更/删除时调用）。
     */
    void evict(Long providerId);

    /**
     * 失效指定提供商+模型的实例。
     */
    void evict(Long providerId, String modelCode);
}