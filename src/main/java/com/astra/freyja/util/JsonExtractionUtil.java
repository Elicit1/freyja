package com.astra.freyja.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * 通用 JSON 文本清洗与反序列化工具。
 * 能够自动容忍 Markdown ```json ... ``` 代码块标记与前后说明文字。
 */
@Slf4j
public class JsonExtractionUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 清洗并反序列化外部输入文本为目标对象。
     *
     * @param rawText 原始文本 (可能带有 markdown 围栏或前后自然语言说明)
     * @param clazz   目标对象类型
     * @param <T>     泛型类型
     * @return 成功反序列化后的对象，若无法解析则返回 null
     */
    public static <T> T cleanAndParseJson(String rawText, Class<T> clazz) {
        if (StringUtils.isBlank(rawText)) {
            return null;
        }

        String text = rawText.trim();

        // 1. 如果包含 Markdown 代码块标记，优先提取代码块内部内容
        if (text.contains("```")) {
            int firstFence = text.indexOf("```");
            int secondFence = text.indexOf("```", firstFence + 3);
            if (secondFence > firstFence) {
                String block = text.substring(firstFence + 3, secondFence).trim();
                if (block.toLowerCase().startsWith("json")) {
                    block = block.substring(4).trim();
                }
                text = block;
            }
        }

        // 2. 尝试直接反序列化
        try {
            return MAPPER.readValue(text, clazz);
        } catch (Exception e) {
            log.debug("[JsonExtractionUtil] 直接反序列化未成功: {}", e.getMessage());
        }

        // 3. 截取最外层 { ... }
        int startIdx = text.indexOf('{');
        int endIdx = text.lastIndexOf('}');
        if (startIdx >= 0 && endIdx > startIdx) {
            String jsonBlock = text.substring(startIdx, endIdx + 1);
            try {
                return MAPPER.readValue(jsonBlock, clazz);
            } catch (Exception e) {
                log.warn("[JsonExtractionUtil] 截取 JSON 块反序列化失败: {}", e.getMessage());
            }
        }

        return null;
    }
}
