package com.astra.freyja.capability;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.profile.*;
import com.astra.freyja.agent.tool.*;
import com.astra.freyja.capability.model.CapabilityModels.*;
import com.astra.freyja.capability.service.*;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.CapabilityMapper;
import com.astra.freyja.dto.agent.AgentConfigDTO;
import com.astra.freyja.mcp.*;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in uses only the dedicated freyja_capability_it database and real Redis. */
@EnabledIfEnvironmentVariable(named = "FREYJA_CAPABILITY_IT", matches = "true")
class CapabilityCatalogIntegrationTest {
    CapabilityCatalogService catalog;
    CapabilityHandlers handlers;
    CapabilityMapper mapper;
    DriverManagerDataSource source;
    TransactionTemplate transactions;
    StringRedisTemplate redis;
    LettuceConnectionFactory connection;
    McpRuntimeControl control;
    final McpJson json = new McpJson();
    final AtomicInteger writes = new AtomicInteger();
    final McpPrincipal caller = new McpPrincipal(true, false, false, Set.of(2032095628944588801L));

    @SuppressWarnings("unchecked") static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class); when(provider.getObject()).thenReturn(value); when(provider.getIfAvailable()).thenReturn(value); return provider;
    }
    @BeforeEach void setup() throws Exception {
        String url = System.getenv().getOrDefault("FREYJA_CAPABILITY_JDBC_URL", "jdbc:mysql://127.0.0.1:3306/freyja_capability_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
        assertTrue(url.contains("/freyja_capability_it?"), "必须使用专用测试数据库");
        source = new DriverManagerDataSource(url, System.getenv("FREYJA_CAPABILITY_JDBC_USER"), System.getenv("FREYJA_CAPABILITY_JDBC_PASSWORD"));
        new ResourceDatabasePopulator(new ClassPathResource("sql/upgrade_unified_capabilities.sql")).execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        for (String table : List.of("ai_capability_outbox", "ai_capability_release", "ai_capability_binding", "ai_capability_version", "ai_capability_definition", "ai_capability_rule", "ai_capability_catalog_state")) jdbc.execute("DELETE FROM " + table);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source);
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true); factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:/mapper/capability/*.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(CapabilityMapper.class);
        var redisConfig = new RedisStandaloneConfiguration("127.0.0.1", 6379); redisConfig.setPassword(System.getenv("FREYJA_CAPABILITY_REDIS_PASSWORD"));
        connection = new LettuceConnectionFactory(redisConfig); connection.afterPropertiesSet(); connection.start();
        redis = new StringRedisTemplate(connection);
        var inventory = mock(McpToolInventory.class);
        var readSchema = Map.<String, Object>of("type", "object", "additionalProperties", false, "required", List.of("dramaId", "shotId"), "properties",
                Map.of("dramaId", Map.of("type", "string"), "shotId", Map.of("type", "string")));
        var writeSchema = Map.<String, Object>of("type", "object", "additionalProperties", false, "required", List.of("dramaId", "requestId", "data"), "properties",
                Map.of("dramaId", Map.of("type", "string"), "requestId", Map.of("type", "string"), "data", Map.of("type", "object", "additionalProperties", false, "required", List.of("name"), "properties", Map.of("name", Map.of("type", "string", "maxLength", 64)))));
        McpTool<McpInputs.Shot> read = new McpTool<>("get_shot", "读取分镜", McpInputs.Shot.class, McpTool.Effect.READ, readSchema,
                (c, i) -> Map.of("id", i.shotId(), "dramaId", i.dramaId()));
        McpTool<McpRecordInputs.Create> write = new McpTool<>("create_prop", "创建道具", McpRecordInputs.Create.class, McpTool.Effect.WRITE, writeSchema,
                (c, i) -> { if (!c.write()) throw new BizException(403, "禁止写入"); writes.incrementAndGet(); return Map.of("requestId", i.requestId(), "name", i.data().get("name")); });
        McpTool<McpInputs.Empty> writeOnlyRead = new McpTool<>("operation_result", "读取写入结果", McpInputs.Empty.class, McpTool.Effect.READ,
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false), (c, i) -> Map.of("status", "ok"));
        when(inventory.registeredHandlers()).thenReturn(List.of(read, write, writeOnlyRead.requiringWritePermission()));
        var profile = mock(AgentProfile.class); when(profile.code()).thenReturn("core-assistant"); when(profile.requiredTools()).thenReturn(List.of("request_user_input"));
        var profiles = new AgentProfileRegistry(List.of(profile));
        AgentTool<McpInputs.Empty> rulesProbe = new AgentTool<>() {
            public String name() { return "rules_probe"; }
            public String description() { return "返回当前规则用于测试"; }
            public Class<McpInputs.Empty> inputType() { return McpInputs.Empty.class; }
            public ToolResult execute(McpInputs.Empty input, ToolContext context) { return ToolResult.success(CapabilityRuleContext.current()); }
        };
        handlers = new CapabilityHandlers(provider(inventory), provider(List.of(new RequestUserInputTool(), rulesProbe)), provider(profiles), json, new AgentJson());
        control = mock(McpRuntimeControl.class); var settings = new McpRuntimeSettings(); settings.setEnabled(true); settings.setWriteEnabled(true);
        when(control.current()).thenReturn(settings); when(control.toolEnabled(any(), any())).thenReturn(true);
        catalog = new CapabilityCatalogService(mapper, source, transactions, handlers, new CapabilitySchemaPolicy(), json, provider(redis), provider(control), provider(profiles));
        catalog.initialize();
    }
    @AfterEach void close() { if (connection != null) connection.destroy(); }
    Binding binding(Release r, Consumer consumer, String name) { return r.bindings().stream().filter(b -> b.consumer() == consumer && b.name().equals(name)).findFirst().orElseThrow(); }
    ToolContext context(AgentSnapshot capabilities, String type) {
        var config = new AgentConfigDTO(); config.setAgentCode("core-assistant");
        var snapshot = new Snapshot("core-assistant", 1, 1, config, "", Map.of(), Map.of(), null, type, null, capabilities);
        return new ToolContext(1L, 2L, 3L, 2032095628944588801L, 4L, "step-key", snapshot);
    }
    Release editBinding(Release release, String id, java.util.function.UnaryOperator<Binding> edit) {
        var bindings = release.bindings().stream().map(b -> b.id().equals(id) ? edit.apply(b) : b).toList();
        catalog.save(new Edit(mapper.state().getRevision(), bindings, release.instructions(), release.rules()));
        catalog.publish(new Publish(mapper.state().getRevision(), "测试发布")); return catalog.current();
    }
    Binding renamed(Binding b, String name, boolean enabled) { return new Binding(b.id(), b.capabilityId(), b.versionId(), b.handlerKey(), b.consumer(), b.consumerCode(), name,
            "数据库更新说明", b.schema(), enabled, b.required(), b.contextMode(), b.projectRequired(), b.effect(), b.recoveryMode()); }

    @Test void internalAndMcpShareHandlerAndInternalContextCannotBeForged() {
        var release = catalog.current(); var external = binding(release, Consumer.MCP, "get_shot"); var internal = binding(release, Consumer.INTERNAL_AGENT, "get_shot");
        assertEquals(external.capabilityId(), internal.capabilityId()); assertEquals(external.versionId(), internal.versionId());
        var snapshot = catalog.agentSnapshot("core-assistant", List.of("get_shot", "create_prop"));
        assertEquals(Map.of("id", "2032095628944588810", "dramaId", "2032095628944588801"), catalog.agentTool(internal).execute(Map.of("shotId", "2032095628944588810"), context(snapshot, "NORMAL")).data());
        assertThrows(BizException.class, () -> catalog.agentTool(internal).execute(Map.of("shotId", "1", "dramaId", "999"), context(snapshot, "NORMAL")));
        var write = binding(release, Consumer.INTERNAL_AGENT, "create_prop");
        var saved = (Map<?, ?>) catalog.agentTool(write).execute(Map.of("data", Map.of("name", "剑")), context(snapshot, "NORMAL")).data();
        assertEquals("agent-step-3", saved.get("requestId")); assertEquals(1, writes.get());
        assertThrows(BizException.class, () -> catalog.agentTool(write).execute(Map.of("data", Map.of("name", "剑")), context(snapshot, "CONFIG_TEST")));
        assertEquals(1, writes.get());
    }
    @Test void publishedNamesInstructionsAndSchemasAreActuallyUsedByMcp() throws Exception {
        var release = catalog.current(); var old = binding(release, Consumer.MCP, "get_shot");
        release = editBinding(release, old.id(), b -> renamed(b, "read_story_shot", true));
        catalog.save(new Edit(mapper.state().getRevision(), release.bindings(), "数据库创作指南", "用户要求配乐时允许背景音乐"));
        catalog.publish(new Publish(mapper.state().getRevision(), "指南发布"));
        var dispatcher = new CapabilityMcpDispatcher(catalog, new McpToolCatalog(json), control);
        var init = rpc(dispatcher, "initialize", Map.of("protocolVersion", "2025-06-18")); assertTrue(init.contains("数据库创作指南"));
        var list = rpc(dispatcher, "tools/list", Map.of()); assertTrue(list.contains("read_story_shot")); assertFalse(list.contains("\"name\":\"get_shot\""));
        var result = rpc(dispatcher, "tools/call", Map.of("name", "read_story_shot", "arguments", Map.of("dramaId", "2032095628944588801", "shotId", "2032095628944588810")));
        assertTrue(result.contains("2032095628944588810")); assertTrue(result.contains("\"id\":9007199254740991"));
    }
    @Test void pinnedNativeRulesAndWritePermissionSurvivePublicationAndRename() throws Exception {
        var release = catalog.current(); var pinned = catalog.agentSnapshot("core-assistant", List.of("rules_probe", "operation_result"));
        var external = binding(release, Consumer.MCP, "operation_result");
        release = editBinding(release, external.id(), b -> renamed(b, "read_saved_result", true));
        catalog.save(new Edit(mapper.state().getRevision(), release.bindings(), release.instructions(), "新发布规则"));
        catalog.publish(new Publish(mapper.state().getRevision(), "更新规则"));
        var nativeBinding = pinned.bindings().stream().filter(b -> b.name().equals("rules_probe")).findFirst().orElseThrow();
        assertEquals(CapabilityCatalogService.DEFAULT_RULES, catalog.agentTool(nativeBinding).execute(Map.of(), context(pinned, "NORMAL")).data());
        assertNull(CapabilityRuleContext.current());
        var renamedTool = catalog.mcpTools().stream().filter(t -> t.name().equals("read_saved_result")).findFirst().orElseThrow();
        assertTrue(renamedTool.writePermissionRequired());
        var definition = new McpToolCatalog(json).specification(renamedTool);
        var reader = new McpPrincipal(false, false, false, caller.projects());
        var denied = definition.callHandler().apply(io.modelcontextprotocol.common.McpTransportContext.create(Map.of(McpPrincipal.CONTEXT_KEY, reader)), new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("read_saved_result", Map.of()));
        assertTrue(denied.isError());
        var internal = pinned.bindings().stream().filter(b -> b.name().equals("operation_result")).findFirst().orElseThrow();
        assertThrows(BizException.class, () -> catalog.agentTool(internal).execute(Map.of(), context(pinned, "CONFIG_TEST")));
        var dispatcher = new CapabilityMcpDispatcher(catalog, new McpToolCatalog(json), control);
        assertTrue(rpc(dispatcher, "initialize", Map.of("protocolVersion", "unknown")).contains("2025-11-25"));
        assertTrue(rpc(dispatcher, "initialize", Map.of()).contains("-32602"));
    }
    String rpc(CapabilityMcpDispatcher dispatcher, String method, Map<String, Object> params) throws Exception {
        var request = new MockHttpServletRequest("POST", "/mcp"); var response = new MockHttpServletResponse();
        var rpc = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", 9007199254740991L, "method", method, "params", params));
        assertTrue(dispatcher.handle(request, response, rpc, caller)); assertEquals(200, response.getStatus()); return response.getContentAsString();
    }
    @Test void secondInstanceLoadsNewVersionPinnedTaskKeepsContractAndRevocationWins() {
        var initial = catalog.current(); var pinned = catalog.agentSnapshot("core-assistant", List.of("get_shot"));
        var internal = binding(initial, Consumer.INTERNAL_AGENT, "get_shot");
        var active = editBinding(initial, internal.id(), b -> renamed(b, "inspect_shot", true));
        var second = new CapabilityCatalogService(mapper, source, transactions, handlers, new CapabilitySchemaPolicy(), json, provider(redis), provider(control), provider(null));
        second.initialize(); assertEquals(active.id(), second.current().id());
        assertEquals("get_shot", pinned.bindings().getFirst().name()); assertEquals(initial.id(), pinned.releaseId());
        assertNotNull(catalog.agentTool(pinned.bindings().getFirst()).execute(Map.of("shotId", "8"), context(pinned, "NORMAL")).data());
        editBinding(active, internal.id(), b -> renamed(b, "inspect_shot", false));
        assertThrows(BizException.class, () -> catalog.agentTool(pinned.bindings().getFirst()).execute(Map.of("shotId", "8"), context(pinned, "NORMAL")));
    }
    @Test void cacheFailureFallsBackToMysqlSnapshotsAreImmutableAndPublicationConflicts() {
        var initial = catalog.current(); assertThrows(UnsupportedOperationException.class, () -> initial.bindings().getFirst().schema().put("type", "number"));
        var broken = mock(StringRedisTemplate.class); when(broken.opsForValue()).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("测试连接故障"));
        var second = new CapabilityCatalogService(mapper, source, transactions, handlers, new CapabilitySchemaPolicy(), json, provider(broken), provider(control), provider(null));
        second.initialize(); assertEquals(initial.id(), second.current().id());
        assertEquals(409, assertThrows(BizException.class, () -> catalog.publish(new Publish(-1L, "过期发布"))).getCode());
        catalog.refresh(); assertNotNull(redis.opsForValue().get("freyja:capability:v1:release:" + initial.id()));
    }
    @Test void concurrentPublishSerializesWithMysqlAndRollbackIsANewRevision() throws Exception {
        var initial = catalog.current(); long revision = mapper.state().getRevision();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { try { catalog.publish(new Publish(revision, "并发1")); return true; } catch (BizException e) { return false; } });
            var two = executor.submit(() -> { try { catalog.publish(new Publish(revision, "并发2")); return true; } catch (BizException e) { return false; } });
            assertNotEquals(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        }
        long before = mapper.state().getRevision(); catalog.rollback(new Rollback(before, initial.id()));
        assertTrue(catalog.current().revision() > before); assertEquals(initial.instructions(), catalog.current().instructions());
    }
}
