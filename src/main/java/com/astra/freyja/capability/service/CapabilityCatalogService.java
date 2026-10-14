package com.astra.freyja.capability.service;

import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.profile.AgentProfileRegistry;
import com.astra.freyja.agent.tool.AgentTool;
import com.astra.freyja.capability.model.CapabilityModels.*;
import com.astra.freyja.capability.model.CapabilityRow;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.CapabilityMapper;
import com.astra.freyja.mcp.*;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityCatalogService {
    public static final String EVENTS = "freyja:capability:v1:events";
    public static final String DEFAULT_RULES = """
            公共创作规则：非提示词内容使用简体中文，已确认姓名及用户原文保持原样。所有业务ID使用字符串。
            项目画幅必须显式选择启用的系统比例；用户未指定先询问，明确委托AI选择时优先启用的16:9，已有项目画幅保留。
            场景类型可空，明确时填写INDOOR/OUTDOOR/STUDIO/VIRTUAL；河畔、街道用OUTDOOR。时段可空，支持DAY/NIGHT/SUNSET/DAWN，不默认日间。
            更新省略字段保留，支持清空的字段显式null清空；不猜测人物状态、场景、造型或媒体来源。
            镜头按完整动作、对白与自然表演估时，普通动作参考3–5秒，舒适长镜头参考7–8秒。预计超过8秒时在完整动作、对白轮次或叙事转折处分镜，避免切碎连续动作，保留用户明确长镜头要求。
            REFERENCE_MODE使用prompt和有序refImages，可选refAudios；FIRST_LAST_FRAME使用videoPrompt和首尾帧图片/提示词。
            默认无背景音乐：保留non_diegetic_music，按模型契约写N/A或none / no background music，保留对白、环境声和必要音效；用户明确需要背景音乐时开启includeBgm并按要求写入该段落。
            关键写入后读回核对场景类型、时长、模式、提示词和人物/造型引用，以最新指纹修正，不覆盖用户锁定。
            """;
    private final CapabilityMapper mapper;
    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final CapabilityHandlers handlers;
    private final CapabilitySchemaPolicy schemas;
    private final McpJson json;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final ObjectProvider<McpRuntimeControl> mcpControls;
    private final ObjectProvider<AgentProfileRegistry> profiles;
    private final Map<String, Release> local = new java.util.concurrent.ConcurrentHashMap<>();
    private final String instanceId = UUID.randomUUID().toString();
    private volatile boolean initialized;
    private final DefaultJsonSchemaValidator validator = new DefaultJsonSchemaValidator();
    private final com.fasterxml.jackson.databind.ObjectMapper storage = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    @EventListener(ApplicationReadyEvent.class) public void start() { initialize(); refresh(); }
    public void initialize() {
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            new ResourceDatabasePopulator(new ClassPathResource("sql/upgrade_unified_capabilities.sql")).execute(dataSource);
            transactions.executeWithoutResult(tx -> {
                mapper.initializeState();
                var state = mapper.lockState();
                if (state.getActiveReleaseId() == null) {
                    List<Binding> bindings = handlers.initialBindings();
                    var controls = mcpControls.getIfAvailable();
                    if (controls != null) {
                        var settings = controls.current();
                        bindings = bindings.stream().map(b -> b.consumer() == Consumer.MCP && Boolean.FALSE.equals(settings.getTools().get(b.name()))
                                ? enabled(b, false) : b).toList();
                    }
                    var initial = new Release(null, 0, bindings, McpServerConfiguration.GUIDE, DEFAULT_RULES);
                    mapper.saveDraft(json.write(initial));
                    Release published = publishLocked(mapper.lockState(), initial, "首次导入内部Agent与MCP能力");
                    mapper.alignDraft(json.write(withVersions(initial, published)));
                } else {
                    migrateLocked(state);
                }
            });
            initialized = true;
        }
    }
    public Release current() {
        initialize();
        CapabilityRow state = mapper.state();
        if (state == null || state.getActiveReleaseId() == null) throw new BizException(503, "能力目录尚未发布");
        String id = state.getActiveReleaseId().toString();
        try { return release(id); }
        catch (BizException failure) {
            // Keep healthy tools discoverable while an administrator repairs an incompatible binding.
            Release raw = rawRelease(id);
            List<Binding> available = new ArrayList<>();
            for (Binding binding : raw.bindings()) try {
                Binding projected = migrateBinding(binding, false);
                verify(List.of(projected));
                available.add(projected);
            } catch (BizException ignored) { }
            Release safe = new Release(raw.id(), raw.revision(), List.copyOf(available), raw.instructions(), raw.rules());
            local.put(id, safe);
            log.warn("能力目录存在待修复绑定，已仅开放 {} 个可用能力: {}", available.size(), failure.getMessage());
            return safe;
        }
    }
    public Release release(String id) {
        Release cached = local.get(id);
        if (cached != null) return cached;
        Release value = migrateSnapshot(rawRelease(id), false);
        verify(value.bindings());
        local.put(id, value);
        return value;
    }
    private Release rawRelease(String id) {
        CapabilityRow row = mapper.release(Long.valueOf(id));
        if (row == null) throw new BizException(409, "能力发布版本不存在");
        String text = null;
        var client = redis.getIfAvailable();
        if (client != null) try { text = client.opsForValue().get(cacheKey(id)); } catch (RuntimeException e) { log.debug("能力缓存不可用，回源MySQL"); }
        if (text == null || !hash(text).equals(row.getContentHash())) text = row.getPayloadJson();
        if (!hash(text).equals(row.getContentHash())) throw new BizException(409, "能力发布快照摘要不一致");
        Release value = read(text);
        if (!id.equals(value.id())) throw new BizException(409, "能力版本标识不一致");
        if (client != null) try { client.opsForValue().set(cacheKey(id), text, Duration.ofHours(24)); } catch (RuntimeException ignored) { }
        return value;
    }
    public Map<String, Object> view() {
        initialize();
        var state = mapper.state();
        Release active = rawRelease(state.getActiveReleaseId().toString());
        Release draft = read(mapper.draft().getPayloadJson());
        List<Map<String, String>> issues = new ArrayList<>();
        for (Binding binding : active.bindings()) issue("active", binding, issues);
        for (Binding binding : draft.bindings()) issue("draft", binding, issues);
        Map<String, Map<String, Object>> repairs = new LinkedHashMap<>();
        for (Binding binding : draft.bindings()) {
            var handler = handlers.all().get(binding.handlerKey());
            if (handler != null) repairs.put(binding.id(), baseline(binding, handler));
        }
        return Map.of("revision", state.getRevision(), "active", active, "draft", draft, "issues", issues, "repairs", repairs,
                "releases", mapper.releases(), "instanceId", instanceId,
                "handlers", handlers.all().values().stream().map(h -> Map.of("key", h.key(), "effect", h.effect(), "recoveryMode", h.recoveryMode(), "schema", h.schema())).toList());
    }
    public void save(Edit input) {
        initialize();
        requireText(input.instructions(), input.rules());
        transactions.executeWithoutResult(tx -> {
            var state = lock(input.revision());
            Map<String, Binding> existing = new HashMap<>();
            read(mapper.draft().getPayloadJson()).bindings().forEach(b -> existing.put(b.id(), b));
            if (input.bindings().size() != existing.size()) throw new BizException(400, "请使用已有能力绑定，不能删除处理器契约");
            Set<String> names = new HashSet<>();
            for (Binding b : input.bindings()) {
                Binding old = existing.get(b.id());
                if (old == null || !old.capabilityId().equals(b.capabilityId()) || !old.handlerKey().equals(b.handlerKey())
                        || old.consumer() != b.consumer() || !old.consumerCode().equals(b.consumerCode()) || old.contextMode() != b.contextMode()
                        || old.required() != b.required() || old.effect() != b.effect() || old.recoveryMode() != b.recoveryMode()
                        || old.projectRequired() != b.projectRequired() || !Objects.equals(old.versionId(), b.versionId()))
                    throw new BizException(400, "不能通过工具元数据修改能力身份、入口或必要上下文");
                if (b.name() == null || !b.name().matches("[A-Za-z0-9_-]{1,128}")
                        || !names.add(b.consumer() + "/" + b.consumerCode() + "/" + b.name())
                        || b.description() == null || b.description().isBlank() || b.description().length() > 20000
                        || b.required() && !b.enabled()) throw new BizException(400, "能力名称、说明或启用状态无效");
                if (!Objects.equals(old.schema(), b.schema())) verify(List.of(b));
            }
            mapper.saveDraft(json.write(new Release(null, state.getRevision() + 1, input.bindings(), input.instructions(), input.rules())));
        });
    }
    public void publish(Publish input) {
        initialize();
        transactions.executeWithoutResult(tx -> {
            var state = lock(input.revision());
            Release draft = read(mapper.draft().getPayloadJson()); verify(draft.bindings());
            Release published = publishLocked(state, draft, input.note());
            mapper.alignDraft(json.write(withVersions(draft, published)));
        });
        refresh();
    }
    public void rollback(Rollback input) {
        initialize();
        Release previous = release(input.releaseId());
        transactions.executeWithoutResult(tx -> {
            var state = lock(input.revision());
            verify(previous.bindings());
            mapper.saveDraft(json.write(previous));
            Release published = publishLocked(mapper.lockState(), previous, "回滚至版本 " + previous.id());
            mapper.alignDraft(json.write(withVersions(previous, published)));
        });
        refresh();
    }
    private void migrateLocked(CapabilityRow state) {
        Release active = rawRelease(state.getActiveReleaseId().toString());
        Release draft = read(mapper.draft().getPayloadJson());
        Release nextActive = null;
        try { nextActive = migrateSnapshot(active, false); verify(nextActive.bindings()); }
        catch (BizException e) { log.warn("已发布能力存在待人工修复的兼容冲突: {}", e.getMessage()); }
        Release nextDraft = migrateCompatibleBindings(draft, true);
        if (nextActive != null && !active.bindings().equals(nextActive.bindings())) {
            Release published = publishLocked(state, nextActive, "自动兼容当前处理器契约");
            mapper.alignDraft(json.write(withVersions(nextDraft, published)));
        } else if (!draft.bindings().equals(nextDraft.bindings())) {
            mapper.saveDraft(json.write(new Release(null, state.getRevision() + 1, nextDraft.bindings(), nextDraft.instructions(), nextDraft.rules())));
        }
    }
    private Release migrateCompatibleBindings(Release source, boolean draft) {
        List<Binding> bindings = new ArrayList<>();
        for (Binding binding : source.bindings()) try { bindings.add(migrateBinding(binding, draft)); }
        catch (BizException e) {
            log.warn("能力绑定待人工修复: {}", e.getMessage());
            bindings.add(binding);
        }
        return new Release(source.id(), source.revision(), List.copyOf(bindings), source.instructions(), source.rules());
    }
    private Release migrateSnapshot(Release source, boolean draft) {
        List<Binding> bindings = source.bindings().stream().map(b -> migrateBinding(b, draft)).toList();
        return new Release(source.id(), source.revision(), bindings, source.instructions(), source.rules());
    }
    private Binding migrateBinding(Binding binding, boolean draft) {
        var handler = handlers.require(binding.handlerKey());
        Map<String, Object> currentSchema = baseline(binding, handler);
        try { schemas.verify(binding.name(), currentSchema, binding.schema()); return binding; }
        catch (BizException incompatible) {
            String contract = mapper.versionContract(Long.valueOf(binding.versionId()), Long.valueOf(binding.capabilityId()));
            if (contract == null && draft) contract = mapper.latestContract(Long.valueOf(binding.capabilityId()));
            if (contract == null) throw new BizException(409, "能力「" + binding.name() + "」缺少历史处理器契约，需在能力管理页恢复当前参数结构");
            Map<String, Object> previous = handlers.parse(contract);
            if (binding.consumer() == Consumer.INTERNAL_AGENT && handler.business() != null) previous = handlers.internalSchema(previous);
            Map<String, Object> migrated = schemas.rebase(binding.name(), previous, binding.schema(), currentSchema);
            return withSchema(binding, migrated);
        }
    }
    private Map<String, Object> baseline(Binding binding, CapabilityHandlers.Handler handler) {
        return binding.consumer() == Consumer.INTERNAL_AGENT && handler.business() != null
                ? handlers.internalSchema(handler.schema()) : handler.schema();
    }
    private void issue(String source, Binding binding, List<Map<String, String>> issues) {
        try { verify(List.of(migrateBinding(binding, source.equals("draft")))); }
        catch (BizException e) { issues.add(Map.of("source", source, "bindingId", binding.id(), "name", binding.name(), "message", e.getMessage())); }
    }
    private static Binding withSchema(Binding b, Map<String, Object> schema) {
        return new Binding(b.id(), b.capabilityId(), b.versionId(), b.handlerKey(), b.consumer(), b.consumerCode(), b.name(),
                b.description(), schema, b.enabled(), b.required(), b.contextMode(), b.projectRequired(), b.effect(), b.recoveryMode());
    }
    private Release withVersions(Release draft, Release published) {
        Map<String, String> versions = new HashMap<>();
        published.bindings().forEach(b -> versions.put(b.capabilityId(), b.versionId()));
        List<Binding> bindings = draft.bindings().stream().map(b -> {
            return new Binding(b.id(), b.capabilityId(), schemaCompatible(b) ? versions.getOrDefault(b.capabilityId(), b.versionId()) : b.versionId(),
                    b.handlerKey(), b.consumer(), b.consumerCode(), b.name(), b.description(), b.schema(), b.enabled(), b.required(),
                    b.contextMode(), b.projectRequired(), b.effect(), b.recoveryMode());
        }).toList();
        return new Release(null, published.revision(), bindings, draft.instructions(), draft.rules());
    }
    private boolean schemaCompatible(Binding binding) {
        try { schemas.verify(binding.name(), baseline(binding, handlers.require(binding.handlerKey())), binding.schema()); return true; }
        catch (BizException ignored) { return false; }
    }
    private CapabilityRow lock(Long expected) {
        CapabilityRow state = mapper.lockState();
        if (expected == null || !expected.equals(state.getRevision())) throw new BizException(409, "能力配置已修改，请刷新后重试");
        return state;
    }
    private Release publishLocked(CapabilityRow state, Release draft, String note) {
        long revision = state.getRevision() + 1;
        String id = Long.toString(IdWorker.getId());
        Map<String, String> versions = new HashMap<>();
        List<Binding> bindings = new ArrayList<>();
        for (Binding b : draft.bindings()) {
            String version = schemaCompatible(b) ? versions.computeIfAbsent(b.capabilityId(), cap -> {
                long v = IdWorker.getId();
                mapper.insertDefinition(Long.valueOf(cap), b.handlerKey(), b.name());
                mapper.insertVersion(v, Long.valueOf(cap), revision, json.write(handlers.require(b.handlerKey()).schema()));
                return Long.toString(v);
            }) : b.versionId();
            var next = new Binding(b.id(), b.capabilityId(), version, b.handlerKey(), b.consumer(), b.consumerCode(), b.name(),
                    b.description(), b.schema(), b.enabled(), b.required(), b.contextMode(), b.projectRequired(), b.effect(), b.recoveryMode());
            bindings.add(next);
            mapper.saveBinding(Long.valueOf(b.id()), Long.valueOf(b.capabilityId()), b.consumer().name(), b.name(), json.write(next));
        }
        Release published = new Release(id, revision, List.copyOf(bindings), draft.instructions(), draft.rules());
        String text = json.write(published);
        mapper.saveRelease(Long.valueOf(id), revision, text, hash(text), note);
        mapper.saveRule("SHARED_CREATIVE_RULES", draft.rules());
        mapper.saveRule("MCP_INSTRUCTIONS", draft.instructions());
        mapper.activate(Long.valueOf(id), revision);
        mapper.enqueue(IdWorker.getId(), revision, Long.valueOf(id));
        return published;
    }
    public void verify(List<Binding> bindings) {
        if (bindings == null || bindings.isEmpty() || bindings.size() > 500) throw new BizException(400, "能力绑定数量无效");
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (Binding b : bindings) {
            if (b == null || b.id() == null || !ids.add(b.id()) || b.name() == null || !b.name().matches("[A-Za-z0-9_-]{1,128}")
                    || !names.add(b.consumer() + "/" + b.consumerCode() + "/" + b.name())) throw new BizException(400, "能力名称/标识无效或重复");
            var h = handlers.require(b.handlerKey());
            if (b.effect() != h.effect() || b.recoveryMode() != h.recoveryMode() || b.projectRequired() != h.projectRequired()
                    || (b.consumer() == Consumer.MCP && h.nativeTool() != null)) throw new BizException(400, "处理器效果、恢复模式或入口上下文不匹配");
            if (b.required() && !b.enabled()) throw new BizException(400, "不能停用内部工作流的必需能力");
            if (b.description() == null || b.description().isBlank() || b.description().length() > 20000) throw new BizException(400, "能力说明无效");
            schemas.verify(b.name(), baseline(b, h), b.schema());
        }
    }
    public void validate(Binding binding, Map<String, Object> input) {
        if (!validator.validate(binding.schema(), input).valid()) throw new BizException(400, "参数不符合已发布能力Schema，业务ID须为字符串");
    }
    public List<Binding> bindings(Release release, Consumer consumer, String code) {
        return release.bindings().stream().filter(b -> b.consumer() == consumer && b.consumerCode().equals(code) && b.enabled()).toList();
    }
    public Binding requireCurrent(String bindingId) {
        return current().bindings().stream().filter(b -> b.id().equals(bindingId) && b.enabled()).findFirst()
                .orElseThrow(() -> new BizException(403, "CAPABILITY_DISABLED: 能力已撤销"));
    }
    public List<String> allowedNames(String code) { return bindings(current(), Consumer.INTERNAL_AGENT, code).stream().map(Binding::name).toList(); }
    public List<String> requiredNames(String code) { return bindings(current(), Consumer.INTERNAL_AGENT, code).stream().filter(Binding::required).map(Binding::name).toList(); }
    public AgentSnapshot agentSnapshot(String code, List<String> names) {
        Release release = current();
        List<Binding> all = bindings(release, Consumer.INTERNAL_AGENT, code);
        if (!all.stream().map(Binding::name).toList().containsAll(names)) throw new BizException(403, "Agent配置包含未授权能力");
        return new AgentSnapshot(release.id(), all.stream().filter(b -> names.contains(b.name())).toList());
    }
    public List<McpTool<?>> mcpTools() {
        return mcpTools(current());
    }
    public List<McpTool<?>> mcpTools(Release release) {
        return mcpTools(release, false);
    }
    public List<McpTool<?>> mcpTools(Release release, boolean includeDisabled) {
        return release.bindings().stream().filter(b -> b.consumer() == Consumer.MCP && b.consumerCode().equals("freyja") && (includeDisabled || b.enabled()))
                .<McpTool<?>>map(this::mcpTool).toList();
    }
    private McpTool<?> mcpTool(Binding b) {
        var source = handlers.require(b.handlerKey()).business();
        var tool = new McpTool<Map<String, Object>>(b.name(), b.description(),
                mapType(),
                McpTool.Effect.valueOf(b.effect().name()), b.schema(), (caller, args) -> {
                    requireCurrent(b.id()); validate(b, args);
                    return handlers.invokeBusiness(handlers.require(b.handlerKey()), caller, args);
                }).validated(source.validator());
        return source.writePermissionRequired() ? tool.requiringWritePermission() : tool;
    }
    public boolean mcpBindingEnabled(Release release, String name) {
        return release.bindings().stream().anyMatch(b -> b.consumer() == Consumer.MCP && b.name().equals(name) && b.enabled());
    }
    public List<Map<String, Object>> crudCapabilities(Release release, List<Map<String, Object>> baseline) {
        Map<String, String> names = new HashMap<>();
        release.bindings().stream().filter(b -> b.consumer() == Consumer.MCP).forEach(b -> names.put(b.handlerKey(), b.name()));
        return baseline.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>(row);
            for (String op : List.of("read", "create", "update", "delete", "batchUpdate")) {
                String source = (String) row.get(op); result.put(op, names.getOrDefault(Consumer.MCP.name() + ":" + source, source));
            }
            return result;
        }).toList();
    }
    public void switchMcpTool(String name, boolean enabled) {
        initialize();
        transactions.executeWithoutResult(tx -> {
            var state = mapper.lockState();
            var active = rawRelease(state.getActiveReleaseId().toString());
            var target = active.bindings().stream().filter(b -> b.consumer() == Consumer.MCP && b.name().equals(name)).findFirst()
                    .orElseThrow(() -> new BizException(404, "MCP能力绑定不存在"));
            verify(List.of(migrateBinding(target, false)));
            var draft = read(mapper.draft().getPayloadJson());
            var edited = draft.bindings().stream().map(b -> b.id().equals(target.id()) ? enabled(b, enabled) : b).toList();
            mapper.saveDraft(json.write(new Release(null, state.getRevision() + 1, edited, draft.instructions(), draft.rules())));
            var live = migrateCompatibleBindings(active, false).bindings().stream().map(b -> b.id().equals(target.id()) ? enabled(b, enabled) : b).toList();
            Release published = publishLocked(mapper.lockState(), new Release(null, state.getRevision() + 1, live, active.instructions(), active.rules()), "MCP工具开关修改");
            mapper.alignDraft(json.write(withVersions(new Release(null, published.revision(), edited, draft.instructions(), draft.rules()), published)));
        });
    }
    @SuppressWarnings("unchecked") private static Class<Map<String, Object>> mapType() { return (Class<Map<String, Object>>) (Class<?>) Map.class; }
    public AgentTool<Map<String, Object>> agentTool(Binding b) {
        return new AgentTool<>() {
            public String name() { return b.name(); }
            public String description() { return b.description(); }
            @SuppressWarnings("unchecked") public Class<Map<String, Object>> inputType() { return (Class<Map<String, Object>>) (Class<?>) Map.class; }
            public int version() { return Math.toIntExact(releaseForVersion(b).revision()); }
            public Effect effect() { return b.effect(); }
            public RecoveryMode recoveryMode() { return b.recoveryMode(); }
            public boolean projectRequired() { return b.projectRequired(); }
            public ToolDescriptor descriptor() { return new ToolDescriptor(b.name(), version(), b.description(), b.effect(), b.recoveryMode(), b.projectRequired(), json.write(b.schema())); }
            public ToolResult execute(Map<String, Object> input, ToolContext context) { return invoke(input, context, false, null); }
            public ToolResult reconcile(Map<String, Object> input, ToolContext context, WaitHandle wait) { return invoke(input, context, true, wait); }
            private ToolResult invoke(Map<String, Object> input, ToolContext context, boolean recover, WaitHandle wait) {
                requireCurrent(b.id()); validate(b, input);
                if (b.effect() == Effect.WRITE && "CONFIG_TEST".equals(context.snapshot().runType())) throw new BizException(403, "测试模式禁止业务写入");
                var h = handlers.require(b.handlerKey());
                if (h.nativeTool() != null) {
                    try (var scope = CapabilityRuleContext.open(release(context.snapshot().capabilities().releaseId()).rules())) {
                        return handlers.invokeNative(h, input, context, recover, wait);
                    }
                }
                Map<String, Object> args = new LinkedHashMap<>(input);
                if (b.projectRequired() && context.dramaId() == null) throw new BizException(403, "当前能力需要绑定项目");
                if (h.schema().get("properties") instanceof Map<?, ?> props) {
                    if (props.containsKey("dramaId") && context.dramaId() != null && b.contextMode() != ContextMode.UNBOUND_PROJECT
                            && !Boolean.TRUE.equals(args.get("publicOnly"))) args.put("dramaId", context.dramaId().toString());
                    if (props.containsKey("requestId")) args.put("requestId", "agent-step-" + context.stepId());
                }
                if (!validator.validate(h.schema(), args).valid()) throw new BizException(400, "共享能力参数与项目上下文不匹配");
                boolean write = !"CONFIG_TEST".equals(context.snapshot().runType());
                if (h.business().writePermissionRequired() && !write) throw new BizException(403, "测试模式禁止读取写操作结果");
                McpPrincipal caller = new McpPrincipal(write, false, write && context.dramaId() == null,
                        context.dramaId() == null ? Set.of() : Set.of(context.dramaId()));
                return ToolResult.success(handlers.invokeBusiness(h, caller, args));
            }
        };
    }
    private Release releaseForVersion(Binding b) {
        return local.values().stream().filter(r -> r.bindings().stream().anyMatch(v -> v.versionId().equals(b.versionId()))).findFirst()
                .orElseThrow(() -> new BizException(409, "能力版本未加载"));
    }
    @Scheduled(fixedDelay = 5000) public void refresh() {
        if (!initialized) return;
        try { current(); } catch (RuntimeException e) { log.warn("能力目录刷新失败: {}", e.getMessage()); }
        var client = redis.getIfAvailable(); if (client == null) return;
        for (var event : mapper.pendingEvents()) try {
            var release = release(event.getActiveReleaseId().toString());
            client.opsForValue().set(cacheKey(release.id()), mapper.release(Long.valueOf(release.id())).getPayloadJson(), Duration.ofHours(24));
            client.convertAndSend(EVENTS, json.write(Map.of("revision", event.getRevision(), "releaseId", release.id())));
            mapper.sent(event.getId());
        } catch (RuntimeException e) { mapper.retry(event.getId()); break; }
        try { var state = mapper.state(); client.opsForValue().set("freyja:capability:v1:instance:" + instanceId,
                json.write(Map.of("releaseId", state.getActiveReleaseId().toString(), "revision", state.getRevision())), Duration.ofSeconds(30)); }
        catch (RuntimeException ignored) { }
    }
    public String rules() { return current().rules(); }
    private static Binding enabled(Binding b, boolean enabled) { return new Binding(b.id(), b.capabilityId(), b.versionId(), b.handlerKey(), b.consumer(), b.consumerCode(), b.name(), b.description(), b.schema(), enabled, b.required(), b.contextMode(), b.projectRequired(), b.effect(), b.recoveryMode()); }
    private static String cacheKey(String id) { return "freyja:capability:v1:release:" + id; }
    private void requireText(String instructions, String rules) { if (instructions == null || rules == null || instructions.length() > 100000 || rules.length() > 100000) throw new BizException(400, "公共规则文本无效或过长"); }
    private Release read(String text) {
        try {
            Release source = storage.readValue(text, Release.class);
            List<Binding> bindings = source.bindings().stream().map(b -> new Binding(b.id(), b.capabilityId(), b.versionId(), b.handlerKey(),
                    b.consumer(), b.consumerCode(), b.name(), b.description(), immutableMap(b.schema()), b.enabled(), b.required(), b.contextMode(),
                    b.projectRequired(), b.effect(), b.recoveryMode())).toList();
            return new Release(source.id(), source.revision(), bindings, source.instructions(), source.rules());
        } catch (Exception e) { throw new BizException(409, "能力快照无效"); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> immutableMap(Map<String, Object> value) { return (Map<String, Object>) immutable(value); }
    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>(); source.forEach((k, v) -> result.put(k.toString(), immutable(v)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> source) return Collections.unmodifiableList(source.stream().map(CapabilityCatalogService::immutable).toList());
        return value;
    }
    private static String hash(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
