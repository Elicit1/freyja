package com.astra.freyja.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 大模型输出截断 JSON 智能自动修复工具类：
 * 解决大模型因 max_tokens 截断导致的引号未闭合、括号缺失、尾部逗号等语法错误。
 */
@Slf4j
public class JsonRepairUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 自动清洗并修复可能被截断的 JSON 文本
     *
     * @param json 原始模型输出文本
     * @return 修复闭合后的合法 JSON 文本
     */
    public static String repair(String json) {
        if (StringUtils.isBlank(json)) {
            return "{}";
        }
        String cleaned = json.trim();

        // 1. 剥离 Markdown 代码块标记 ```json / ```
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf("\n");
            if (firstNewline != -1) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }

        // 2. 找到第一个有效的 JSON 对象起始符 '{'
        int firstBrace = cleaned.indexOf("{");
        if (firstBrace == -1) {
            return "{}";
        }
        cleaned = cleaned.substring(firstBrace);

        // 3. 先尝试直接解析，如果本来就是合法的完整 JSON 则直接返回
        try {
            OBJECT_MAPPER.readTree(cleaned);
            return cleaned;
        } catch (Exception ignored) {
            // 发生截断，进入自动修复
        }

        // 4. 逐步执行括号栈修复与回退裁剪
        String repaired = doRepair(cleaned);
        try {
            OBJECT_MAPPER.readTree(repaired);
            return repaired;
        } catch (Exception e) {
            log.debug("首次修复未完全闭合，尝试回退裁剪最后一个破损节点: {}", e.getMessage());
        }

        // 5. 激进回退修复：如果最后一个子节点破损，回退到倒数第一个完整的 '}' 或 ']'
        return doFallbackRepair(cleaned);
    }

    private static String doRepair(String input) {
        StringBuilder sb = new StringBuilder();
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inString) {
                sb.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                    sb.append(c);
                } else if (c == '{' || c == '[') {
                    stack.push(c);
                    sb.append(c);
                } else if (c == '}') {
                    if (!stack.isEmpty() && stack.peek() == '{') {
                        stack.pop();
                        sb.append(c);
                    }
                } else if (c == ']') {
                    if (!stack.isEmpty() && stack.peek() == '[') {
                        stack.pop();
                        sb.append(c);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        // 如果在字符串内部截断，先补齐引号
        if (inString) {
            sb.append('"');
        }

        // 清除尾部悬空的逗号、冒号或空白
        String result = cleanTrailingDanglingTokens(sb.toString().trim());

        // 再次根据未闭合的栈补全括号
        Deque<Character> remainingStack = new ArrayDeque<>();
        inString = false;
        escaped = false;
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == '{' || c == '[') remainingStack.push(c);
                else if (c == '}' && !remainingStack.isEmpty() && remainingStack.peek() == '{') remainingStack.pop();
                else if (c == ']' && !remainingStack.isEmpty() && remainingStack.peek() == '[') remainingStack.pop();
            }
        }

        StringBuilder finalSb = new StringBuilder(result);
        while (!remainingStack.isEmpty()) {
            char top = remainingStack.pop();
            if (top == '{') {
                finalSb.append('}');
            } else if (top == '[') {
                finalSb.append(']');
            }
        }

        return finalSb.toString();
    }

    private static String doFallbackRepair(String input) {
        // 从后往前寻找最后一个完整的逗号、闭合括号
        int lastSafeIndex = Math.max(input.lastIndexOf("},"), input.lastIndexOf("],"));
        if (lastSafeIndex == -1) {
            lastSafeIndex = Math.max(input.lastIndexOf("}"), input.lastIndexOf("]"));
        }
        if (lastSafeIndex > 0) {
            String safePrefix = input.substring(0, lastSafeIndex + 1);
            return doRepair(safePrefix);
        }
        return doRepair(input);
    }

    private static String cleanTrailingDanglingTokens(String s) {
        String res = s.trim();
        boolean changed = true;
        while (changed && res.length() > 0) {
            changed = false;
            if (res.endsWith(",") || res.endsWith(":")) {
                res = res.substring(0, res.length() - 1).trim();
                changed = true;
            }
        }
        return res;
    }
}
