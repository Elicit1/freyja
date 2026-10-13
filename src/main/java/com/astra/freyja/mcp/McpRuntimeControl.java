package com.astra.freyja.mcp;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.McpServerConfigMapper;
import com.astra.freyja.entity.SysConfig;
import com.astra.freyja.util.CryptoUtil;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;

/** Server controls are ordinary admin settings, never published as MCP tools. */
@Service
@RequiredArgsConstructor
public class McpRuntimeControl {
    private final McpProperties properties;
    private final McpServerConfigMapper configs;
    private final McpJson json;
    private final CryptoUtil crypto;
    private final McpToolInventory inventory;
    private final ObjectProvider<McpStatelessSyncServer> servers;
    private final ObjectProvider<McpToolCatalog> catalogs;

    public McpRuntimeSettings current() { return decode(configs.find()); }
    private McpRuntimeSettings defaults() {
        McpRuntimeSettings value = new McpRuntimeSettings(); value.setEnabled(properties.isEnabled()); value.setWriteEnabled(properties.isWriteEnabled());
        value.setAllowAllProjects(properties.isAllowAllProjects()); value.setAllowCreateProjects(properties.isAllowCreateProjects());
        value.setAllowedProjectIds(new LinkedHashSet<>(properties.getAllowedProjectIds())); value.setAllowedOrigins(new LinkedHashSet<>(properties.getAllowedOrigins())); return value;
    }
    private McpRuntimeSettings decode(SysConfig row) {
        if (row == null) return defaults();
        if (!McpServerConfigMapper.CONFIG_KEY.equals(row.getConfigKey()) || !Integer.valueOf(1).equals(row.getStatus()))
            throw new BizException(409, "MCP 设置记录无效，请恢复管理配置");
        try { return json.mapper().readerForUpdating(defaults()).readValue(row.getConfigValue()); }
        catch (Exception e) { throw new BizException(409, "MCP 设置格式无效，请修复配置"); }
    }
    public String token(McpRuntimeSettings settings) {
        return settings.getTokenCiphertext() == null ? properties.getToken() : crypto.safeDecrypt(settings.getTokenCiphertext());
    }
    public static boolean validToken(String token) { return token != null && token.length() >= 24 && !token.isBlank(); }
    public boolean toolEnabled(McpRuntimeSettings state, McpTool<?> tool) {
        if (tool.name().equals("get_business_operation") && !state.isWriteEnabled()) return false;
        return !Boolean.FALSE.equals(state.getTools().get(tool.name())) && (tool.effect() == McpTool.Effect.READ || state.isWriteEnabled());
    }
    public void requireTool(String name, McpTool.Effect effect) {
        var state = current();
        if (!state.isEnabled()) throw new BizException(403, "MCP_DISABLED");
        if (Boolean.FALSE.equals(state.getTools().get(name))) throw new BizException(403, "MCP_TOOL_DISABLED");
        if (effect == McpTool.Effect.WRITE && !state.isWriteEnabled()) throw new BizException(403, "MCP_WRITE_DISABLED");
    }
    public Map<String, Object> view() {
        var state = current(); Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", state.getRevision()); result.put("enabled", state.isEnabled()); result.put("writeEnabled", state.isWriteEnabled());
        result.put("allowAllProjects", state.isAllowAllProjects()); result.put("allowCreateProjects", state.isAllowCreateProjects());
        result.put("allowedProjectIds", state.getAllowedProjectIds()); result.put("allowedOrigins", state.getAllowedOrigins());
        result.put("tokenConfigured", validToken(token(state))); result.put("endpoint", "/mcp"); result.put("protocol", "Streamable HTTP");
        var catalog = catalogs.getObject();
        result.put("tools", inventory.all().stream().map(tool -> Map.of("name", tool.name(), "description", tool.description(), "effect", tool.effect(),
                "enabled", !Boolean.FALSE.equals(state.getTools().get(tool.name())), "published", state.isEnabled() && toolEnabled(state, tool),
                "inputSchema", catalog.specification(tool).tool().inputSchema())).toList());
        return result;
    }
    @Transactional(rollbackFor = Exception.class)
    public void update(McpRuntimeSettings.Update input) {
        if (input == null || input.enabled() == null || input.writeEnabled() == null || input.allowAllProjects() == null || input.allowCreateProjects() == null
                || input.allowedProjectIds() == null || input.allowedOrigins() == null) throw new BizException(400, "请提交完整的服务配置");
        edit(input.revision(), state -> {
            state.setEnabled(input.enabled()); state.setWriteEnabled(input.writeEnabled()); state.setAllowAllProjects(input.allowAllProjects()); state.setAllowCreateProjects(input.allowCreateProjects());
            state.setAllowedProjectIds(new LinkedHashSet<>(input.allowedProjectIds())); state.setAllowedOrigins(new LinkedHashSet<>(input.allowedOrigins()));
            if (input.tools() != null) state.getTools().putAll(input.tools());
            if (input.token() != null && !input.token().isEmpty()) {
                if (!validToken(input.token())) throw new BizException(400, "访问令牌至少需要 24 个字符");
                state.setTokenCiphertext(crypto.encrypt(input.token()));
            }
        });
    }
    @Transactional(rollbackFor = Exception.class)
    public void switchServer(McpRuntimeSettings.Switch input) { switchValue(null, input); }
    @Transactional(rollbackFor = Exception.class)
    public void switchTool(String tool, McpRuntimeSettings.Switch input) { switchValue(tool, input); }
    private void switchValue(String tool, McpRuntimeSettings.Switch input) {
        if (input == null || input.enabled() == null) throw new BizException(400, "缺少开关状态");
        edit(input.revision(), state -> { if (tool == null) state.setEnabled(input.enabled()); else state.getTools().put(tool, input.enabled()); });
    }
    private void edit(Integer revision, Consumer<McpRuntimeSettings> action) {
        configs.reserve(json.write(defaults())); var row = configs.lock();
        if (row == null) throw new BizException(409, "MCP 配置记录不可用");
        var state = decode(row);
        if (revision == null || revision != state.getRevision()) throw new BizException(409, "MCP 配置已变更，请刷新后重试");
        action.accept(state); validate(state); state.setRevision(state.getRevision() + 1);
        if (configs.save(json.write(state)) != 1) throw new BizException(409, "MCP 配置保存冲突");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { publish(state); }
        });
    }
    private void validate(McpRuntimeSettings state) {
        if (state.isEnabled() && !validToken(token(state))) throw new BizException(400, "开启 MCP 前请配置至少 24 字符的访问令牌");
        if (state.getAllowedProjectIds().size() > 1000 || state.getAllowedOrigins().size() > 100) throw new BizException(400, "授权列表过长");
        state.getAllowedProjectIds().forEach(McpScope::id);
        for (String origin : state.getAllowedOrigins()) {
            try { URI uri = URI.create(origin); if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) throw new IllegalArgumentException(); }
            catch (RuntimeException e) { throw new BizException(400, "Origin 应为完整来源，例如 https://studio.example，不能包含路径"); }
        }
        Set<String> known = new HashSet<>(); inventory.all().forEach(tool -> known.add(tool.name()));
        if (!known.containsAll(state.getTools().keySet()) || state.getTools().containsValue(null)) throw new BizException(400, "工具配置包含未知名称或无效状态");
    }
    public synchronized void publish(McpRuntimeSettings state) {
        state = current();
        var server = servers.getIfAvailable(); if (server == null) return;
        var catalog = catalogs.getObject(); Set<String> current = new HashSet<>(); server.listTools().forEach(t -> current.add(t.name()));
        for (McpTool<?> tool : inventory.all()) {
            boolean enabled = toolEnabled(state, tool);
            if (current.contains(tool.name()) && !enabled) server.removeTool(tool.name());
            else if (!current.contains(tool.name()) && enabled) server.addTool(catalog.specification(tool));
        }
    }
}
