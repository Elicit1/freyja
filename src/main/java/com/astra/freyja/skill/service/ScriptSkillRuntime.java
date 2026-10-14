package com.astra.freyja.skill.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dto.script.GlobalStoryContext;
import com.astra.freyja.dto.script.ScriptDecomposeRequestDTO;
import com.astra.freyja.dto.script.ScriptSkillEvent;
import com.astra.freyja.dto.script.ScriptSkillStagePolicy;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.skill.model.LoadedSkill;
import com.astra.freyja.skill.model.SkillCatalogItem;
import com.astra.freyja.skill.model.SkillPromptContext;
import com.astra.freyja.skill.tool.LoadSkillResponse;
import com.astra.freyja.skill.tool.LoadSkillToolFactory;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import com.astra.freyja.skill.tool.ReadSkillFileToolFactory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClientBuilder;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Task-scoped Skill catalog, stage-scoped prompts and invocation-scoped tools. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptSkillRuntime {
    private final SkillCatalogService catalogService;
    private final SkillContentService contentService;
    private final SkillPromptContextService promptContextService;
    private final LoadSkillToolFactory toolFactory;
    private final ReadSkillFileToolFactory readSkillFileToolFactory;
    private final AiModelMapper modelMapper;
    private final tools.jackson.databind.ObjectMapper taskObjectMapper;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /** Called at every public task entry, before persisting the input or invoking AI. */
    public void prepareRequest(ScriptDecomposeRequestDTO request) {
        request.setSkillCatalogSnapshot(null); // never trust a client-supplied snapshot
        if (request.getSkillPolicy() == null || !request.getSkillPolicy().isActive()) return;

        List<SkillCatalogItem> snapshot = catalogService.getEnabledCatalog().stream()
                .map(item -> SkillCatalogItem.builder()
                        .name(item.getName()).displayName(item.getDisplayName())
                        .description(item.getDescription()).version(item.getVersion())
                        .versionId(item.getVersionId()).build())
                .toList();
        Map<String, Long> versions = versions(snapshot);
        validateStage(request.getSkillPolicy().getPlanner(), versions, request, "Planner");
        validateStage(request.getSkillPolicy().getWorker(), versions, request, "Worker");
        request.setSkillCatalogSnapshot(snapshot);
    }

    /** Human retries use the original task snapshot; an override cannot introduce a later version. */
    public void validateRetry(ScriptDecomposeRequestDTO request, ScriptSkillStagePolicy stage) {
        if (stage == null || !stage.isActive()) return;
        validateStage(stage, versions(request.getSkillCatalogSnapshot()), request, "Worker");
    }

    private void validateStage(ScriptSkillStagePolicy stage, Map<String, Long> versions,
                               ScriptDecomposeRequestDTO request, String label) {
        if (stage == null) return;
        Set<String> required = new LinkedHashSet<>();
        if (stage.getRequiredSkillNames() != null) {
            for (String name : stage.getRequiredSkillNames()) {
                if (StringUtils.isBlank(name)) continue;
                String clean = name.trim().toLowerCase();
                Long versionId = versions.get(clean);
                if (versionId == null) throw new BizException(400, label + " 必用 Skill 不存在或未启用: " + clean);
                LoadedSkill loaded = contentService.loadSkillVersion(versionId);
                if (loaded == null || StringUtils.isBlank(loaded.getContent())) {
                    throw new BizException(400, label + " 必用 Skill 正文为空: " + clean);
                }
                required.add(clean);
            }
        }
        stage.setRequiredSkillNames(List.copyOf(required));
        if (stage.isAllowDynamicLoad()) validateToolCapability(request, label);
    }

    private void validateToolCapability(ScriptDecomposeRequestDTO request, String label) {
        if (request.getProviderId() == null || StringUtils.isBlank(request.getModelCode())) return;
        AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, request.getProviderId())
                .eq(AiModel::getModelCode, request.getModelCode()));
        if (model == null || !Integer.valueOf(1).equals(model.getStatus())
                || !"CHAT".equalsIgnoreCase(model.getModelType())) {
            throw new BizException(400, label + " 按需加载需要启用的 CHAT 模型");
        }
        if (StringUtils.isBlank(model.getParamsJson())) return;
        try {
            JsonNode capabilities = jsonMapper.readTree(model.getParamsJson()).path("capabilities");
            if (capabilities.has("toolCalling") && !capabilities.path("toolCalling").asBoolean(true)) {
                throw new BizException(400, label + " 所选模型声明不支持 Tool Calling");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.debug("模型 capabilities 配置无法解析: {}", e.getMessage());
        }
    }

    public Invocation begin(ScriptDecomposeRequestDTO request, ScriptSkillStagePolicy stage,
                            GlobalStoryContext context, String systemPrompt, String stageName,
                            String segmentId, int attempt, BiConsumer<String, String> channel) {
        Long taskId = context == null ? null : context.getTaskId();
        if (stage == null || !stage.isActive()) {
            log.info("[ScriptSkill] taskId={} stage={} segment={} attempt={} disabled; no Skill will be loaded",
                    taskId, stageName, segmentId, attempt);
            return null;
        }
        List<SkillCatalogItem> catalog = request.getSkillCatalogSnapshot();
        Map<String, Long> versions = versions(catalog);
        log.info("[ScriptSkill] taskId={} stage={} segment={} attempt={} enabled, required={}, dynamicLoad={}, catalogSize={}",
                taskId, stageName, segmentId, attempt, stage.getRequiredSkillNames(), stage.isAllowDynamicLoad(), versions.size());
        String requestId = context != null && context.getTaskId() != null
                ? context.getTaskId() + ":" + stageName + ":" + StringUtils.defaultString(segmentId)
                : stageName + ":" + StringUtils.defaultString(segmentId);
        LoadSkillToolSession session = new LoadSkillToolSession(versions);
        for (String name : stage.getRequiredSkillNames()) {
            Long versionId = versions.get(name);
            if (versionId == null) throw new BizException(400, "必用 Skill 不在任务版本快照中: " + name);
            long loadStart = System.currentTimeMillis();
            log.info("[ScriptSkill] taskId={} stage={} segment={} attempt={} required load started, name={}, versionId={}",
                    taskId, stageName, segmentId, attempt, name, versionId);
            try {
                LoadedSkill skill = contentService.loadSkillVersion(versionId);
                session.preload(name, skill);
                emit(context, channel, new ScriptSkillEvent(stageName, segmentId, attempt, name,
                        versionId, skill.getVersion(), skill.getContentHash(), "REQUIRED", "LOADED"));
                log.info("[ScriptSkill] taskId={} stage={} segment={} attempt={} required load completed, name={}, versionId={}, version={}, hash={}, durationMs={}",
                        taskId, stageName, segmentId, attempt, name, versionId, skill.getVersion(),
                        skill.getContentHash(), System.currentTimeMillis() - loadStart);
            } catch (Exception e) {
                log.error("[ScriptSkill] taskId={} stage={} segment={} attempt={} required load failed, name={}, versionId={}, durationMs={}",
                        taskId, stageName, segmentId, attempt, name, versionId, System.currentTimeMillis() - loadStart, e);
                throw e;
            }
        }
        SkillPromptContext requiredContext = promptContextService.loadSelected(
                "script-" + stageName.toLowerCase(), requestId, stage.getRequiredSkillNames(), versions);
        String prompt = promptContextService.appendToSystemPrompt(systemPrompt, requiredContext);
        ToolCallback tool = null;
        if (stage.isAllowDynamicLoad()) {
            prompt += "\n\n" + catalogService.formatCatalogForPrompt(catalog)
                    + "\n如需技能知识，请调用 load_skill(name)。仅在最终回答输出业务 JSON，不输出工具结果或 Skill 正文。";
            tool = toolFactory.createTool(session, status -> log.info(
                    "[ScriptSkill] taskId={} stage={} segment={} attempt={} tool activity: {}",
                    taskId, stageName, segmentId, attempt, status.trim()), response -> onToolResult(
                    response, session, context, channel, stageName, segmentId, attempt));
            log.info("[ScriptSkill] taskId={} stage={} segment={} attempt={} load_skill tool registered, catalogSize={}",
                    taskId, stageName, segmentId, attempt, versions.size());
        }
        ToolCallback readFileTool = stage.isAllowDynamicLoad()
                ? readSkillFileToolFactory.createTool(session, message -> {
                    log.info("[ScriptSkill] taskId={} stage={} segment={} attempt={} {}",
                            taskId, stageName, segmentId, attempt, message.trim());
                    if (channel != null) channel.accept("PLANNER".equalsIgnoreCase(stageName) ? "PLANNER" : segmentId,
                            "📄 " + message);
                }, warnMessage -> {
                    log.warn("[ScriptSkill] taskId={} stage={} segment={} attempt={} {}",
                            taskId, stageName, segmentId, attempt, warnMessage);
                    if (channel != null) {
                        String warnText = "⚠️ [AI Skill 警告] " + warnMessage + "\n";
                        channel.accept("PLANNER".equalsIgnoreCase(stageName) ? "PLANNER" : segmentId, warnText);
                    }
                }) : null;
        return new Invocation(prompt, tool, readFileTool, session, taskId, stageName, segmentId, attempt, channel);
    }

    public String streamWithTool(ChatModel model, Invocation invocation,
                                 Consumer<String> finalTextChunk) {
        return streamWithTool(model, invocation, null, finalTextChunk);
    }

    public String streamWithTool(ChatModel model, Invocation invocation, String userPrompt,
                                 Consumer<String> finalTextChunk) {
        ChatOptions options = model.getOptions();
        if (options == null) throw new BizException("所选模型缺少 ChatOptions，无法执行 Skill Tool Calling");

        ToolCallbackResolver fallbackResolver = toolName -> createUnknownToolFallback(toolName, invocation);

        DefaultToolCallingManager toolCallingManager = DefaultToolCallingManager.builder()
                .observationRegistry(ObservationRegistry.NOOP)
                .resolutionFallbackEnabled(true)
                .toolCallbackResolver(fallbackResolver)
                .build();

        ToolCallingAdvisor.Builder<?> advisorBuilder = ToolCallingAdvisor.builder()
                .toolCallingManager(toolCallingManager);

        ChatClient.Builder builder = new DefaultChatClientBuilder(
                model,
                ObservationRegistry.NOOP,
                null,
                null,
                advisorBuilder
        );
        builder.defaultOptions(options.mutate());
        StringBuilder answer = new StringBuilder();
        var requestSpec = builder.build().prompt().system(invocation.systemPrompt());
        if (userPrompt != null) requestSpec = requestSpec.user(userPrompt);
        if (invocation.hasTools()) {
            requestSpec = requestSpec.tools((Object[]) invocation.tools());
        }
        try (var stream = requestSpec.stream().content().toStream()) {
            stream.forEach(chunk -> {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("AI Skill 调用已取消");
                }
                if (StringUtils.isNotEmpty(chunk)) {
                    answer.append(chunk);
                    if (finalTextChunk != null) finalTextChunk.accept(chunk);
                }
            });
        }
        log.info("[ScriptSkill] taskId={} stage={} segment={} attempt={} model completed, loaded={}, loadedReferenceFiles={}, toolCalls={}",
                invocation.taskId(), invocation.stage(), invocation.segmentId(), invocation.attempt(),
                invocation.session() == null ? null : invocation.session().getLoadedSkillNames(),
                invocation.session() == null ? null : invocation.session().getLoadedReferenceFiles(),
                invocation.session() == null ? null : invocation.session().getInvocationHistory());
        return answer.toString();
    }

    private ToolCallback createUnknownToolFallback(String toolName, Invocation invocation) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(toolName)
                        .description("未识别工具: " + toolName)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String input) {
                Long taskId = invocation != null ? invocation.taskId() : null;
                String stage = invocation != null ? invocation.stage() : null;
                String segmentId = invocation != null ? invocation.segmentId() : null;
                int attempt = invocation != null ? invocation.attempt() : 1;

                log.warn("[ScriptSkill] taskId={} stage={} segment={} attempt={} 模型调用了未注册工具: name={}, input={}",
                        taskId, stage, segmentId, attempt, toolName, input);
                if (invocation != null && invocation.channel() != null) {
                    String warnText = "⚠️ [AI Skill 警告] 模型调用了未注册工具 [" + toolName + "]，已跳过并引导模型继续生成...\n";
                    invocation.channel().accept("PLANNER".equalsIgnoreCase(stage) ? "PLANNER" : segmentId, warnText);
                }
                return "工具 [" + toolName + "] 不存在或不支持。请勿尝试调用此工具，直接基于已有上下文输出最终业务 JSON 结果。";
            }
        };
    }

    private void onToolResult(LoadSkillResponse response, LoadSkillToolSession session,
                              GlobalStoryContext context, BiConsumer<String, String> channel,
                              String stage, String segmentId, int attempt) {
        if (response == null || StringUtils.isBlank(response.getName())) return;
        String name = response.getName().trim().toLowerCase();
        log.info("[ScriptSkill] taskId={} stage={} segment={} attempt={} tool result, invocationNo={}, name={}, versionId={}, version={}, hash={}, status={}, message={}",
                context == null ? null : context.getTaskId(), stage, segmentId, attempt, session.getInvocationCount(),
                name, session.resolveVersion(name).orElse(null), response.getVersion(), response.getContentHash(),
                response.getStatus(), "ERROR".equals(response.getStatus()) ? response.getMessage() : null);
        emit(context, channel, new ScriptSkillEvent(stage, segmentId, attempt, name,
                session.resolveVersion(name).orElse(null), response.getVersion(),
                response.getContentHash(), "TOOL", response.getStatus()));
        if ("ERROR".equalsIgnoreCase(response.getStatus()) && channel != null) {
            String warnMsg = "⚠️ [AI Skill 警告] 阶段 " + stage + " 调用技能 [" + name + "] 失败: "
                    + StringUtils.defaultIfBlank(response.getMessage(), "未知错误") + "\n";
            channel.accept("PLANNER".equalsIgnoreCase(stage) ? "PLANNER" : segmentId, warnMsg);
        }
    }

    private void emit(GlobalStoryContext context, BiConsumer<String, String> channel, ScriptSkillEvent event) {
        if (context != null) context.getSkillEvents().add(event);
        if (channel != null) channel.accept("SKILL_EVENT", taskObjectMapper.writeValueAsString(event));
    }

    private Map<String, Long> versions(List<SkillCatalogItem> catalog) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (catalog != null) for (SkillCatalogItem item : catalog) {
            if (item != null && StringUtils.isNotBlank(item.getName()) && item.getVersionId() != null) {
                result.put(item.getName().trim().toLowerCase(), item.getVersionId());
            }
        }
        return result;
    }

    public record Invocation(String systemPrompt, ToolCallback tool, ToolCallback readFileTool, LoadSkillToolSession session,
                             Long taskId, String stage, String segmentId, int attempt, BiConsumer<String, String> channel) {
        public Invocation(String systemPrompt, ToolCallback tool, ToolCallback readFileTool, LoadSkillToolSession session,
                          Long taskId, String stage, String segmentId, int attempt) {
            this(systemPrompt, tool, readFileTool, session, taskId, stage, segmentId, attempt, null);
        }

        public Invocation(String systemPrompt, ToolCallback tool, LoadSkillToolSession session,
                          Long taskId, String stage, String segmentId, int attempt) {
            this(systemPrompt, tool, null, session, taskId, stage, segmentId, attempt, null);
        }

        public Invocation withSystemPrompt(String newSystemPrompt) {
            return new Invocation(newSystemPrompt, tool, readFileTool, session, taskId, stage, segmentId, attempt, channel);
        }

        public boolean hasTools() {
            return tool != null || readFileTool != null;
        }

        public ToolCallback[] tools() {
            if (tool == null) return readFileTool == null ? new ToolCallback[0] : new ToolCallback[]{readFileTool};
            return readFileTool == null ? new ToolCallback[]{tool} : new ToolCallback[]{tool, readFileTool};
        }
    }
}
