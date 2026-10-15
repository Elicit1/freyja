package com.astra.freyja.mcp;

import com.astra.freyja.controller.McpSettingsController;
import com.astra.freyja.common.GlobalExceptionHandler;
import com.astra.freyja.dao.McpServerConfigMapper;
import com.astra.freyja.util.CryptoUtil;
import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = McpRuntimeProtocolIntegrationTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"freyja.mcp.enabled=false", "freyja.mcp.token=", "freyja.mcp.write-enabled=false", "spring.sql.init.mode=never",
                "logging.file.name=target/mcp-runtime-test.log"})
class McpRuntimeProtocolIntegrationTest {
    static final String TOKEN = "ui-mcp-runtime-test-token-1234567890";
    @LocalServerPort int port;
    @Autowired AutowireCapableBeanFactory factory;
    @Autowired McpRuntimeControl control;
    @Autowired McpServerConfigMapper configs;
    @Autowired McpToolInventory inventory;
    @Autowired McpToolCatalog catalog;
    @Autowired McpStatelessSyncServer server;
    McpBusinessDatabaseTest fixture;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({McpBusinessDatabaseTest.Config.class, McpServerConfiguration.class, McpToolCatalog.class,
            McpToolInventory.class, McpSettingsController.class, CryptoUtil.class, GlobalExceptionHandler.class})
    static class App {
        @Bean McpRuntimeControl control(McpProperties properties, McpServerConfigMapper mapper, McpJson json, CryptoUtil crypto,
                McpToolInventory inventory, McpRecordTools recordTools, ObjectProvider<McpStatelessSyncServer> servers, ObjectProvider<McpToolCatalog> catalogs,
                SqlSessionFactory sqlSessionFactory, DataSource source) {
            // The production database already owns sys_config; create it before transport registration in this test.
            new JdbcTemplate(source).execute("CREATE TABLE IF NOT EXISTS sys_config (id BIGINT PRIMARY KEY,config_name VARCHAR(100),config_key VARCHAR(100),"
                    + "config_value VARCHAR(100000),config_type VARCHAR(32),is_builtin INTEGER,status INTEGER,deleted INTEGER,"
                    + "create_by BIGINT,update_by BIGINT,create_time TIMESTAMP,update_time TIMESTAMP,remark VARCHAR(500))");
            return new McpRuntimeControl(properties, mapper, json, crypto, inventory, recordTools, servers, catalogs);
        }
    }
    @BeforeEach void setup() { fixture = new McpBusinessDatabaseTest(); factory.autowireBean(fixture); fixture.setup(); fixture.properties.setWriteEnabled(false); control.publish(control.current()); }
    @AfterEach void noAiCalls() { if (fixture != null) fixture.noInternalAiCalls(); }
    HttpResponse<String> request(String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (path.equals("/mcp")) builder.header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-06-18");
        builder.header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode admin(String path, Object body) throws Exception {
        var response = request(body == null ? "GET" : "PUT", path, body, null); assertEquals(200, response.statusCode(), response.body()); return json.readTree(response.body());
    }
    int revision() throws Exception { return admin("/system/mcp", null).path("data").path("revision").asInt(); }
    JsonNode configure(boolean enabled, boolean write) throws Exception {
        return admin("/system/mcp", Map.of("revision", revision(), "enabled", enabled, "writeEnabled", write, "allowAllProjects", false, "allowCreateProjects", false,
                "allowedProjectIds", List.of(McpBusinessDatabaseTest.ROOT), "allowedOrigins", List.of("https://studio.example"), "token", TOKEN));
    }
    HttpResponse<String> rpc(String method, Map<String, Object> params, String token) throws Exception {
        return request("POST", "/mcp", Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params), token);
    }
    Set<String> toolNames() throws Exception {
        var response = rpc("tools/list", Map.of(), TOKEN); assertEquals(200, response.statusCode(), response.body());
        Set<String> names = new HashSet<>(); json.readTree(response.body()).path("result").path("tools").forEach(tool -> names.add(tool.path("name").asText())); return names;
    }
    @Test void skillToolsRemainReadOnlyAndFollowIndependentRuntimeSwitches() throws Exception {
        configure(true, false);
        assertTrue(toolNames().containsAll(Set.of("list_skills", "read_skill", "read_skill_file")));
        var cached = catalog.specification(inventory.all().stream().filter(tool -> tool.name().equals("list_skills")).findFirst().orElseThrow());
        admin("/system/mcp/tools/list_skills", Map.of("revision", revision(), "enabled", false)); assertFalse(toolNames().contains("list_skills"));
        var result = cached.callHandler().apply(McpTransportContext.create(Map.of(McpPrincipal.CONTEXT_KEY, fixture.caller)), new McpSchema.CallToolRequest("list_skills", Map.of()));
        assertTrue(result.isError());
        org.mockito.Mockito.verifyNoInteractions(fixture.storage, fixture.skillContents, fixture.skillRepository);
    }

    @Test void directUploadToolsAreDiscoveredAndRespectWritePermissionsAndIndependentSwitches() throws Exception {
        configure(true, false);
        assertFalse(toolNames().contains(McpPresignedImageUploads.PREPARE_TOOL));
        assertFalse(toolNames().contains(McpPresignedImageUploads.COMPLETE_TOOL));
        configure(true, true);
        assertTrue(toolNames().containsAll(Set.of(McpPresignedImageUploads.PREPARE_TOOL, McpPresignedImageUploads.COMPLETE_TOOL)));
        var cached = catalog.specification(inventory.all().stream().filter(t -> t.name().equals(McpPresignedImageUploads.PREPARE_TOOL)).findFirst().orElseThrow());
        admin("/system/mcp/tools/" + McpPresignedImageUploads.PREPARE_TOOL, Map.of("revision", revision(), "enabled", false));
        assertFalse(toolNames().contains(McpPresignedImageUploads.PREPARE_TOOL));
        assertTrue(toolNames().contains(McpPresignedImageUploads.COMPLETE_TOOL));
        var result = cached.callHandler().apply(McpTransportContext.create(Map.of(McpPrincipal.CONTEXT_KEY, fixture.caller)),
                new McpSchema.CallToolRequest(McpPresignedImageUploads.PREPARE_TOOL, Map.of()));
        assertTrue(result.isError());
        admin("/system/mcp/tools/" + McpPresignedImageUploads.PREPARE_TOOL, Map.of("revision", revision(), "enabled", true));
        assertTrue(toolNames().contains(McpPresignedImageUploads.PREPARE_TOOL));
        org.mockito.Mockito.verifyNoInteractions(fixture.storage);
    }

    @Test void directUploadRoundTripUsesSmallJsonArgumentsAndReturnsFinalAssetUrl() throws Exception {
        configure(true, true);
        byte[] bytes = McpImageAssetsTest.image("png", false);
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        when(fixture.storage.bucketExists(any())).thenReturn(true);
        when(fixture.storage.getPresignedPostFormData(any())).thenReturn(Map.of("policy", "signed-policy", "x-amz-signature", "signature"));
        Map<String, Object> metadata = Map.of("requestId", "http-direct-prepare1", "dramaId", McpBusinessDatabaseTest.ROOT,
                "purpose", "KEYFRAME", "fileName", "frame.png", "contentType", "image/png", "size", bytes.length, "sha256", hash);
        var prepared = rpc("tools/call", Map.of("name", McpPresignedImageUploads.PREPARE_TOOL, "arguments", metadata), TOKEN);
        var receipt = json.readTree(prepared.body()).path("result").path("structuredContent");
        assertTrue(receipt.path("ok").asBoolean(), prepared.body());
        assertEquals("POST", receipt.path("data").path("method").asText());
        assertFalse(receipt.path("data").has("url")); assertFalse(prepared.body().contains("dataBase64"));

        var stat = mock(io.minio.StatObjectResponse.class);
        when(stat.size()).thenReturn((long) bytes.length); when(stat.contentType()).thenReturn("image/png"); when(stat.etag()).thenReturn("etag");
        when(fixture.storage.statObject(any())).thenReturn(stat);
        when(fixture.storage.getObject(any())).thenAnswer(call -> new io.minio.GetObjectResponse(okhttp3.Headers.of(), "staging", "us-east-1", "key", new java.io.ByteArrayInputStream(bytes)));
        var confirmed = rpc("tools/call", Map.of("name", McpPresignedImageUploads.COMPLETE_TOOL, "arguments", Map.of(
                "requestId", "http-direct-confirm1", "dramaId", McpBusinessDatabaseTest.ROOT, "uploadId", "http-direct-prepare1")), TOKEN);
        var completed = json.readTree(confirmed.body()).path("result").path("structuredContent");
        assertTrue(completed.path("ok").asBoolean(), confirmed.body());
        assertTrue(completed.path("data").path("url").asText().contains("/keyframe/"));
        assertEquals(hash, completed.path("data").path("sha256").asText());
        verify(fixture.storage).putObject(any());
    }

    @Test void uiConfigEnablesDefaultOffServerWithoutRestartAndNeverReturnsToken() throws Exception {
        assertEquals(404, rpc("tools/list", Map.of(), TOKEN).statusCode());
        JsonNode initial = admin("/system/mcp", null).path("data"); assertFalse(initial.path("enabled").asBoolean()); assertEquals(72, initial.path("tools").size());
        assertEquals(400, admin("/system/mcp/enabled", Map.of("revision", 0, "enabled", true)).path("code").asInt()); assertNull(configs.find());
        JsonNode saved = configure(false, true); assertEquals(200, saved.path("code").asInt(), saved.toString());
        assertFalse(saved.toString().contains(TOKEN)); assertFalse(saved.toString().contains("tokenCiphertext")); assertTrue(saved.path("data").path("tokenConfigured").asBoolean());
        assertFalse(configs.find().getConfigValue().contains(TOKEN));
        assertEquals(200, admin("/system/mcp/enabled", Map.of("revision", revision(), "enabled", true)).path("code").asInt());
        assertEquals(401, rpc("tools/list", Map.of(), "incorrect").statusCode()); assertEquals(72, toolNames().size());
    }
    @Test void disabledToolDisappearsAndCachedHandlerCannotExecuteIt() throws Exception {
        assertEquals(200, configure(true, true).path("code").asInt());
        var tool = inventory.all().stream().filter(t -> t.name().equals("batch_create_records")).findFirst().orElseThrow();
        var cached = catalog.specification(tool);
        assertEquals(200, admin("/system/mcp/tools/batch_create_records", Map.of("revision", revision(), "enabled", false)).path("code").asInt());
        assertFalse(toolNames().contains("batch_create_records"));
        Map<String, Object> input = Map.of("requestId", "cached-handler01", "dramaId", McpBusinessDatabaseTest.ROOT, "items", List.of(Map.of("clientKey", "shot", "type", "SHOT", "data", Map.of("sceneId", "30", "prompt", "external prompt"))));
        var error = cached.callHandler().apply(McpTransportContext.create(Map.of(McpPrincipal.CONTEXT_KEY, fixture.caller)), new McpSchema.CallToolRequest("batch_create_records", input));
        assertTrue(error.isError()); assertTrue(fixture.json.write(error.structuredContent()).contains("MCP_TOOL_DISABLED")); assertEquals(0, fixture.count("drama_shot"));
        assertEquals(200, admin("/system/mcp/tools/batch_create_records", Map.of("revision", revision(), "enabled", true)).path("code").asInt()); assertTrue(toolNames().contains("batch_create_records"));
        var response = rpc("tools/call", Map.of("name", "batch_create_records", "arguments", input), TOKEN); assertEquals(200, response.statusCode(), response.body());
        assertFalse(json.readTree(response.body()).path("result").path("isError").asBoolean(), response.body()); assertEquals(1, fixture.count("drama_shot"));
    }
    @Test void serverSwitchPreservesToolChoicesAndWritePermissionControlsPublishing() throws Exception {
        assertEquals(200, configure(true, true).path("code").asInt());
        admin("/system/mcp/tools/get_shot", Map.of("revision", revision(), "enabled", false));
        admin("/system/mcp/enabled", Map.of("revision", revision(), "enabled", false)); assertEquals(404, rpc("tools/list", Map.of(), TOKEN).statusCode());
        assertEquals(200, admin("/system/mcp", null).path("code").asInt());
        admin("/system/mcp/enabled", Map.of("revision", revision(), "enabled", true)); assertFalse(toolNames().contains("get_shot"));
        assertEquals(200, configure(true, false).path("code").asInt()); assertFalse(toolNames().contains("batch_create_records")); assertFalse(toolNames().contains("get_business_operation"));
        assertEquals(21, toolNames().size()); assertFalse(toolNames().contains("get_shot"));
    }

    @Test void retiredImageUploadToolsAreAbsentAndRejectCalls() throws Exception {
        configure(true, true);
        Set<String> names = toolNames();
        for (String name : List.of("upload_asset_image", "batch_upload_asset_images")) {
            assertFalse(names.contains(name));
            var response = rpc("tools/call", Map.of("name", name, "arguments", Map.of()), TOKEN);
            JsonNode result = json.readTree(response.body());
            assertTrue(result.has("error") || result.path("result").path("isError").asBoolean(), response.body());
            assertEquals(400, admin("/system/mcp/tools/" + name, Map.of("revision", revision(), "enabled", true)).path("code").asInt());
        }
        verifyNoInteractions(fixture.storage);
    }

    @Test void staleOrUnknownToolChangesAreRejectedAndTokenRotationTakesEffect() throws Exception {
        assertEquals(200, configure(true, true).path("code").asInt()); int old = revision();
        admin("/system/mcp/tools/get_shot", Map.of("revision", old, "enabled", false));
        assertEquals(409, admin("/system/mcp/tools/get_project", Map.of("revision", old, "enabled", false)).path("code").asInt());
        assertEquals(400, admin("/system/mcp/tools/derive_prompt", Map.of("revision", revision(), "enabled", true)).path("code").asInt());
        String changed = TOKEN + "-rotated";
        assertEquals(200, admin("/system/mcp", Map.of("revision", revision(), "enabled", true, "writeEnabled", true, "allowAllProjects", false, "allowCreateProjects", false,
                "allowedProjectIds", List.of(McpBusinessDatabaseTest.ROOT), "allowedOrigins", List.of(), "token", changed)).path("code").asInt());
        assertEquals(401, rpc("tools/list", Map.of(), TOKEN).statusCode()); assertEquals(200, rpc("tools/list", Map.of(), changed).statusCode());
        assertFalse(admin("/system/mcp", null).toString().contains(changed)); assertFalse(control.current().getTools().get("get_shot"));
        assertTrue(toolNamesForServer().contains("get_project")); assertFalse(toolNamesForServer().contains("derive_prompt"));
    }
    Set<String> toolNamesForServer() { Set<String> result = new HashSet<>(); server.listTools().forEach(t -> result.add(t.name())); return result; }

    @Test void crudMatrixShowsAllObjectsAndIndividualDeleteSwitchReallyBlocksCalls() throws Exception {
        configure(true, true);
        JsonNode settings = admin("/system/mcp", null).path("data");
        assertEquals(10, settings.path("crudCapabilities").size()); assertEquals(10, settings.path("recordTypes").size());
        fixture.shot(80L);
        admin("/system/mcp/tools/delete_shot", Map.of("revision", revision(), "enabled", false));
        assertFalse(toolNames().contains("delete_shot")); assertTrue(toolNames().contains("batch_delete_records"));
        Map<String, Object> arguments = Map.of("requestId", "disabled-delete-01", "dramaId", McpBusinessDatabaseTest.ROOT, "recordId", "80",
                "expectedFingerprint", fixture.views.fingerprint(fixture.shots.selectById(80L)));
        var cachedTool = inventory.all().stream().filter(t -> t.name().equals("delete_shot")).findFirst().orElseThrow();
        var result = catalog.specification(cachedTool).callHandler().apply(McpTransportContext.create(Map.of(McpPrincipal.CONTEXT_KEY, fixture.caller)),
                new McpSchema.CallToolRequest("delete_shot", arguments));
        assertTrue(result.isError()); assertNotNull(fixture.shots.selectById(80L));
        assertEquals(0, fixture.count("mcp_business_operation"));
    }

    @Test void legacyUpdateSwitchMigratesToDedicatedToolsAndCanBeSaved() throws Exception {
        configure(true, true);
        var legacy = control.current(); legacy.getTools().put("batch_update_records", false);
        configs.save(fixture.json.write(legacy));
        var migrated = control.current(); assertFalse(migrated.getTools().containsKey("batch_update_records"));
        for (var capability : factory.getBean(McpRecordTools.class).capabilities()) {
            assertEquals(false, migrated.getTools().get(capability.get("batchUpdate")));
            assertEquals(false, migrated.getTools().get(capability.get("update")));
        }
        assertEquals(200, admin("/system/mcp/tools/batch_update_shots", Map.of("revision", revision(), "enabled", true)).path("code").asInt());
        assertTrue(toolNames().contains("batch_update_shots")); assertFalse(toolNames().contains("batch_update_props"));
        assertFalse(configs.find().getConfigValue().contains("batch_update_records"));
    }

    @Test void retiredUploadSwitchesAreDiscardedAndDoNotBlockSavingSettings() throws Exception {
        configure(true, true);
        var settings = control.current();
        settings.getTools().put("upload_asset_image", false);
        settings.getTools().put("batch_upload_asset_images", true);
        settings.getTools().put("get_shot", false);
        new JdbcTemplate(fixture.dataSource).update("UPDATE sys_config SET config_value=? WHERE id=900000000000000019", fixture.json.write(settings));
        assertFalse(control.current().getTools().containsKey("upload_asset_image"));
        assertFalse(control.current().getTools().containsKey("batch_upload_asset_images"));
        assertFalse(control.current().getTools().get("get_shot"));
        assertEquals(200, configure(true, true).path("code").asInt());
        assertFalse(configs.find().getConfigValue().contains("upload_asset_image"));
    }

    @Test void savedSettingsAndEncryptedTokenSurviveAControlServiceRestart() throws Exception {
        assertEquals(200, configure(true, true).path("code").asInt());
        admin("/system/mcp/tools/get_shot", Map.of("revision", revision(), "enabled", false));
        var restarted = new McpRuntimeControl(fixture.properties, configs, fixture.json, factory.getBean(CryptoUtil.class), inventory, factory.getBean(McpRecordTools.class),
                factory.getBeanProvider(McpStatelessSyncServer.class), factory.getBeanProvider(McpToolCatalog.class));
        assertFalse(fixture.properties.isEnabled()); assertTrue(restarted.current().isEnabled());
        assertFalse(restarted.current().getTools().get("get_shot")); assertEquals(TOKEN, restarted.token(restarted.current()));
        assertTrue(restarted.current().getAllowedProjectIds().contains(McpBusinessDatabaseTest.ROOT));
        assertFalse(restarted.view().toString().contains(TOKEN));
    }
}
