package com.astra.freyja.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = McpProtocolIntegrationTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"freyja.mcp.enabled=true", "freyja.mcp.token=" + McpProtocolIntegrationTest.TOKEN,
                "freyja.mcp.allow-all-projects=true", "logging.file.name=target/mcp-test.log"})
class McpProtocolIntegrationTest {
    static final String TOKEN = "mcp-protocol-test-token-1234567890";
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({McpServerConfiguration.class, McpToolCatalog.class, McpJson.class})
    static class App {
        @Bean McpReadTools reads() {
            McpReadTools reads = mock(McpReadTools.class);
            when(reads.tools()).thenReturn(List.of(McpTool.read("get_project", "Test project", McpRecordInputs.Get.class, (caller, input) ->
                    Map.of("id", McpScope.id(input.recordId()), "authenticated", caller.allProjects()))));
            return reads;
        }
    }

    HttpResponse<String> request(String body, String authorization, String origin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18").POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) builder.header("Authorization", authorization);
        if (origin != null) builder.header("Origin", origin);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void rejectsMissingAndIncorrectCredentialsAndBrowserOrigins() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        assertEquals(401, request(body, null, null).statusCode());
        assertEquals(401, request(body, "Bearer incorrect", null).statusCode());
        assertEquals(403, request(body, "Bearer " + TOKEN, "https://untrusted.example").statusCode());
    }

    @Test void initializesAndDiscoversTypedTools() throws Exception {
        var init = request("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}", "Bearer " + TOKEN, null);
        assertEquals(200, init.statusCode(), init.body());
        assertEquals("freyja-digital-studio", json.readTree(init.body()).path("result").path("serverInfo").path("name").asText());
        var response = request("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/list\"}", "Bearer " + TOKEN, null);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode tool = json.readTree(response.body()).path("result").path("tools").get(0);
        assertEquals("get_project", tool.path("name").asText());
        assertEquals("string", tool.path("inputSchema").path("properties").path("recordId").path("type").asText());
        assertTrue(tool.path("annotations").path("readOnlyHint").asBoolean());
    }

    @Test void callsToolsWithIdentityAndPreservesProtocolAndBusinessIdTypes() throws Exception {
        var response = request("{\"jsonrpc\":\"2.0\",\"id\":2032095628944588802,\"method\":\"tools/call\",\"params\":{\"name\":\"get_project\",\"arguments\":{\"recordId\":\"2032095628944588801\"}}}", "Bearer " + TOKEN, null);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode root = json.readTree(response.body());
        assertTrue(root.path("id").isNumber(), response.body());
        assertEquals(2032095628944588802L, root.path("id").asLong());
        assertFalse(root.path("result").path("isError").asBoolean(), response.body());
        JsonNode data = root.path("result").path("structuredContent").path("data");
        assertTrue(data.path("authenticated").asBoolean(), response.body());
        assertTrue(data.path("id").isTextual(), response.body());
        assertEquals("2032095628944588801", data.path("id").asText());
    }

    @Test void numericBusinessIdsAreRejectedAsToolErrors() throws Exception {
        var response = request("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"get_project\",\"arguments\":{\"recordId\":2032095628944588801}}}", "Bearer " + TOKEN, null);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(json.readTree(response.body()).path("result").path("isError").asBoolean(), response.body());
    }

    @Test void readsGuideResource() throws Exception {
        var response = request("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"resources/read\",\"params\":{\"uri\":\"freyja://guide\"}}", "Bearer " + TOKEN, null);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(json.readTree(response.body()).path("result").path("contents").get(0).path("text").asText().contains("Business IDs"));
    }
}
