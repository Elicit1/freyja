package com.astra.freyja.capability;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.profile.AgentProfile;
import com.astra.freyja.agent.profile.AgentProfileRegistry;
import com.astra.freyja.agent.tool.AgentTool;
import com.astra.freyja.capability.model.CapabilityModels.*;
import com.astra.freyja.capability.service.*;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.CapabilityMapper;
import com.astra.freyja.mcp.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real catalog tables without MySQL or Redis. */
class CapabilityCatalogMigrationTest {
    private DriverManagerDataSource source;
    private CapabilityMapper mapper;
    private final McpJson json = new McpJson();
    private AgentProfileRegistry profiles;
    private McpRuntimeControl control;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> result = mock(ObjectProvider.class);
        when(result.getObject()).thenReturn(value);
        when(result.getIfAvailable()).thenReturn(value);
        return result;
    }

    @BeforeEach void setup() throws Exception {
        source = new DriverManagerDataSource("jdbc:h2:mem:capability-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:/mapper/capability/*.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(CapabilityMapper.class);
        var profile = mock(AgentProfile.class);
        when(profile.code()).thenReturn(AgentProfileRegistry.ASSISTANT_CODE);
        when(profile.requiredTools()).thenReturn(List.of());
        profiles = new AgentProfileRegistry(List.of(profile));
        control = mock(McpRuntimeControl.class);
        when(control.current()).thenReturn(new McpRuntimeSettings());
    }

    private Map<String, Object> schema(boolean old, List<String> modes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("script", Map.of("type", "string", "maxLength", old ? 100 : 80));
        if (old) properties.put("dialogue", Map.of("type", "string"));
        properties.put("mode", Map.of("type", "string", "enum", modes));
        return Map.of("type", "object", "additionalProperties", false, "properties", properties, "required", List.of("script"));
    }

    private CapabilityCatalogService catalog(boolean old, List<String> modes) {
        var inventory = mock(McpToolInventory.class);
        McpTool<McpInputs.Empty> tool = new McpTool<>("script_tool", "剧本工具", McpInputs.Empty.class,
                McpTool.Effect.READ, schema(old, modes), (caller, input) -> Map.of());
        McpTool<McpInputs.Empty> healthy = new McpTool<>("status_tool", "状态工具", McpInputs.Empty.class,
                McpTool.Effect.READ, Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                (caller, input) -> Map.of());
        when(inventory.registeredHandlers()).thenReturn(List.of(tool, healthy));
        var handlers = new CapabilityHandlers(provider(inventory), provider(List.<AgentTool<?>>of()), provider(profiles), json, new AgentJson());
        return new CapabilityCatalogService(mapper, source, new TransactionTemplate(new DataSourceTransactionManager(source)),
                handlers, new CapabilitySchemaPolicy(), json, provider(null), provider(control), provider(profiles));
    }

    private Binding external(Release release) {
        return release.bindings().stream().filter(b -> b.consumer() == Consumer.MCP).findFirst().orElseThrow();
    }

    private void customize(CapabilityCatalogService catalog, boolean narrowEnum) {
        Release active = catalog.current();
        List<Binding> edited = active.bindings().stream().map(b -> {
            if (b.consumer() != Consumer.MCP || !b.name().equals("script_tool")) return b;
            Map<String, Object> next = new LinkedHashMap<>(b.schema());
            Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) next.get("properties"));
            properties.put("script", Map.of("type", "string", "maxLength", 60, "description", "创作者脚本"));
            if (narrowEnum) properties.put("mode", Map.of("type", "string", "enum", List.of("A")));
            next.put("properties", properties);
            return new Binding(b.id(), b.capabilityId(), b.versionId(), b.handlerKey(), b.consumer(), b.consumerCode(),
                    b.name(), "自定义工具说明", next, b.enabled(), b.required(), b.contextMode(), b.projectRequired(), b.effect(), b.recoveryMode());
        }).toList();
        catalog.save(new Edit(mapper.state().getRevision(), edited, active.instructions(), "待发布的草稿规则"));
    }

    @Test void migratesPublishedAndPendingDraftWithoutChangingHistory() {
        var original = catalog(true, List.of("A", "B"));
        original.initialize();
        customize(original, false);
        Release old = original.current();
        String originalPayload = mapper.release(Long.valueOf(old.id())).getPayloadJson();
        String originalHash = mapper.release(Long.valueOf(old.id())).getContentHash();

        var upgraded = catalog(false, List.of("A", "B"));
        upgraded.initialize();
        Release active = upgraded.current();
        assertNotEquals(old.id(), active.id());
        assertEquals(originalPayload, mapper.release(Long.valueOf(old.id())).getPayloadJson());
        assertEquals(originalHash, mapper.release(Long.valueOf(old.id())).getContentHash());
        assertFalse(((Map<?, ?>) external(upgraded.release(old.id())).schema().get("properties")).containsKey("dialogue"));
        assertFalse(((Map<?, ?>) external(active).schema().get("properties")).containsKey("dialogue"));
        var view = upgraded.view();
        Release draft = (Release) view.get("draft");
        Binding draftTool = external(draft);
        Map<?, ?> script = (Map<?, ?>) ((Map<?, ?>) draftTool.schema().get("properties")).get("script");
        assertEquals(60, script.get("maxLength"));
        assertEquals("创作者脚本", script.get("description"));
        assertEquals("待发布的草稿规则", draft.rules());
        assertEquals(external(active).versionId(), draftTool.versionId());
        assertTrue(((List<?>) view.get("issues")).isEmpty());
        assertEquals(List.of("script_tool", "status_tool"), upgraded.allowedNames(AgentProfileRegistry.ASSISTANT_CODE));
        assertEquals(2, upgraded.mcpTools().size());
        assertEquals(active.id(), catalog(false, List.of("A", "B")).current().id());
    }

    @Test void incompatiblePublishedBindingDoesNotLockManagementOrOtherModules() {
        var original = catalog(true, List.of("A", "B"));
        original.initialize();
        customize(original, true);
        original.publish(new Publish(mapper.state().getRevision(), "旧版枚举"));

        var upgraded = catalog(false, List.of("C", "D"));
        upgraded.initialize();
        var view = upgraded.view();
        assertTrue(((List<?>) view.get("issues")).size() >= 2);
        assertEquals(1, upgraded.mcpTools().size());
        assertEquals(List.of("script_tool", "status_tool"), upgraded.allowedNames(AgentProfileRegistry.ASSISTANT_CODE));
        int bindingCount = ((Release) view.get("active")).bindings().size();
        upgraded.switchMcpTool("status_tool", false);
        assertEquals(bindingCount, ((Release) upgraded.view().get("active")).bindings().size());
        assertTrue(((List<Map<String, String>>) upgraded.view().get("issues")).stream()
                .anyMatch(issue -> issue.get("message").contains("#/properties/mode/enum")));
        assertEquals(0, upgraded.mcpTools().size());
        Release draft = (Release) upgraded.view().get("draft");
        upgraded.save(new Edit(mapper.state().getRevision(), draft.bindings(), draft.instructions(), "修复期间可保存规则"));
        assertEquals("修复期间可保存规则", ((Release) upgraded.view().get("draft")).rules());

        Binding broken = external((Release) upgraded.view().get("draft"));
        Map<String, Map<String, Object>> repairs = (Map<String, Map<String, Object>>) upgraded.view().get("repairs");
        Map<String, Object> illegal = new LinkedHashMap<>(repairs.get(broken.id()));
        Map<String, Object> illegalProperties = new LinkedHashMap<>((Map<String, Object>) illegal.get("properties"));
        illegalProperties.put("unexpected", Map.of("type", "string"));
        illegal.put("properties", illegalProperties);
        List<Binding> malicious = ((Release) upgraded.view().get("draft")).bindings().stream().map(b -> b.id().equals(broken.id())
                ? new Binding(b.id(), b.capabilityId(), b.versionId(), b.handlerKey(), b.consumer(), b.consumerCode(),
                    b.name(), b.description(), illegal, b.enabled(), b.required(), b.contextMode(), b.projectRequired(), b.effect(), b.recoveryMode())
                : b).toList();
        assertThrows(BizException.class, () -> upgraded.save(new Edit(mapper.state().getRevision(), malicious, draft.instructions(), draft.rules())));
        List<Binding> fixed = ((Release) upgraded.view().get("draft")).bindings().stream().map(b -> b.id().equals(broken.id())
                ? new Binding(b.id(), b.capabilityId(), b.versionId(), b.handlerKey(), b.consumer(), b.consumerCode(),
                    b.name(), b.description(), repairs.get(b.id()), b.enabled(), b.required(), b.contextMode(), b.projectRequired(), b.effect(), b.recoveryMode())
                : b).toList();
        upgraded.save(new Edit(mapper.state().getRevision(), fixed, draft.instructions(), "修复期间可保存规则"));
        upgraded.publish(new Publish(mapper.state().getRevision(), "修复不兼容参数"));
        assertEquals(1, upgraded.mcpTools().size());
        assertTrue(((List<?>) upgraded.view().get("issues")).isEmpty());
    }
}
