package com.astra.freyja.service;

/**
 * AI 输出校验与 JSON 截断智能修复服务。
 * 负责检测 LLM 输出完整性、清洗 Markdown 代码块、处理 max_tokens 截断与反序列化校验。
 */
public interface AIOutputValidationService {

    /**
     * 清洗、修复并解析 AI 输出的 JSON 文本至目标 DTO。
     * 若遇到末尾截断等语法破损，自动启用深度括号回溯修复。
     *
     * @param rawOutput 大模型原始返回字符串
     * @param targetClass 目标 DTO 类型
     * @param <T> 泛型
     * @return 解析验证后的对象实例
     */
    <T> T parseAndValidate(String rawOutput, Class<T> targetClass);

    /**
     * 判断一段输出文本是否可能因 max_tokens 发生了截断。
     *
     * @param text 待检测文本
     * @return true-可能发生截断; false-正常闭合
     */
    boolean isTruncated(String text);
}
