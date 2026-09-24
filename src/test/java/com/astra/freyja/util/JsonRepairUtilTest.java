package com.astra.freyja.util;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class JsonRepairUtilTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testRepairCompleteJson() throws Exception {
        String json = "{\"title\": \"战神\", \"episodes\": [{\"shots\": []}]}";
        String repaired = JsonRepairUtil.repair(json);
        JsonNode node = objectMapper.readTree(repaired);
        assertEquals("战神", node.get("title").asText());
    }

    @Test
    void testRepairTruncatedArrayAndObject() throws Exception {
        // 模拟大模型在输出 shots 数组中间被截断
        String truncated = "{\"dramaTitle\": \"战神赘婿\", \"episodes\": [{\"scenes\": [{\"shots\": [{\"shotNo\": 1, \"action\": \"主角走进大厅\"}, {\"shotNo\": 2, \"action\": \"反派嘲笑";
        String repaired = JsonRepairUtil.repair(truncated);
        assertNotNull(repaired);

        JsonNode node = objectMapper.readTree(repaired);
        assertEquals("战神赘婿", node.get("dramaTitle").asText());
        assertTrue(node.get("episodes").isArray());
        assertTrue(node.get("episodes").get(0).get("scenes").isArray());
    }

    @Test
    void testRepairTrailingCommaAndIncompleteObject() throws Exception {
        String truncated = "{\"title\": \"短剧\", \"characters\": [{\"name\": \"林晨\"}, ";
        String repaired = JsonRepairUtil.repair(truncated);
        JsonNode node = objectMapper.readTree(repaired);
        assertEquals("短剧", node.get("title").asText());
        assertEquals(1, node.get("characters").size());
    }

    @Test
    void testRepairMarkdownWrappedTruncatedJson() throws Exception {
        String wrapped = "```json\n{\"title\": \"短剧\", \"scenes\": [{\"name\": \"会议室\"";
        String repaired = JsonRepairUtil.repair(wrapped);
        JsonNode node = objectMapper.readTree(repaired);
        assertEquals("短剧", node.get("title").asText());
    }
}
