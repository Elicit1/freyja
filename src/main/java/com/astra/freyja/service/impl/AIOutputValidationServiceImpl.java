package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.service.AIOutputValidationService;
import com.astra.freyja.util.JsonRepairUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 输出校验与自动修复服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIOutputValidationServiceImpl implements AIOutputValidationService {

    private final ObjectMapper objectMapper;

    @Override
    public <T> T parseAndValidate(String rawOutput, Class<T> targetClass) {
        if (StringUtils.isBlank(rawOutput)) {
            throw new BizException("AI 模型返回内容为空");
        }

        String cleaned = cleanMarkdownAndExtractJson(rawOutput);

        // 1. 尝试直接 Jackson 解析
        try {
            return objectMapper.readValue(cleaned, targetClass);
        } catch (Exception e) {
            log.warn("[AIOutputValidation] 直接解析失败, 疑似语法或截断问题 ({}), 启动 JsonRepairUtil 自动修复...", e.getMessage());
        }

        // 2. 启用截断/语法自动修复
        try {
            String repairedJson = JsonRepairUtil.repair(cleaned);
            return objectMapper.readValue(repairedJson, targetClass);
        } catch (Exception e) {
            log.warn("[AIOutputValidation] 修复后 Jackson 仍失败, 尝试 Spring AI BeanOutputConverter: {}", e.getMessage());
        }

        // 3. Fallback: Spring AI BeanOutputConverter
        try {
            BeanOutputConverter<T> converter = new BeanOutputConverter<>(targetClass);
            return converter.convert(cleaned);
        } catch (Exception ex) {
            log.error("[AIOutputValidation] 所有解析手段均告失败, 原始输出: {}", rawOutput, ex);
            throw new BizException("AI 输出 JSON 解析校验失败: " + ex.getMessage());
        }
    }

    @Override
    public boolean isTruncated(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        // 如果末尾不是 } 或 ]，且不包含闭合引号，则极大概率被 max_tokens 截断
        return !trimmed.endsWith("}") && !trimmed.endsWith("]");
    }

    private String cleanMarkdownAndExtractJson(String text) {
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        }
        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            cleaned = cleaned.substring(firstBrace, lastBrace + 1);
        }
        return cleaned;
    }
}
