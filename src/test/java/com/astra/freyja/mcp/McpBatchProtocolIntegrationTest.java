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

    @Test void discoversOnlyNineteenBusinessToolsAndNoPrompts() throws Exception {
        JsonNode init = rpc("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(), "clientInfo", Map.of("name", "batch-test", "version", "1")));
        assertFalse(init.path("capabilities").has("prompts"));
        JsonNode catalog = rpc("tools/list", Map.of()).path("tools"); assertEquals(19, catalog.size());
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
                "items", List.of(Map.of("clientKey", "shot", "type", "SHOT", "data", Map.of("sceneId", "30", "prompt", "forbidden"))))));
        assertTrue(invalid.path("isError").asBoolean());
        assertFalse(invalid.path("content").get(0).path("text").asText().isBlank());
        assertEquals(0, fixture.count("drama_shot"));
    }
}
