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
    @Autowired com.astra.freyja.dao.AiSkillFileMapper skillFileMapper;
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

    @Test void discoversBusinessCrudToolsAndNoInternalAi() throws Exception {
        JsonNode init = rpc("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(), "clientInfo", Map.of("name", "batch-test", "version", "1")));
        assertFalse(init.path("capabilities").has("prompts"));
        JsonNode catalog = rpc("tools/list", Map.of()).path("tools"); assertEquals(72, catalog.size());
        Set<String> names = new HashSet<>(); catalog.forEach(tool -> names.add(tool.path("name").asText()));
        assertTrue(names.containsAll(Set.of("append_storyboard", "batch_create_records", "batch_update_shots", "batch_delete_records", "batch_read_records")));
        assertTrue(names.containsAll(Set.of("list_skills", "read_skill", "read_skill_file", "batch_upload_asset_images")));
        assertTrue(Collections.disjoint(names, Set.of("get_creation_options", "decompose_script", "generate_image", "generate_video")));
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
        for (JsonNode tool : catalog) if (Set.of("batch_create_records").contains(tool.path("name").asText())) {
            for (JsonNode item : tool.path("inputSchema").path("properties").path("items").path("items").path("oneOf"))
                contracts.put(tool.path("name").asText() + ":" + item.path("properties").path("type").path("const").asText(), item.path("properties").path("data"));
        }
        JsonNode look = contracts.get("batch_create_records:CHARACTER_LOOK");
        assertEquals(List.of("characterId", "lookName", "designDesc"), json.convertValue(look.path("required"), List.class));
        assertEquals(List.of("sceneId"), json.convertValue(contracts.get("batch_create_records:SHOT").path("required"), List.class));
        for (JsonNode tool : catalog) if (tool.path("name").asText().equals("batch_update_character_looks")) {
            JsonNode item = tool.path("inputSchema").path("properties").path("items").path("items");
            assertFalse(item.path("properties").has("type"));
            assertEquals(0, item.path("properties").path("data").path("required").size());
        }
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

    @Test void singleCrudAndTypedBatchUpdateWorkThroughHttpAndRejectOtherBusinessFields() throws Exception {
        String project = McpBusinessDatabaseTest.ROOT;
        JsonNode created = rpc("tools/call", Map.of("name", "create_prop", "arguments", Map.of("requestId", "http-single-create", "dramaId", project, "data", Map.of("name", "HTTP prop"))));
        assertFalse(created.path("isError").asBoolean(), created.toString());
        String id = created.path("structuredContent").path("data").path("recordId").asText();
        JsonNode read = rpc("tools/call", Map.of("name", "get_prop", "arguments", Map.of("dramaId", project, "recordId", id)));
        String fingerprint = read.path("structuredContent").path("data").path("editFingerprint").asText();
        assertEquals(64, fingerprint.length());
        for (Map<String, Object> invalid : List.of(
                Map.<String, Object>of("requestId", "http-single-invalid", "dramaId", project, "recordId", id, "expectedFingerprint", fingerprint, "type", "PROP", "data", Map.of("name", "Wrong")),
                Map.<String, Object>of("requestId", "http-single-invalid", "dramaId", project, "recordId", id, "expectedFingerprint", fingerprint, "data", Map.of("sceneId", "30")))) {
            assertTrue(rpc("tools/call", Map.of("name", "update_prop", "arguments", invalid)).path("isError").asBoolean());
        }
        JsonNode updated = rpc("tools/call", Map.of("name", "update_prop", "arguments", Map.of("requestId", "http-single-update", "dramaId", project,
                "recordId", id, "expectedFingerprint", fingerprint, "data", Map.of("name", "Edited prop"))));
        assertFalse(updated.path("isError").asBoolean(), updated.toString());
        fingerprint = updated.path("structuredContent").path("data").path("data").path("editFingerprint").asText();
        JsonNode batch = rpc("tools/call", Map.of("name", "batch_update_props", "arguments", Map.of("requestId", "http-typed-update", "dramaId", project,
                "items", List.of(Map.of("recordId", id, "expectedFingerprint", fingerprint, "data", Map.of("description", "External text"))))));
        assertFalse(batch.path("isError").asBoolean(), batch.toString());
        fingerprint = batch.path("structuredContent").path("data").path("records").get(0).path("data").path("editFingerprint").asText();
        var arguments = Map.of("requestId", "http-single-delete", "dramaId", project, "recordId", id, "expectedFingerprint", fingerprint);
        JsonNode deleted = rpc("tools/call", Map.of("name", "delete_prop", "arguments", arguments));
        assertFalse(deleted.path("isError").asBoolean(), deleted.toString()); assertTrue(deleted.path("structuredContent").path("data").path("deleted").asBoolean());
        assertEquals(deleted, rpc("tools/call", Map.of("name", "delete_prop", "arguments", arguments)));
        assertNull(fixture.props.selectById(McpScope.id(id)));
        assertTrue(rpc("tools/call", Map.of("name", "get_prop", "arguments", Map.of("dramaId", project, "recordId", id))).path("isError").asBoolean());
        for (JsonNode tool : rpc("tools/list", Map.of()).path("tools")) {
            String name = tool.path("name").asText();
            assertNotEquals("batch_update_records", name);
            if (name.startsWith("batch_update_")) assertFalse(tool.path("inputSchema").path("properties").path("items").path("items").path("properties").has("type"));
        }
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

    @Test void singleAndElevenShotBatchAcceptLookAliasAndExposeConcreteMediaSchema() throws Exception {
        fixture.character(50L, McpBusinessDatabaseTest.PROJECT); fixture.look(60L, 50L, 1);
        for (JsonNode tool : rpc("tools/list", Map.of()).path("tools")) if (tool.path("name").asText().equals("create_shot")) {
            JsonNode image = tool.path("inputSchema").path("properties").path("data").path("properties").path("refImages").path("items");
            assertEquals("object", image.path("type").asText()); assertFalse(image.has("allOf"));
            assertEquals(List.of("sourceType", "imageUrl"), json.convertValue(image.path("required"), List.class));
            assertTrue(image.path("properties").has("sourceId")); assertTrue(image.path("properties").path("sourceType").path("enum").toString().contains("CHARACTER_LOOK"));
        }
        Map<String, Object> data = new LinkedHashMap<>(Map.of("sceneId", "30", "shotType", "AUTO", "cameraMovement", "AUTO", "shotTypeLocked", false, "cameraMovementLocked", false,
                "characterRefs", List.of(Map.of("characterId", "50", "lookId", "60")),
                "refImages", List.of(Map.of("sourceType", "CHARACTER_LOOK", "sourceId", "60", "characterId", "50", "imageUrl", "https://media/look.png"))));
        data.put("videoPrompt", "integrated_multimodal_description: 0-4s the subject walks.\noverall_soundscape: footsteps.\nnon_diegetic_music: none."); data.put("duration", 4);
        JsonNode single = rpc("tools/call", Map.of("name", "create_shot", "arguments", Map.of("requestId", "look-single-001", "dramaId", McpBusinessDatabaseTest.ROOT, "data", data)));
        assertFalse(single.path("isError").asBoolean(), single.toString());
        JsonNode saved = single.path("structuredContent").path("data").path("data");
        assertEquals("CHARACTER_REFERENCE", saved.path("refImages").get(0).path("sourceType").asText());
        assertEquals("60", saved.path("refImages").get(0).path("lookId").asText()); assertEquals("AUTO", saved.path("shotType").asText());
        assertEquals(data.get("videoPrompt"), saved.path("videoPrompt").asText());
        List<Object> items = new ArrayList<>(); for (int index = 0; index < 11; index++) items.add(Map.of("clientKey", "shot" + index, "type", "SHOT", "data", data));
        JsonNode batch = rpc("tools/call", Map.of("name", "batch_create_records", "arguments", Map.of("requestId", "look-batch-0001", "dramaId", McpBusinessDatabaseTest.ROOT, "items", items)));
        assertFalse(batch.path("isError").asBoolean(), batch.toString()); assertEquals(12, fixture.count("drama_shot"));
    }

    @Test void malformedSingleAndBatchMediaReturnCompactStructuredErrorsWithoutUnionDump() throws Exception {
        for (Map<String, Object> image : List.of(Map.<String, Object>of("url", "https://media/a.png", "type", "CHARACTER", "assetId", "50"),
                Map.<String, Object>of("sourceType", "CHARACTER", "imageUrl", "https://media/a.png"))) {
            Map<String, Object> data = Map.of("sceneId", "30", "refImages", List.of(image));
            for (String tool : List.of("create_shot", "batch_create_records")) {
                Map<String, Object> args = new LinkedHashMap<>(Map.of("requestId", "invalid-media001", "dramaId", McpBusinessDatabaseTest.ROOT));
                if (tool.equals("create_shot")) args.put("data", data);
                else args.put("items", List.of(Map.of("clientKey", "shot", "type", "SHOT", "data", data)));
                var result = rpc("tools/call", Map.of("name", tool, "arguments", args));
                assertTrue(result.path("isError").asBoolean()); assertEquals(400, result.path("structuredContent").path("error").path("code").asInt());
                assertTrue(result.toString().length() < 1500, result.toString());
                assertTrue(result.path("structuredContent").path("error").path("message").asText().contains("refImages[0]"));
                assertFalse(result.toString().contains("constant value 'PROJECT'"));
            }
        }
        assertEquals(0, fixture.count("drama_shot"));
    }

    @Test void batchUploadWorksThroughHttpAndReturnsOnlyUrlsAndMetadata() throws Exception {
        org.mockito.Mockito.when(fixture.storage.bucketExists(org.mockito.ArgumentMatchers.any(io.minio.BucketExistsArgs.class))).thenReturn(true);
        String encoded = Base64.getEncoder().encodeToString(McpImageUploadTest.image("png", true));
        Map<String, Object> args = Map.of("requestId", "http-batch-image1", "dramaId", McpBusinessDatabaseTest.ROOT, "items", List.of(
                Map.of("clientKey", "hero", "purpose", "CHARACTER_LOOK", "dataBase64", encoded),
                Map.of("clientKey", "scene", "purpose", "SCENE", "dataBase64", encoded)));
        var result = rpc("tools/call", Map.of("name", "batch_upload_asset_images", "arguments", args));
        assertFalse(result.path("isError").asBoolean(), result.toString());
        assertEquals(2, result.path("structuredContent").path("data").path("count").asInt());
        assertTrue(result.path("structuredContent").path("data").path("urlMap").has("hero")); assertFalse(result.toString().contains(encoded));
        assertEquals(result, rpc("tools/call", Map.of("name", "batch_upload_asset_images", "arguments", args)));
        org.mockito.Mockito.verify(fixture.storage, org.mockito.Mockito.times(2)).putObject(org.mockito.ArgumentMatchers.any(io.minio.PutObjectArgs.class));
    }

    @Test void publishedSkillAndReferenceFilesUseEnabledVersionWithoutExecutingInternalTools() throws Exception {
        var item = com.astra.freyja.skill.model.SkillCatalogItem.builder().name("h3-prompt-writing").displayName("H3").description("H3 video prompt format").version("1").versionId(2032095628944588802L).build();
        org.mockito.Mockito.when(fixture.skillCatalog.getEnabledCatalog()).thenReturn(List.of(item));
        org.mockito.Mockito.when(fixture.skillContents.loadSkillVersion(item.getVersionId())).thenReturn(com.astra.freyja.skill.model.LoadedSkill.builder()
                .name(item.getName()).version("1").contentHash("hash").content("Read references/base-en.txt for three sections; references/ref-en.txt for six.").build());
        var file = new com.astra.freyja.entity.AiSkillFile(); file.setId(301L); file.setSkillVersionId(item.getVersionId()); file.setRelativePath("references/base-en.txt");
        file.setFileType("REFERENCE"); file.setObjectKey("private/storage/key"); file.setContentHash("filehash"); file.setContentSize(100L); skillFileMapper.insert(file);
        org.mockito.Mockito.when(fixture.skillRepository.loadTextFile("private/storage/key", "filehash", 100L)).thenReturn("integrated_multimodal_description\noverall_soundscape\nnon_diegetic_music");
        var catalog = rpc("tools/call", Map.of("name", "list_skills", "arguments", Map.of("keyword", "H3"))).path("structuredContent").path("data");
        assertEquals(1, catalog.path("total").asInt()); assertTrue(catalog.path("records").get(0).path("versionId").isTextual()); assertFalse(catalog.toString().contains("base-en.txt"));
        Map<String, Object> readArgs = Map.of("name", item.getName(), "versionId", item.getVersionId().toString());
        var loaded = rpc("tools/call", Map.of("name", "read_skill", "arguments", readArgs)); assertFalse(loaded.path("isError").asBoolean(), loaded.toString());
        assertEquals("references/base-en.txt", loaded.path("structuredContent").path("data").path("references").get(0).path("path").asText()); assertFalse(loaded.toString().contains("private/storage/key"));
        var fileArgs = new LinkedHashMap<>(readArgs); fileArgs.put("path", "references/base-en.txt");
        var body = rpc("tools/call", Map.of("name", "read_skill_file", "arguments", fileArgs)); assertFalse(body.path("isError").asBoolean());
        assertTrue(body.path("structuredContent").path("data").path("content").asText().contains("overall_soundscape")); assertFalse(body.toString().contains("private/storage/key"));
        for (String path : List.of("references/../secret.md", "references/./base-en.txt", "scripts/run.py", "F:\\secret.md", "references\\base-en.txt")) {
            fileArgs.put("path", path); assertTrue(rpc("tools/call", Map.of("name", "read_skill_file", "arguments", fileArgs)).path("isError").asBoolean());
        }
        fileArgs.put("path", "references/base-en.txt"); item.setVersionId(302L);
        var changed = rpc("tools/call", Map.of("name", "read_skill_file", "arguments", fileArgs)); assertEquals(409, changed.path("structuredContent").path("error").path("code").asInt());
        org.mockito.Mockito.when(fixture.skillCatalog.getEnabledCatalog()).thenReturn(List.of());
        assertEquals(404, rpc("tools/call", Map.of("name", "read_skill", "arguments", readArgs)).path("structuredContent").path("error").path("code").asInt());
        org.mockito.Mockito.verify(fixture.skillRepository, org.mockito.Mockito.times(1)).loadTextFile("private/storage/key", "filehash", 100L);
        org.mockito.Mockito.verifyNoInteractions(fixture.skillFiles);
    }
}
