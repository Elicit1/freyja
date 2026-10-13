package com.astra.freyja.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import java.net.URI;
import java.net.http.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end HTTP -> MCP schema -> native business CRUD -> transaction journal. */
@SpringBootTest(classes = McpBatchProtocolIntegrationTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"freyja.mcp.enabled=true", "freyja.mcp.token=" + McpBatchProtocolIntegrationTest.TOKEN,
                "freyja.mcp.write-enabled=true", "freyja.mcp.allowed-project-ids=2032095628944588801",
                "spring.sql.init.mode=never", "logging.file.name=target/mcp-http-batch-test.log"})
class McpBatchProtocolIntegrationTest {
    static final String TOKEN = "mcp-batch-protocol-test-token-1234567890";
    @LocalServerPort int port;
    @Autowired AutowireCapableBeanFactory factory;
    McpBusinessDatabaseTest fixture;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({McpBusinessDatabaseTest.Config.class, McpServerConfiguration.class, McpToolCatalog.class})
    static class App {
        @Bean ToolCallback internalAiCallback() { return ToolCallbacks.from(new InternalOnly())[0]; }
    }
    static class InternalOnly {
        @Tool(name = "internal_ai_should_remain_private", description = "Internal AI callback")
        public String privateAi() { throw new AssertionError("Internal AI must not be called through MCP"); }
    }

    @BeforeEach void setup() { fixture = new McpBusinessDatabaseTest(); factory.autowireBean(fixture); fixture.setup(); }
    @AfterEach void noAiCalls() { if (fixture != null) fixture.noInternalAiCalls(); }

    JsonNode rpc(String method, Map<String, Object> params) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-06-18")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params)))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode root = json.readTree(response.body()); assertFalse(root.has("error"), response.body()); return root.path("result");
    }

    @Test void discoversOnlyTwentyBusinessToolsAndNoPrompts() throws Exception {
        JsonNode init = rpc("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(), "clientInfo", Map.of("name", "batch-test", "version", "1")));
        assertFalse(init.path("capabilities").has("prompts"));
        JsonNode catalog = rpc("tools/list", Map.of()).path("tools"); assertEquals(20, catalog.size());
        Set<String> names = new HashSet<>(); catalog.forEach(tool -> names.add(tool.path("name").asText()));
        assertTrue(names.containsAll(Set.of("append_storyboard", "batch_create_records", "batch_update_records", "batch_read_records")));
        assertTrue(Collections.disjoint(names, Set.of("list_skills", "read_skill", "get_creation_options", "decompose_script", "generate_image", "generate_video")));
        assertFalse(names.contains("internal_ai_should_remain_private"));
    }

    @Test void batchCreateAndReplayThroughHttpPreserveIdsAndDatabaseState() throws Exception {
        Map<String, Object> input = Map.of("requestId", "http-import-req1", "dramaId", McpBusinessDatabaseTest.ROOT, "items", List.of(
                Map.of("clientKey", "ep", "type", "EPISODE", "data", Map.of("title", "HTTP episode")),
                Map.of("clientKey", "scene", "type", "SCENE", "data", Map.of("episodeId", Map.of("ref", "ep"), "name", "HTTP scene"))));
        JsonNode first = rpc("tools/call", Map.of("name", "append_storyboard", "arguments", input));
        assertFalse(first.path("isError").asBoolean(), first.toString());
        JsonNode data = first.path("structuredContent").path("data");
        assertTrue(data.path("idMap").path("ep").isTextual()); assertTrue(data.path("records").get(0).path("data").path("id").isTextual());
        JsonNode replay = rpc("tools/call", Map.of("name", "append_storyboard", "arguments", input));
        assertEquals(first, replay); assertEquals(2, fixture.count("drama_episode")); assertEquals(2, fixture.count("drama_scene"));
        JsonNode invalid = rpc("tools/call", Map.of("name", "batch_create_records", "arguments", Map.of("requestId", "http-invalid-req", "dramaId", McpBusinessDatabaseTest.ROOT,
                "items", List.of(Map.of("clientKey", "shot", "type", "SHOT", "data", Map.of("sceneId", "30", "latestTaskId", "forbidden"))))));
        assertTrue(invalid.path("isError").asBoolean());
        assertFalse(invalid.path("content").get(0).path("text").asText().isBlank());
        assertEquals(0, fixture.count("drama_shot"));
    }

    @Test void publishedContractsAllowDirectWorkshopCardsAndOnlyRequireBusinessFields() throws Exception {
        JsonNode catalog = rpc("tools/list", Map.of()).path("tools");
        Map<String, JsonNode> contracts = new HashMap<>();
        for (JsonNode tool : catalog) if (Set.of("batch_create_records", "batch_update_records").contains(tool.path("name").asText())) {
            for (JsonNode item : tool.path("inputSchema").path("properties").path("items").path("items").path("oneOf"))
                contracts.put(tool.path("name").asText() + ":" + item.path("properties").path("type").path("const").asText(), item.path("properties").path("data"));
        }
        JsonNode look = contracts.get("batch_create_records:CHARACTER_LOOK");
        assertEquals(List.of("characterId", "lookName", "designDesc"), json.convertValue(look.path("required"), List.class));
        assertEquals(List.of("sceneId"), json.convertValue(contracts.get("batch_create_records:SHOT").path("required"), List.class));
        assertEquals(0, contracts.get("batch_update_records:CHARACTER_LOOK").path("required").size());
        Map<String, Object> input = Map.of("requestId", "http-workshop-01", "dramaId", McpBusinessDatabaseTest.ROOT, "items", List.of(
                Map.of("clientKey", "character", "type", "CHARACTER", "data", Map.of("name", "旅客")),
                Map.of("clientKey", "look", "type", "CHARACTER_LOOK", "data", Map.of("characterId", Map.of("ref", "character"), "lookName", "日常装", "designDesc", "灰色棉衣")),
                Map.of("clientKey", "shot", "type", "SHOT", "data", Map.of("sceneId", "30", "generationMode", "REFERENCE_MODE", "prompt", "<Picture 1> 是主角身份参考。",
                        "refImages", List.of(Map.of("sourceType", "CHARACTER", "sourceId", Map.of("ref", "character"), "imageUrl", "https://media/identity.png", "usageRole", "SUBJECT", "referenceRole", "IDENTITY"))))));
        JsonNode result = rpc("tools/call", Map.of("name", "batch_create_records", "arguments", input));
        assertFalse(result.path("isError").asBoolean(), result.toString());
        JsonNode shot = result.path("structuredContent").path("data").path("records").get(2).path("data");
        assertEquals("<Picture 1> 是主角身份参考。", shot.path("prompt").asText()); assertTrue(shot.path("directorPlan").isNull());
        assertFalse(shot.path("refImages").get(0).path("id").asText().isBlank());
        assertEquals(1, fixture.count("drama_shot"));
        JsonNode invalid = rpc("tools/call", Map.of("name", "batch_create_records", "arguments", Map.of("requestId", "http-empty-url1", "dramaId", McpBusinessDatabaseTest.ROOT,
                "items", List.of(Map.of("clientKey", "shot", "type", "SHOT", "data", Map.of("sceneId", "30", "refImages", List.of(Map.of("sourceType", "UPLOAD", "imageUrl", ""))))))));
        assertTrue(invalid.path("isError").asBoolean()); assertEquals(1, fixture.count("drama_shot"));
    }

    @Test void agentUploadsLargeImageThroughMcpThenCreatesAllReferenceAssets() throws Exception {
        byte[] bytes = McpImageUploadTest.image("png", true); assertTrue(bytes.length > 2 * 1024 * 1024);
        org.mockito.Mockito.when(fixture.storage.bucketExists(org.mockito.ArgumentMatchers.any(io.minio.BucketExistsArgs.class))).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            io.minio.PutObjectArgs stored = invocation.getArgument(0); assertArrayEquals(bytes, stored.stream().readAllBytes()); return null;
        }).when(fixture.storage).putObject(org.mockito.ArgumentMatchers.any(io.minio.PutObjectArgs.class));
        var arguments = Map.of("requestId", "http-image-upload1", "dramaId", McpBusinessDatabaseTest.ROOT, "purpose", "CHARACTER_LOOK", "dataBase64", Base64.getEncoder().encodeToString(bytes));
        JsonNode uploaded = rpc("tools/call", Map.of("name", "upload_asset_image", "arguments", arguments));
        assertFalse(uploaded.path("isError").asBoolean(), uploaded.toString());
        String url = uploaded.path("structuredContent").path("data").path("url").asText(); assertTrue(url.startsWith("http://127.0.0.1:9000/video-assets/mcp/"));
        assertEquals(uploaded, rpc("tools/call", Map.of("name", "upload_asset_image", "arguments", arguments)));
        org.mockito.Mockito.verify(fixture.storage, org.mockito.Mockito.times(1)).putObject(org.mockito.ArgumentMatchers.any(io.minio.PutObjectArgs.class));
        var created = rpc("tools/call", Map.of("name", "batch_create_records", "arguments", Map.of("requestId", "http-image-bind01", "dramaId", McpBusinessDatabaseTest.ROOT, "items", List.of(
                Map.of("clientKey", "character", "type", "CHARACTER", "data", Map.of("name", "Agent角色")),
                Map.of("clientKey", "look", "type", "CHARACTER_LOOK", "data", Map.of("characterId", Map.of("ref", "character"), "lookName", "上传造型", "designDesc", "日常服装", "referenceImageUrl", url)),
                Map.of("clientKey", "scene", "type", "RESOURCE_SCENE", "data", Map.of("name", "上传场景", "referenceImageUrl", url)),
                Map.of("clientKey", "frame", "type", "KEYFRAME", "data", Map.of("name", "上传关键图", "frameUrl", url)),
                Map.of("clientKey", "prop", "type", "PROP", "data", Map.of("name", "上传道具", "coverUrl", url))))));
        assertFalse(created.path("isError").asBoolean(), created.toString());
        JsonNode entries = created.path("structuredContent").path("data").path("records");
        assertEquals(url, entries.get(1).path("data").path("referenceImageUrl").asText()); assertEquals("SYNCED", entries.get(1).path("data").path("imageStatus").asText());
        assertEquals(url, entries.get(2).path("data").path("referenceImageUrl").asText()); assertEquals(url, entries.get(3).path("data").path("frameUrl").asText());
        assertEquals(url, entries.get(4).path("data").path("coverUrl").asText());
        assertEquals(2, fixture.count("mcp_business_operation"));
    }
}
