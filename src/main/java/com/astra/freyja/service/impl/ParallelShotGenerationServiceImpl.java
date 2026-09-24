package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.script.*;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.entity.enums.AiTaskType;
import com.astra.freyja.service.AIOutputValidationService;
import com.astra.freyja.service.AiModelFactory;
import com.astra.freyja.service.AiTaskService;
import com.astra.freyja.service.ParallelShotGenerationService;
import com.astra.freyja.service.SysConfigService;
import com.astra.freyja.skill.service.ScriptSkillRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Worker 并行分镜生成调度服务实现 (ParallelShotGenerationServiceImpl)。
 * 严格遵循「Worker 仅处理自身 Segment、多线程真正并行、Semaphore 控制并发、失败局部重试」架构原则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParallelShotGenerationServiceImpl implements ParallelShotGenerationService {

    private final AiModelFactory aiModelFactory;
    private final SysConfigService sysConfigService;
    private final AIOutputValidationService validationService;
    private final AiTaskService aiTaskService;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private ScriptSkillRuntime scriptSkillRuntime;

    private static final String DEFAULT_WORKER_SYSTEM_PROMPT = """
        你是剧情分镜编剧，负责将当前 Segment 原文整理为完整的剧情分镜。

        ### 固定职责与强制约束
        1. Worker 只负责剧情拆镜、scriptContent、人物动作与表演、对白与声音、人物及道具连续性、资产引用和合理时长。
        2. 多个 Shot 必须在整个 Segment 范围内保持剧情、时空、人物动作及道具状态连续，不得无依据改变已确定的事实。
        3. scriptContent 和 action 必须保持艺术风格中立。剥离原文中的艺术风格与媒介修辞，仅保留客观、可观察的剧情及环境信息。
        4. 不得自行设计景别、机位、摄影机角度、运镜及构图，不得生成生图或视频 Prompt。
        5. 原文明确指定的摄影要求必须在 scriptContent 中保留，并标识为“用户原始要求”；只记录原始要求，不得自行扩展。
        6. scenes、shots.sceneId 和 propIds 必须严格引用当前任务实际绑定的资产 ID，不得编造。
        7. propIds 表示道具资产引用，不代表具体实例数量。人物持有的道具数量、归属、持有方式及状态变化必须符合原文和前序镜头事实。
        8. 按照当前 JSON Schema 输出完整分镜，不得自行增加、删除或修改字段。camera 字段遵循 Schema 定义的未指定状态，不填写 STATIC 或其他具体摄影参数。
        9. 当系统提供适用的剧情分镜或连续性 Skill 时，按照现有 Skill 加载协议执行，并遵循已加载的专业规则。详细的分镜方法、动作编排和连续性处理由 Skill 提供。

        只输出符合当前 JSON Schema 的最终 JSON，不附加解释或 Markdown。
        """;

    @Override
    public List<SegmentShotResult> generateShotsInParallel(List<StorySegment> segments,
                                                          GlobalStoryContext globalContext,
                                                          ScriptDecomposeRequestDTO request,
                                                          Consumer<String> stepLogger,
                                                          Consumer<SegmentShotResult> segmentProgressCallback) {
        return generateShotsInParallel(segments, globalContext, request, null, stepLogger, segmentProgressCallback);
    }

    @Override
    public List<SegmentShotResult> generateShotsInParallel(List<StorySegment> segments,
                                                           GlobalStoryContext globalContext,
                                                           ScriptDecomposeRequestDTO request,
                                                           BiConsumer<String, String> channelChunkConsumer,
                                                           Consumer<String> stepLogger,
                                                           Consumer<SegmentShotResult> segmentProgressCallback) {
        if (segments == null || segments.isEmpty()) {
            return Collections.emptyList();
        }

        int maxConcurrency = getIntConfig("ai.shotWorker.maxConcurrency", 3);
        int timeoutSeconds = getIntConfig("ai.shotWorker.timeoutSeconds", 180);
        int maxRetries = getIntConfig("ai.shotWorker.maxRetries", 2);

        if (stepLogger != null) {
            stepLogger.accept(String.format("🚀 [Worker Pool] 启动分镜并行生成流水线: 共 %d 个 Segment, 最大并发限制 maxConcurrency=%d...",
                    segments.size(), maxConcurrency));
        }

        Semaphore semaphore = new Semaphore(Math.max(1, maxConcurrency));
        List<CompletableFuture<SegmentShotResult>> futures = new ArrayList<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        for (int i = 0; i < segments.size(); i++) {
            StorySegment currentSeg = segments.get(i);
            String prevContext = (i > 0) ? buildLightweightPreviousContext(segments.get(i - 1)) : null;
            String nextContext = (i < segments.size() - 1) ? buildLightweightNextContext(segments.get(i + 1)) : null;

            CompletableFuture<SegmentShotResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                    return processSingleSegmentWithRetry(
                            currentSeg, globalContext, prevContext, nextContext,
                            request, timeoutSeconds, maxRetries, channelChunkConsumer, stepLogger, segmentProgressCallback
                    );
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BizException("Worker 线程中断: " + e.getMessage());
                } finally {
                    semaphore.release();
                }
            }, executor);

            futures.add(future);
        }

        List<SegmentShotResult> results = new ArrayList<>();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            for (CompletableFuture<SegmentShotResult> f : futures) {
                results.add(f.get());
            }
        } catch (Exception e) {
            log.error("[ParallelShotGeneration] taskId={} parallel execution failed",
                    globalContext == null ? null : globalContext.getTaskId(), e);
            throw new BizException("并行分镜解析失败: " + e.getMessage());
        } finally {
            executor.shutdown();
        }

        results.sort(Comparator.comparingInt(r -> r.getSequence() != null ? r.getSequence() : 0));
        return results;
    }

    @Override
    public SegmentShotResult retrySingleSegment(StorySegment segment,
                                                GlobalStoryContext globalContext,
                                                String previousContext,
                                                String nextContext,
                                                ScriptDecomposeRequestDTO request,
                                                Consumer<String> stepLogger) {
        return retrySingleSegment(segment, globalContext, previousContext, nextContext, request, null, stepLogger);
    }

    @Override
    public SegmentShotResult retrySingleSegment(StorySegment segment,
                                                GlobalStoryContext globalContext,
                                                String previousContext,
                                                String nextContext,
                                                ScriptDecomposeRequestDTO request,
                                                String customInstructions,
                                                Consumer<String> stepLogger) {
        int timeoutSeconds = getIntConfig("ai.shotWorker.timeoutSeconds", 180);
        return processSingleSegmentWithRetry(
                segment, globalContext, previousContext, nextContext,
                request, customInstructions, timeoutSeconds, 1, null, stepLogger, null
        );
    }

    private SegmentShotResult processSingleSegmentWithRetry(StorySegment segment,
                                                           GlobalStoryContext globalContext,
                                                           String previousContext,
                                                           String nextContext,
                                                           ScriptDecomposeRequestDTO request,
                                                           int timeoutSeconds,
                                                           int maxRetries,
                                                           BiConsumer<String, String> channelChunkConsumer,
                                                           Consumer<String> stepLogger,
                                                           Consumer<SegmentShotResult> segmentProgressCallback) {
        return processSingleSegmentWithRetry(segment, globalContext, previousContext, nextContext, request, null, timeoutSeconds, maxRetries, channelChunkConsumer, stepLogger, segmentProgressCallback);
    }

    private SegmentShotResult processSingleSegmentWithRetry(StorySegment segment,
                                                           GlobalStoryContext globalContext,
                                                           String previousContext,
                                                           String nextContext,
                                                           ScriptDecomposeRequestDTO request,
                                                           String customInstructions,
                                                           int timeoutSeconds,
                                                           int maxRetries,
                                                           BiConsumer<String, String> channelChunkConsumer,
                                                           Consumer<String> stepLogger,
                                                           Consumer<SegmentShotResult> segmentProgressCallback) {
        String segId = segment.getId();
        int seq = segment.getSequence() != null ? segment.getSequence() : 1;

        SegmentShotResult shotResultVO = SegmentShotResult.builder()
                .segmentId(segId)
                .sequence(seq)
                .status("RUNNING")
                .retryCount(0)
                .build();

        if (segmentProgressCallback != null) {
            segmentProgressCallback.accept(shotResultVO);
        }

        if (stepLogger != null) {
            stepLogger.accept(String.format("  ↳ ⚙️ [Worker %s] 启动分镜解析 (第 %d 段: 《%s》, %d 字)...",
                    segId, seq, segment.getTitle(), segment.getRawText() != null ? segment.getRawText().length() : 0));
        }

        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            log.info("[Worker] taskId={} segment={} attempt={} started, model={}",
                    globalContext == null ? null : globalContext.getTaskId(), segId, attempt, request.getModelCode());
            if (attempt > 0) {
                if (channelChunkConsumer != null) channelChunkConsumer.accept("CHANNEL_RESET", segId);
                if (stepLogger != null) {
                    stepLogger.accept(String.format("  ↳ ⚠️ [Worker %s] 第 %d 次局部重试...", segId, attempt));
                }
                shotResultVO.setStatus("RETRYING");
                shotResultVO.setRetryCount(attempt);
                if (segmentProgressCallback != null) {
                    segmentProgressCallback.accept(shotResultVO);
                }
            }

            long startMs = System.currentTimeMillis();
            try {
                WorkerShotResult workerOutput = callWorkerAi(segment, globalContext, previousContext, nextContext, request, customInstructions, attempt, channelChunkConsumer, stepLogger);
                long durationMs = System.currentTimeMillis() - startMs;

                int shotCount = (workerOutput.getScenes() != null)
                        ? workerOutput.getScenes().stream().mapToInt(sc -> sc.getShots() != null ? sc.getShots().size() : 0).sum()
                        : 0;

                shotResultVO.setStatus("SUCCESS");
                shotResultVO.setShotResult(workerOutput);
                shotResultVO.setDurationMs(durationMs);
                shotResultVO.setErrorMessage(null);

                log.info("[Worker] taskId={} segment={} attempt={} completed, durationMs={}, shots={}",
                        globalContext == null ? null : globalContext.getTaskId(), segId, attempt, durationMs, shotCount);
                if (stepLogger != null) {
                    stepLogger.accept(String.format("  ↳ ✓ [Worker %s] 完成分镜生成 (耗时: %.2fs, 产出 %d 镜)",
                            segId, durationMs / 1000.0, shotCount));
                }

                // 向该 Worker 专属视口推送结构化分镜摘要与台词总览 (直流分发，无正则/前缀)
                StringBuilder previewSb = new StringBuilder();
                previewSb.append(String.format("\n\n========================================\n✨ 【%s】分镜生成完成 (共 %d 镜，耗时 %.2fs)\n========================================\n",
                        segId, shotCount, durationMs / 1000.0));
                if (workerOutput.getScenes() != null) {
                    for (WorkerShotResult.WorkerSceneVO sc : workerOutput.getScenes()) {
                        previewSb.append(String.format("🎬 场次: %s\n", StringUtils.defaultIfBlank(sc.getName(), "未命名场景")));
                        if (sc.getShots() != null) {
                            for (WorkerShotResult.WorkerShotVO sh : sc.getShots()) {
                                previewSb.append(String.format("  - 镜 %s [%.1fs]: %s",
                                        sh.getSequence() != null ? sh.getSequence().toString() : "",
                                        sh.getDuration() != null ? sh.getDuration() : 5.0,
                                        StringUtils.defaultIfBlank(sh.getAction(), "")));
                                if (StringUtils.isNotBlank(sh.getDialogue())) {
                                    previewSb.append(String.format(" 【对白 (%s): %s】",
                                            StringUtils.defaultIfBlank(sh.getDialogueSpeaker(), "角色"),
                                            sh.getDialogue()));
                                }
                                previewSb.append("\n");
                            }
                        }
                    }
                }
                if (channelChunkConsumer != null) {
                    channelChunkConsumer.accept(segId, previewSb.toString());
                }

                if (segmentProgressCallback != null) {
                    segmentProgressCallback.accept(shotResultVO);
                }
                return shotResultVO;
            } catch (Exception e) {
                lastException = e;
                log.warn("[Worker] taskId={} segment={} attempt={} failed",
                        globalContext == null ? null : globalContext.getTaskId(), segId, attempt, e);
            }
        }

        shotResultVO.setStatus("FAILED");
        shotResultVO.setErrorMessage(lastException != null ? lastException.getMessage() : "生成分镜失败");
        if (stepLogger != null) {
            stepLogger.accept(String.format("  ↳ ❌ [Worker %s] 生成失败: %s", segId, shotResultVO.getErrorMessage()));
        }

        if (segmentProgressCallback != null) {
            segmentProgressCallback.accept(shotResultVO);
        }
        return shotResultVO;
    }

    private WorkerShotResult callWorkerAi(StorySegment segment,
                                          GlobalStoryContext globalContext,
                                          String previousContext,
                                          String nextContext,
                                          ScriptDecomposeRequestDTO request,
                                          String customInstructions,
                                          int attempt,
                                          BiConsumer<String, String> channelChunkConsumer,
                                          Consumer<String> stepLogger) {
        long startMs = System.currentTimeMillis();
        ChatModel chatModel = aiModelFactory.getChatModel(request.getProviderId(), request.getModelCode());
        BeanOutputConverter<WorkerShotResult> converter = new BeanOutputConverter<>(WorkerShotResult.class);

        String pacingDesc = switch (StringUtils.defaultIfBlank(request.getPacingPreset(), "STANDARD")) {
            case "CINEMATIC_LONG" -> "倾向保留完整的连续动作和空间关系，减少无必要的切镜；具体时长按镜头可行性决定";
            case "FAST_PACED" -> "节奏紧凑，优先保留关键动作与反应；不得为追求快节奏截断动作或对白";
            default -> "标准工业短剧节奏，单镜以 5~8 秒为参考；按原文叙事节奏拆解，保持动作完整，避免无意义的碎镜头";
        };

        StringBuilder taskInput = new StringBuilder();
        if (StringUtils.isNotBlank(customInstructions)) {
            taskInput.append("【当前任务补充输入】：\n")
                    .append(customInstructions).append("\n\n");
        }
        taskInput.append("【当前 Segment 的场景资产事实】：\n")
                .append(buildBoundSceneAssetFacts(segment, globalContext)).append("\n");
        taskInput.append("【当前 Segment 的人物资产事实】：\n")
                .append(buildBoundCharacterAssetFacts(segment, globalContext)).append("\n");
        taskInput.append("【当前 Segment 的道具资产事实】：\n")
                .append(buildBoundPropAssetFacts(segment, globalContext)).append("\n");
        if (StringUtils.isNotBlank(previousContext)) {
            taskInput.append("【前一分段剧情上下文】：\n")
                    .append(previousContext).append("\n\n");
        }
        if (StringUtils.isNotBlank(nextContext)) {
            taskInput.append("【后续剧情上下文】：\n")
                    .append(nextContext).append("\n\n");
        }
        taskInput.append("【当前 Segment 动态事实】：\n")
                .append("分段ID: ").append(StringUtils.defaultString(segment.getId())).append("\n")
                .append("分段标题: ").append(StringUtils.defaultString(segment.getTitle())).append("\n")
                .append("分段概要: ").append(StringUtils.defaultString(segment.getSummary())).append("\n")
                .append("绑定主场景值: ").append(StringUtils.defaultIfBlank(segment.getSceneId(), "未提供")).append("\n")
                .append("叙事目的: ").append(StringUtils.defaultString(segment.getNarrativePurpose())).append("\n")
                .append("涉及角色: ").append(segment.getCharacterIds() != null && !segment.getCharacterIds().isEmpty()
                        ? String.join(", ", segment.getCharacterIds()) : "未提供").append("\n")
                .append("涉及道具引用: ").append(segment.getPropIds() != null && !segment.getPropIds().isEmpty()
                        ? String.join(", ", segment.getPropIds())
                        : segment.getImportantPropIds() != null && !segment.getImportantPropIds().isEmpty()
                                ? String.join(", ", segment.getImportantPropIds()) : "未提供").append("\n")
                .append("剪辑节奏偏好: ").append(pacingDesc).append("\n")
                .append("Segment 原文: \n")
                .append(StringUtils.defaultString(segment.getRawText())).append("\n\n")
                .append("【当前 JSON Schema】：\n")
                .append(converter.getFormat());

        String baseSystemPrompt = sysConfigService.getConfigValue("ai.prompt.shot_worker_system", DEFAULT_WORKER_SYSTEM_PROMPT);
        String systemPrompt = baseSystemPrompt + "\n\n### 本次 Worker 任务输入（动态事实）\n" + taskInput;
        ScriptSkillRuntime.Invocation skillInvocation = scriptSkillRuntime == null ? null
                : scriptSkillRuntime.begin(request,
                request.getSkillPolicy() == null ? null : request.getSkillPolicy().getWorker(),
                globalContext, systemPrompt, "WORKER", segment.getId(), attempt, channelChunkConsumer);
        if (skillInvocation != null) {
            systemPrompt = skillInvocation.systemPrompt();
            skillInvocation = new ScriptSkillRuntime.Invocation(
                    systemPrompt,
                    skillInvocation.tool(), skillInvocation.session(), skillInvocation.taskId(),
                    skillInvocation.stage(), skillInvocation.segmentId(), skillInvocation.attempt());
        }

        Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt)));

        AiTask aiTask = aiTaskService.createTask(
                request.getDramaId(),
                globalContext != null ? globalContext.getEpisodeId() : null,
                AiTaskType.SHOT_DECOMPOSE,
                "WORKER_" + segment.getId() + "_" + System.currentTimeMillis() % 10000,
                globalContext != null ? globalContext.getTaskId() : null,
                "Segment: " + segment.getId() + ", TextLen: " + (segment.getRawText() != null ? segment.getRawText().length() : 0),
                request.getModelCode(),
                null
        );
        aiTaskService.markRunning(aiTask.getId());

        StringBuilder fullOutput = new StringBuilder();
        try {
            if (skillInvocation != null && skillInvocation.tool() != null) {
                fullOutput.append(scriptSkillRuntime.streamWithTool(chatModel, skillInvocation, token -> {
                            if (channelChunkConsumer != null) channelChunkConsumer.accept(segment.getId(), token);
                        }));
            } else {
            try {
                chatModel.stream(prompt).toStream().forEach(chunk -> {
                    if (chunk != null && chunk.getResult() != null && chunk.getResult().getOutput() != null) {
                        String token = chunk.getResult().getOutput().getText();
                        if (StringUtils.isNotEmpty(token)) {
                            fullOutput.append(token);
                            if (channelChunkConsumer != null) {
                                channelChunkConsumer.accept(segment.getId(), token);
                            }
                        }
                    }
                });
            } catch (Exception streamEx) {
                if (!fullOutput.isEmpty()) throw streamEx;
                log.debug("[Worker] segment={} 流式调用降级为同步调用: {}", segment.getId(), streamEx.getMessage());
                ChatResponse response = chatModel.call(prompt);
                if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                    fullOutput.setLength(0);
                    String respText = response.getResult().getOutput().getText();
                    fullOutput.append(respText);
                    if (channelChunkConsumer != null && StringUtils.isNotEmpty(respText)) {
                        channelChunkConsumer.accept(segment.getId(), respText);
                    }
                }
            }
            }

            long callDuration = System.currentTimeMillis() - startMs;
            if (fullOutput.isEmpty()) {
                aiTaskService.markFailed(aiTask.getId(), "Worker AI 未返回有效响应");
                throw new BizException("Worker AI 未返回有效响应");
            }

            String rawOutput = fullOutput.toString();
            WorkerShotResult result = validationService.parseAndValidate(rawOutput, WorkerShotResult.class);
            if (result == null) {
                aiTaskService.markFailed(aiTask.getId(), "Worker JSON 解析失败");
                throw new BizException("Worker AI JSON 解析失败");
            }

            enforceAutoCameraPlaceholders(result);

            // 确保 segmentId 赋值正确
            if (StringUtils.isBlank(result.getSegmentId())) {
                result.setSegmentId(segment.getId());
            }

            aiTaskService.markSuccess(aiTask.getId(), globalContext != null && globalContext.getTaskId() != null
                    ? null : objectMapper.writeValueAsString(result), (int) callDuration);
            return result;
        } catch (Exception e) {
            aiTaskService.markFailed(aiTask.getId(), e.getMessage());
            throw e;
        }
    }

    private String buildBoundSceneAssetFacts(StorySegment segment, GlobalStoryContext globalContext) {
        String binding = StringUtils.trimToNull(segment.getSceneId());
        StringBuilder facts = new StringBuilder();
        boolean matched = false;
        if (binding != null && globalContext != null && globalContext.getScenes() != null) {
            for (DecomposedSceneVO scene : globalContext.getScenes()) {
                if (scene == null) continue;
                String sceneId = StringUtils.trimToNull(scene.getId());
                String sceneName = StringUtils.trimToNull(scene.getSceneName());
                if (binding.equals(sceneId) || binding.equals(sceneName)) {
                    facts.append("- 场景ID: ").append(StringUtils.defaultIfBlank(sceneId, "未提供"))
                            .append(" | 场景名称: ").append(StringUtils.defaultIfBlank(sceneName, "未提供"));
                    if (StringUtils.isNotBlank(scene.getDescription())) {
                        facts.append(" | 空间说明: ").append(scene.getDescription().trim());
                    }
                    facts.append("\n");
                    matched = true;
                }
            }
        }
        if (!matched) facts.append("绑定场景资产详情: 未提供\n");
        return facts.toString();
    }

    private String buildBoundCharacterAssetFacts(StorySegment segment, GlobalStoryContext globalContext) {
        List<String> names = segment.getCharacterIds() == null ? List.of() : segment.getCharacterIds();
        StringBuilder facts = new StringBuilder();
        if (globalContext != null && globalContext.getCharacters() != null) {
            for (DecomposedCharacterVO character : globalContext.getCharacters()) {
                if (character == null || !matchesCharacterReference(names, character)) continue;
                facts.append("- 人物名称: ").append(StringUtils.firstNonBlank(character.getCanonicalName(), character.getName(), character.getDisplayName()));
                Long assetId = character.getMatchedCharacterId() != null ? character.getMatchedCharacterId() : character.getExistingCharacterId();
                if (assetId != null) facts.append(" | 已匹配人物资产ID: ").append(assetId);
                if (StringUtils.isNotBlank(character.getRoleType())) facts.append(" | 角色定位: ").append(character.getRoleType());
                if (StringUtils.isNotBlank(character.getAppearanceDesc())) facts.append(" | 外貌资料: ").append(character.getAppearanceDesc().trim());
                if (StringUtils.isNotBlank(character.getPersonality())) facts.append(" | 人设资料: ").append(character.getPersonality().trim());
                facts.append("\n");
            }
        }
        if (facts.isEmpty()) facts.append("匹配人物资产资料: 未提供\n");
        return facts.toString();
    }

    private boolean matchesCharacterReference(List<String> references, DecomposedCharacterVO character) {
        return references.contains(character.getName()) || references.contains(character.getCanonicalName())
                || references.contains(character.getDisplayName())
                || character.getAliases() != null && character.getAliases().stream().anyMatch(references::contains);
    }

    private String buildBoundPropAssetFacts(StorySegment segment, GlobalStoryContext globalContext) {
        List<String> propIds = segment.getPropIds() == null ? List.of() : segment.getPropIds();
        List<String> propNames = segment.getImportantPropIds() == null ? List.of() : segment.getImportantPropIds();
        StringBuilder facts = new StringBuilder();
        if (globalContext != null && globalContext.getProps() != null) {
            for (DecomposedPropVO prop : globalContext.getProps()) {
                if (prop == null) continue;
                boolean matched = (StringUtils.isNotBlank(prop.getId()) && propIds.contains(prop.getId()))
                        || (StringUtils.isNotBlank(prop.getName()) && propNames.contains(prop.getName()));
                if (!matched) continue;
                facts.append("- 道具ID: ").append(StringUtils.defaultIfBlank(prop.getId(), "未提供"))
                        .append(" | 道具名称: ").append(StringUtils.defaultIfBlank(prop.getName(), "未提供"));
                if (StringUtils.isNotBlank(prop.getDescription())) facts.append(" | 资产说明: ").append(prop.getDescription().trim());
                facts.append("\n");
            }
        }
        if (facts.isEmpty()) facts.append("匹配道具资产资料: 未提供\n");
        return facts.toString();
    }

    void enforceAutoCameraPlaceholders(WorkerShotResult result) {
        if (result.getScenes() == null) return;
        for (WorkerShotResult.WorkerSceneVO scene : result.getScenes()) {
            if (scene == null || scene.getShots() == null) continue;
            for (WorkerShotResult.WorkerShotVO shot : scene.getShots()) {
                if (shot == null) continue;
                WorkerShotResult.WorkerCameraVO camera = shot.getCamera();
                if (camera == null) {
                    camera = new WorkerShotResult.WorkerCameraVO();
                    shot.setCamera(camera);
                }
                String shotSize = StringUtils.trimToNull(camera.getShotSize());
                String movement = StringUtils.trimToNull(camera.getMovement());
                if ((shotSize != null && !"AUTO".equalsIgnoreCase(shotSize))
                        || (movement != null && !"AUTO".equalsIgnoreCase(movement))) {
                    log.warn("[Worker] segment={} shot={} 返回了摄影设计；强制保留 AUTO 占位值",
                            result.getSegmentId(), shot.getLocalId());
                }
                camera.setShotSize("AUTO");
                camera.setMovement("AUTO");
            }
        }
    }

    private String buildLightweightPreviousContext(StorySegment prevSeg) {
        if (prevSeg == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("前一段落标题: ").append(prevSeg.getTitle()).append("\n");
        sb.append("前一段落概要: ").append(prevSeg.getSummary()).append("\n");
        if (prevSeg.getCharacterIds() != null && !prevSeg.getCharacterIds().isEmpty()) {
            sb.append("出场角色: ").append(String.join(", ", prevSeg.getCharacterIds())).append("\n");
        }
        if (prevSeg.getLocationIds() != null && !prevSeg.getLocationIds().isEmpty()) {
            sb.append("场景地点: ").append(String.join(", ", prevSeg.getLocationIds())).append("\n");
        }
        return sb.toString();
    }

    private String buildLightweightNextContext(StorySegment nextSeg) {
        if (nextSeg == null) return null;
        return String.format("下一段落标题: %s\n下一段落概要: %s", nextSeg.getTitle(), nextSeg.getSummary());
    }

    private int getIntConfig(String key, int defaultValue) {
        try {
            String val = sysConfigService.getConfigValue(key, String.valueOf(defaultValue));
            return Integer.parseInt(val.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
