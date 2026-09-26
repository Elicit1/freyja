package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.res.CharacterShotRefDTO;
import com.astra.freyja.dto.drama.PropShotRefDTO;
import com.astra.freyja.dto.res.PromptAssembleRequestDTO;
import com.astra.freyja.dto.res.ResCharacterAliasDTO;
import com.astra.freyja.dto.script.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.entity.enums.AiTaskType;
import com.astra.freyja.entity.enums.AliasType;
import com.astra.freyja.entity.enums.EvidenceType;
import com.astra.freyja.entity.enums.IdentityStatus;
import com.astra.freyja.engine.sse.ConcurrentSseBridge;
import com.astra.freyja.service.*;
import com.astra.freyja.service.prompt.PromptBuilder;
import com.astra.freyja.skill.service.ScriptSkillRuntime;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 工业级剧本/章节分镜生成服务实现 (并行分段解析架构: Planner AI × 1 + Worker AI × N 并行 + Java 确定性合并与规则引擎)。
 * 严格遵循「整章优先、Planner 仅分段、Worker 并行生成、Java 确定性合并与连续性体检、PromptBuilder 本地组装」架构。
 */
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@Service
public class ScriptDecomposeServiceImpl implements ScriptDecomposeService {

    private final ChapterDecompositionService chapterDecompositionService;
    private final ParallelShotGenerationService parallelShotGenerationService;
    private final ShotMergeService shotMergeService;
    private final CharacterRegistryService characterRegistryService;
    private final PromptBuilder promptBuilder;

    private final ResCharacterMapper characterMapper;
    private final ResCharacterOutfitMapper outfitMapper;
    private final ResSceneMapper resSceneMapper;
    private final ResPropMapper resPropMapper;
    private final DramaMapper dramaMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaSceneMapper dramaSceneMapper;
    private final DramaShotGroupMapper shotGroupMapper;
    private final DramaShotMapper shotMapper;
    private final PromptAssembleService promptAssembleService;
    private final ObjectMapper objectMapper;
    private final AiTaskService aiTaskService;
    @Autowired(required = false)
    private ScriptSkillRuntime scriptSkillRuntime;
    @Autowired(required = false)
    private ShotPromptEventStore aiEvents;
    @Autowired(required = false)
    private ScriptDecomposeDraftStore draftStore;

    @Autowired
    public ScriptDecomposeServiceImpl(
            ChapterDecompositionService chapterDecompositionService,
            ParallelShotGenerationService parallelShotGenerationService,
            ShotMergeService shotMergeService,
            CharacterRegistryService characterRegistryService,
            PromptBuilder promptBuilder,
            ResCharacterMapper characterMapper,
            ResCharacterOutfitMapper outfitMapper,
            ResSceneMapper resSceneMapper,
            ResPropMapper resPropMapper,
            DramaMapper dramaMapper,
            DramaEpisodeMapper episodeMapper,
            DramaSceneMapper dramaSceneMapper,
            DramaShotGroupMapper shotGroupMapper,
            DramaShotMapper shotMapper,
            PromptAssembleService promptAssembleService,
            ObjectMapper objectMapper,
            @Autowired(required = false) AiTaskService aiTaskService) {
        this.chapterDecompositionService = chapterDecompositionService;
        this.parallelShotGenerationService = parallelShotGenerationService;
        this.shotMergeService = shotMergeService;
        this.characterRegistryService = characterRegistryService;
        this.promptBuilder = promptBuilder;
        this.characterMapper = characterMapper;
        this.outfitMapper = outfitMapper;
        this.resSceneMapper = resSceneMapper;
        this.resPropMapper = resPropMapper;
        this.dramaMapper = dramaMapper;
        this.episodeMapper = episodeMapper;
        this.dramaSceneMapper = dramaSceneMapper;
        this.shotGroupMapper = shotGroupMapper;
        this.shotMapper = shotMapper;
        this.promptAssembleService = promptAssembleService;
        this.objectMapper = objectMapper;
        this.aiTaskService = aiTaskService;
    }

    public ScriptDecomposeServiceImpl(
            ChapterDecompositionService chapterDecompositionService,
            ParallelShotGenerationService parallelShotGenerationService,
            ShotMergeService shotMergeService,
            CharacterRegistryService characterRegistryService,
            PromptBuilder promptBuilder,
            ResCharacterMapper characterMapper,
            ResCharacterOutfitMapper outfitMapper,
            ResSceneMapper resSceneMapper,
            ResPropMapper resPropMapper,
            DramaMapper dramaMapper,
            DramaEpisodeMapper episodeMapper,
            DramaSceneMapper dramaSceneMapper,
            DramaShotGroupMapper shotGroupMapper,
            DramaShotMapper shotMapper,
            PromptAssembleService promptAssembleService,
            ObjectMapper objectMapper) {
        this(chapterDecompositionService, parallelShotGenerationService, shotMergeService,
                characterRegistryService, promptBuilder,
                characterMapper, outfitMapper, resSceneMapper, resPropMapper,
                dramaMapper, episodeMapper, dramaSceneMapper, shotGroupMapper, shotMapper,
                promptAssembleService, objectMapper, null);
    }

    @Override
    public ScriptDecomposeResultVO decompose(ScriptDecomposeRequestDTO request) {
        validateDecomposeRequest(request);
        prepareSkillRequest(request);
        return executePipeline(request, null, msg -> log.info("[ScriptDecompose] {}", msg), null, null);
    }

    @Override
    public SseEmitter decomposeStream(ScriptDecomposeRequestDTO request) {
        validateDecomposeRequest(request);
        prepareSkillRequest(request);
        SseEmitter emitter = new SseEmitter(600_000L);

        emitter.onTimeout(() -> {
            log.warn("[ScriptDecomposeStream] SSE 请求处理超时 (600s)");
            try {
                emitter.send(SseEmitter.event().name("error").data("AI 并行流水线执行超时，请稍后重试"));
            } catch (Exception ignored) {
            } finally {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        });

        emitter.onError(ex -> log.debug("[ScriptDecomposeStream] SSE 客户端连接异常: {}", ex.getMessage()));

        ConcurrentSseBridge sseBridge = new ConcurrentSseBridge(emitter);

        Thread.ofVirtual().start(() -> {
            try {
                BiConsumer<String, String> channelChunkConsumer = (channel, content) -> {
                    if ("SKILL_EVENT".equals(channel)) sseBridge.sendSkillEvent(content);
                    else if ("CHANNEL_RESET".equals(channel)) sseBridge.sendChannelReset(content);
                    else sseBridge.sendChannelChunk(channel, content);
                };

                Consumer<String> stepLogger = msg -> {
                    log.info("[ScriptDecompose] {}", msg);
                    sseBridge.sendChannelChunk("ALL", msg + "\n");
                };

                stepLogger.accept("🚀 正在启动 AI 短剧并行分段分镜解析流水线 (Planner × 1 + Worker × N 并行)...");
                ScriptDecomposeResultVO result = executePipeline(request, channelChunkConsumer, stepLogger, sseBridge, null);

                stepLogger.accept("✨ 整章分段解析、并行 Worker 生成、Java 合并与连续性体检全部完成！正在推送结构化结果...");
                String jsonResult = objectMapper.writeValueAsString(result);
                sseBridge.sendResult(jsonResult);
                sseBridge.complete();
            } catch (Exception e) {
                log.error("[ScriptDecomposeStream] 并行流水线执行异常", e);
                sseBridge.sendError(e.getMessage() != null ? e.getMessage() : "流水线执行异常");
            }
        });

        return emitter;
    }

    @Override
    public String startDecomposeTask(ScriptDecomposeRequestDTO request) {
        validateDecomposeRequest(request);
        prepareSkillRequest(request);
        if (aiTaskService == null || aiEvents == null || draftStore == null) throw new BizException("AI 任务或 Redis 草稿服务不可用");
        AiTask task = aiTaskService.createTask(request.getDramaId(), request.getEpisodeId(),
                AiTaskType.CHAPTER_DECOMPOSE, "CHAPTER_" + System.currentTimeMillis() % 100000,
                null, null, request.getModelCode(), 16384);
        if (task == null || task.getId() == null) throw new BizException("拆解任务创建失败");
        if (aiTaskService.getTaskById(task.getId()) == null) throw new BizException("拆解任务持久化失败");
        String id = String.valueOf(task.getId());
        try {
            draftStore.saveRequest(task.getId(), request);
            aiEvents.appendScript(id, "task_created", id);
            log.info("[ScriptDecomposeTask] taskId={} created, model={}, textLength={}, plannerRequiredSkills={}, plannerDynamic={}, workerRequiredSkills={}, workerDynamic={}",
                    id, request.getModelCode(), request.getRawText() == null ? 0 : request.getRawText().length(),
                    request.getSkillPolicy() == null || request.getSkillPolicy().getPlanner() == null ? List.of() : request.getSkillPolicy().getPlanner().getRequiredSkillNames(),
                    request.getSkillPolicy() != null && request.getSkillPolicy().getPlanner() != null && request.getSkillPolicy().getPlanner().isAllowDynamicLoad(),
                    request.getSkillPolicy() == null || request.getSkillPolicy().getWorker() == null ? List.of() : request.getSkillPolicy().getWorker().getRequiredSkillNames(),
                    request.getSkillPolicy() != null && request.getSkillPolicy().getWorker() != null && request.getSkillPolicy().getWorker().isAllowDynamicLoad());
        }
        catch (Exception e) {
            aiTaskService.markFailed(task.getId(), "Redis 草稿写入失败");
            throw new BizException("Redis 草稿写入失败: " + e.getMessage());
        }
        Thread.ofVirtual().start(() -> {
            ConcurrentSseBridge bridge = new ConcurrentSseBridge((type, data) -> aiEvents.appendScript(id, type, data));
            BiConsumer<String, String> channel = (name, content) -> {
                if ("SKILL_EVENT".equals(name)) bridge.sendSkillEvent(content);
                else if ("CHANNEL_RESET".equals(name)) bridge.sendChannelReset(content);
                else bridge.sendChannelChunk(name, content);
            };
            Consumer<String> logger = msg -> {
                log.info("[ScriptDecompose] taskId={} {}", id, msg);
                bridge.sendChannelChunk("ALL", msg + "\n");
            };
            try {
                log.info("[ScriptDecomposeTask] taskId={} started", id);
                logger.accept("🚀 正在启动 AI 短剧并行分段分镜解析流水线...");
                ScriptDecomposeResultVO result = executePipeline(request, channel, logger, bridge, task);
                bridge.sendResult(objectMapper.writeValueAsString(result));
                bridge.complete();
                log.info("[ScriptDecomposeTask] taskId={} finished, status={}, skillEvents={}",
                        id, result.getStatus(), result.getSkillEvents() == null ? 0 : result.getSkillEvents().size());
            } catch (Exception e) {
                log.error("[ScriptDecomposeTask] 拆解失败: taskId={}", id, e);
                aiTaskService.markFailed(task.getId(), e.getMessage());
                bridge.sendError(e.getMessage());
            }
        });
        return id;
    }

    /**
     * 核心整章并行分段解析流水线执行逻辑。
     */
    private ScriptDecomposeResultVO executePipeline(ScriptDecomposeRequestDTO request,
                                                   BiConsumer<String, String> channelChunkConsumer,
                                                   Consumer<String> stepLogger,
                                                   ConcurrentSseBridge sseBridge,
                                                   AiTask existingTask) {
        long startTime = System.currentTimeMillis();
        int rawLen = request.getRawText() != null ? request.getRawText().length() : 0;

        stepLogger.accept(String.format("========================================================================\n" +
                "[1/5] 📖 全局上下文加载与 Planner AI 章节大纲事件分段 (文本总长: %d 字)...", rawLen));

        // 0. 创建整章拆解父级持久化任务 (CHAPTER_DECOMPOSE)
        AiTask parentTask = existingTask;
        if (aiTaskService != null && parentTask == null) {
            parentTask = aiTaskService.createTask(
                    request.getDramaId(),
                    request.getEpisodeId(),
                    AiTaskType.CHAPTER_DECOMPOSE,
                    "CHAPTER_" + System.currentTimeMillis() % 100000,
                    null,
                    draftStore == null ? objectMapper.writeValueAsString(request) : null,
                    request.getModelCode(),
                    16384
            );
        }

        if (aiTaskService != null && parentTask != null) aiTaskService.markRunning(parentTask.getId());

        Long taskId = parentTask != null ? parentTask.getId() : null;
        if (draftStore != null && taskId != null) draftStore.saveRequest(taskId, request);

        // 0. 加载并缓存 GlobalStoryContext (一次性加载，0 次重复查库)
        String registryContext = characterRegistryService.formatRegistryForPrompt(request.getDramaId());
        String sceneRegistryContext = buildPlannerSceneRegistryPrompt(request.getDramaId());
        int startEpNo = (request.getStartEpisodeNo() != null && request.getStartEpisodeNo() > 0)
                ? request.getStartEpisodeNo() : 1;

        Drama existingDrama = null;
        if (request.getDramaId() != null) {
            existingDrama = dramaMapper.selectById(request.getDramaId());
        }

        // 1. 画面风格预设：请求参数优先，已有短剧次之，默认 2D 动漫 / 电影写实兜底
        String effectiveStylePreset = StringUtils.isNotBlank(request.getStylePreset())
                ? request.getStylePreset()
                : (existingDrama != null && StringUtils.isNotBlank(existingDrama.getStylePreset()) ? existingDrama.getStylePreset() : "anime-2d");
        request.setStylePreset(effectiveStylePreset);

        // 2. 视觉基调指南：请求参数优先，已有短剧次之
        String effectiveStyleTone = StringUtils.isNotBlank(request.getStyleTone())
                ? request.getStyleTone()
                : (existingDrama != null ? existingDrama.getStyleTone() : null);
        request.setStyleTone(effectiveStyleTone);

        // 3. 画幅比例：请求参数优先，已有短剧次之，默认 9:16
        String effectiveAspectRatio = StringUtils.isNotBlank(request.getAspectRatio())
                ? request.getAspectRatio()
                : (existingDrama != null && StringUtils.isNotBlank(existingDrama.getAspectRatio()) ? existingDrama.getAspectRatio() : "9:16");
        request.setAspectRatio(effectiveAspectRatio);
        if (draftStore != null && taskId != null) draftStore.saveRequest(taskId, request);

        GlobalStoryContext globalContext = GlobalStoryContext.builder()
                .taskId(taskId)
                .dramaId(request.getDramaId())
                .episodeId(request.getEpisodeId())
                .dramaTitle(existingDrama != null ? existingDrama.getTitle() : null)
                .genre(existingDrama != null ? existingDrama.getGenre() : null)
                .startEpisodeNo(startEpNo)
                .chapterTitle(request.getChapterTitle())
                .aspectRatio(effectiveAspectRatio)
                .stylePreset(effectiveStylePreset)
                .styleTone(effectiveStyleTone)
                .pacingPreset(StringUtils.defaultIfBlank(request.getPacingPreset(), "STANDARD"))
                .characterRegistryPromptText(registryContext)
                .sceneRegistryPromptText(sceneRegistryContext)
                .build();

        try {

        // 1. Planner AI (1 次 AI 调用): 宏观理解整章剧情与事件分段 (List<StorySegment>)
        PlannerDecomposeResultVO plannerResult = chapterDecompositionService.decomposeChapter(
                request.getRawText(), request, globalContext, channelChunkConsumer, stepLogger
        );

        if (plannerResult == null || plannerResult.getSegments() == null || plannerResult.getSegments().isEmpty()) {
            throw new BizException("Planner AI 章节分段未产生有效数据");
        }
        if (draftStore != null && taskId != null) draftStore.savePlanner(taskId, plannerResult);

        if (StringUtils.isBlank(globalContext.getDramaTitle())) {
            globalContext.setDramaTitle(plannerResult.getDramaTitle());
        }
        if (StringUtils.isBlank(globalContext.getGenre())) {
            globalContext.setGenre(plannerResult.getGenre());
        }

        // 结构化推送分段初始化事件给前端，立即创建各 Worker 独立视口
        if (sseBridge != null) {
            sseBridge.sendSegmentsInit(plannerResult.getSegments());
        }

        for (StorySegment seg : plannerResult.getSegments()) {
            stepLogger.accept(String.format("  ↳ 📋 [Worker %s] 规划分段 (第 %d 段: 《%s》, %d 字)",
                    seg.getId(),
                    seg.getSequence() != null ? seg.getSequence() : 1,
                    StringUtils.defaultIfBlank(seg.getTitle(), seg.getId()),
                    seg.getRawText() != null ? seg.getRawText().length() : 0));
        }

        // 2. 场景·角色·道具资产深度提炼与强ID建档 (0 次额外 AI 调用)
        stepLogger.accept("\n[2/5] 🏛️ 场景·角色·道具资产深度提炼与强ID建档 (0 次额外 AI 调用)...");
        List<DecomposedCharacterVO> characters = plannerResult.getCharacters() == null
                ? new ArrayList<>() : plannerResult.getCharacters();
        validatePlannerCharacterReferences(characters, validPlannerCharacterIds(request.getDramaId()));
        List<DecomposedSceneVO> scenes = deduplicateAndAlignScenes(plannerResult.getScenes(), request.getDramaId());
        List<DecomposedPropVO> props = deduplicateAndAlignProps(plannerResult.getProps(), request.getDramaId());
        plannerResult.setCharacters(characters);
        plannerResult.setScenes(scenes);
        plannerResult.setProps(props);
        globalContext.setCharacters(characters);
        globalContext.setScenes(scenes);
        globalContext.setProps(props);

        long matchedChars = characters.stream().filter(c -> c.getMatchedCharacterId() != null || c.getExistingCharacterId() != null).count();
        long newChars = characters.size() - matchedChars;
        stepLogger.accept(String.format("     ↳ 🔗 资产提炼与强ID绑定就绪: 角色 %d 位，场景 %d 处，关键道具 %d 件",
                characters.size(), scenes.size(), props.size()));

        // 结构化推送已发现的场景、角色与道具资产给前端实时看板
        if (sseBridge != null) {
            Map<String, Object> assetsPayload = new HashMap<>();
            assetsPayload.put("characters", characters);
            assetsPayload.put("scenes", scenes);
            assetsPayload.put("props", props);
            sseBridge.sendAssetsDiscovered(assetsPayload);
        }

        // 动态注水格式化场景资产库与道具资产库上下文供所有并行 Worker AI 强引用
        StringBuilder sceneRegistrySb = new StringBuilder();
        for (DecomposedSceneVO sc : scenes) {
            sceneRegistrySb.append(String.format("- 场景编号: %s | 空间名称: %s | 场景生图描述: %s\n",
                    StringUtils.defaultIfBlank(sc.getId(), "SC001"),
                    sc.getSceneName(),
                    StringUtils.defaultIfBlank(sc.getScenePrompt(), "interior scene")));
        }
        globalContext.setSceneRegistryPromptText(sceneRegistrySb.toString());

        StringBuilder propRegistrySb = new StringBuilder();
        for (DecomposedPropVO pr : props) {
            propRegistrySb.append(String.format("- 道具编号: %s | 名称: %s | 视觉生图词: %s\n",
                    StringUtils.defaultIfBlank(pr.getId(), "PR001"),
                    pr.getName(),
                    StringUtils.defaultIfBlank(pr.getPropPrompt(), "key prop")));
        }
        globalContext.setPropRegistryPromptText(propRegistrySb.toString());

        // 动态注水格式化角色注册表上下文给所有并行 Worker AI
        globalContext.setCharacterRegistryPromptText(buildWorkerCharacterRegistry(registryContext, characters));

        // 3. WorkerPool 并行调度 (Worker AI × N 并行，Semaphore 限制并发)
        stepLogger.accept(String.format("\n[3/5] ⚡ WorkerPool 启动多分段并行分镜生成 (%d 个 Segment 并行调度)...",
                plannerResult.getSegments().size()));

        Consumer<SegmentShotResult> progressCallback = segProgress -> {
            if (draftStore != null && taskId != null
                    && ("SUCCESS".equalsIgnoreCase(segProgress.getStatus()) || "FAILED".equalsIgnoreCase(segProgress.getStatus()))) {
                draftStore.saveWorker(taskId, segProgress);
            }
            if (sseBridge == null) return;
            int shotCount = (segProgress.getShotResult() != null && segProgress.getShotResult().getScenes() != null)
                    ? segProgress.getShotResult().getScenes().stream().mapToInt(sc -> sc.getShots() != null ? sc.getShots().size() : 0).sum()
                    : 0;
            Double duration = segProgress.getDurationMs() != null ? segProgress.getDurationMs() / 1000.0 : null;
            sseBridge.sendWorkerStatus(segProgress.getSegmentId(), segProgress.getStatus(), shotCount, duration);
        };

        List<SegmentShotResult> segmentResults = parallelShotGenerationService.generateShotsInParallel(
                plannerResult.getSegments(),
                globalContext,
                request,
                channelChunkConsumer,
                stepLogger,
                progressCallback
        );

        // 4. Java Merge Engine: 按 segment.sequence 排序、合并 Scene 与 ShotGroup、全局重编 Shot ID
        stepLogger.accept("\n[4/5] 🧩 Java Merge Engine 确定性合并与连续性体检 (ShotDurationRule) (0 次额外 AI 调用)...");
        ScriptDecomposeResultVO mergedResult = shotMergeService.mergeSegmentResults(
                segmentResults, plannerResult, globalContext, stepLogger
        );

        // 5. 规则体检与 PromptBuilder 本地生成
        stepLogger.accept("\n[5/5] 🎨 PromptBuilder 影视级视听纯英文提示词装配完成 (场景打光 + 关键道具 + 主焦点角色)...");
        Set<String> registeredNames = new HashSet<>();
        for (DecomposedCharacterVO c : characters) {
            if (StringUtils.isNotBlank(c.getName())) registeredNames.add(c.getName().trim());
            if (StringUtils.isNotBlank(c.getCanonicalName())) registeredNames.add(c.getCanonicalName().trim());
        }

        List<DecomposedShotVO> allFlattenedShots = new ArrayList<>();
        int totalShots = processScenesAndShots(mergedResult, registeredNames, scenes, props, request.getStylePreset(), allFlattenedShots, stepLogger);

        // 6. 计算镜头时长分布与碎片率指标 (防碎镜检测)
        FragmentationStatsVO fragStats = shotMergeService.calculateFragmentationStats(allFlattenedShots);
        mergedResult.setFragmentationStats(fragStats);

        if (Boolean.TRUE.equals(fragStats.getIsHighFragmentation())) {
            stepLogger.accept(String.format("     ↳ %s", fragStats.getWarningMessage()));
        } else {
            stepLogger.accept(String.format("     ↳ 📊 镜头时长健康度良好 (平均时长: %.1fs, 短镜头占比: %.1f%%)",
                    fragStats.getAverageDuration(), fragStats.getShortShotRatio() * 100));
        }

        long elapsedMs = System.currentTimeMillis() - startTime;
        stepLogger.accept(String.format("\n========================================================================\n" +
                        "🎉 整章并行分段分镜生成全部就绪！(总耗时: %.2fs)\n" +
                        "📊 统计成果: 划分 %d 分段, 沉淀 %d 角色, %d 场景, 生成 %d 个连贯分镜 (平均时长 %.1fs)",
                elapsedMs / 1000.0,
                plannerResult.getSegments().size(),
                mergedResult.getCharacters() != null ? mergedResult.getCharacters().size() : 0,
                mergedResult.getScenes() != null ? mergedResult.getScenes().size() : 0,
                totalShots,
                fragStats.getAverageDuration()));

        mergedResult.setTaskId(taskId);
        mergedResult.setSegmentResults(segmentResults);
        mergedResult.setSkillEvents(List.copyOf(globalContext.getSkillEvents()));

        // 7. 持久化落库预览大纲与父任务状态
        boolean anyFailed = segmentResults.stream().anyMatch(sr -> "FAILED".equalsIgnoreCase(sr.getStatus()));
        List<String> failedIds = segmentResults.stream().filter(sr -> "FAILED".equalsIgnoreCase(sr.getStatus())).map(SegmentShotResult::getSegmentId).toList();

        if (anyFailed) {
            mergedResult.setStatus("PARTIAL_SUCCESS");
        } else {
            mergedResult.setStatus("SUCCESS");
        }

        if (draftStore != null && taskId != null) draftStore.saveResult(taskId, mergedResult);
        if (aiTaskService != null && taskId != null) {
            String outputJson = draftStore == null ? objectMapper.writeValueAsString(mergedResult) : null;
            if (anyFailed) {
                aiTaskService.markPartialSuccess(taskId, outputJson, "部分分段失败待补救: " + String.join(", ", failedIds));
            } else {
                aiTaskService.markSuccess(taskId, outputJson, (int) elapsedMs);
            }
        }

        return mergedResult;
        } catch (Exception e) {
            if (aiTaskService != null && taskId != null) {
                aiTaskService.markFailed(taskId, e.getMessage());
            }
            throw e;
        }
    }

    /**
     * Java 本地对全部 Scene/ShotGroup/Shot 进行连续性规则校验、自动平滑修复与 PromptBuilder 组装。
     */
    private int processScenesAndShots(ScriptDecomposeResultVO resultVO,
                                      Set<String> registeredCharacters,
                                      List<DecomposedSceneVO> scenes,
                                      List<DecomposedPropVO> props,
                                      String stylePreset,
                                      List<DecomposedShotVO> allFlattenedShots,
                                      Consumer<String> stepLogger) {
        if (resultVO.getEpisodes() == null || resultVO.getEpisodes().isEmpty()) {
            return 0;
        }

        // 建立场景 ID 映射与名称映射 (支持强 ID 查找与名称容错)
        Map<String, DecomposedSceneVO> sceneIdMap = new HashMap<>();
        Map<String, DecomposedSceneVO> sceneNameMap = new HashMap<>();
        if (scenes != null) {
            for (DecomposedSceneVO sc : scenes) {
                if (StringUtils.isNotBlank(sc.getId())) sceneIdMap.put(sc.getId().trim(), sc);
                if (StringUtils.isNotBlank(sc.getSceneName())) sceneNameMap.put(sc.getSceneName().trim(), sc);
            }
        }

        int totalShots = 0;
        int epNo = 1;

        for (DecomposedEpisodeVO ep : resultVO.getEpisodes()) {
            if (ep.getEpisodeNo() == null) ep.setEpisodeNo(epNo++);
            if (ep.getScenes() == null) continue;

            int scNo = 1;
            for (DecomposedEpisodeSceneVO sc : ep.getScenes()) {
                if (sc.getSceneNo() == null) sc.setSceneNo(scNo++);

                // 优先按 sceneId 强绑定，其次按 sceneName，最后模糊匹配与首场景兜底
                DecomposedSceneVO matchedScene = null;
                if (StringUtils.isNotBlank(sc.getSceneId())) {
                    matchedScene = sceneIdMap.get(sc.getSceneId().trim());
                }
                if (matchedScene == null && StringUtils.isNotBlank(sc.getSceneName())) {
                    matchedScene = sceneNameMap.get(sc.getSceneName().trim());
                }
                if (matchedScene == null && scenes != null && !scenes.isEmpty()) {
                    for (DecomposedSceneVO candidate : scenes) {
                        if (sc.getSceneName() != null && (sc.getSceneName().contains(candidate.getSceneName()) || candidate.getSceneName().contains(sc.getSceneName()))) {
                            matchedScene = candidate;
                            break;
                        }
                    }
                    if (matchedScene == null) {
                        matchedScene = scenes.get(0);
                    }
                }
                if (matchedScene != null) {
                    if (StringUtils.isBlank(sc.getSceneId())) sc.setSceneId(matchedScene.getId());
                    if (sc.getResSceneId() == null) sc.setResSceneId(matchedScene.getExistingSceneId());
                }

                if (sc.getShotGroups() == null || sc.getShotGroups().isEmpty()) {
                    if (sc.getShots() != null && !sc.getShots().isEmpty()) {
                        DecomposedShotGroupVO defaultG = new DecomposedShotGroupVO();
                        defaultG.setGroupNo(1);
                        defaultG.setName(StringUtils.defaultIfBlank(sc.getSceneName(), "场次" + sc.getSceneNo()) + " - 连续动作组");
                        defaultG.setPurpose("连续视听动作单元");
                        defaultG.setShots(new ArrayList<>(sc.getShots()));
                        sc.setShotGroups(new ArrayList<>(List.of(defaultG)));
                    }
                }

                if (sc.getShotGroups() != null) {
                    int gNo = 1;
                    for (DecomposedShotGroupVO group : sc.getShotGroups()) {
                        if (group.getGroupNo() == null) group.setGroupNo(gNo++);

                        if (group.getShots() != null && !group.getShots().isEmpty()) {
                            for (int sIdx = 0; sIdx < group.getShots().size(); sIdx++) {
                                DecomposedShotVO s = group.getShots().get(sIdx);
                                if (s.getShotNo() == null) s.setShotNo(totalShots + sIdx + 1);
                                if (StringUtils.isBlank(s.getGroupId())) {
                                    s.setGroupId(String.format("G%03d", group.getGroupNo()));
                                }
                                if (s.getDuration() == null || s.getDuration() <= 0) {
                                    s.setDuration(5.0);
                                }
                            }

                            // 直接使用 AI 产出的高质量首帧生图词 (firstFrameVisual) 与视频运镜词 (videoPrompt)，杜绝机械硬拼装
                            for (DecomposedShotVO s : group.getShots()) {
                                totalShots++;
                                if (StringUtils.isBlank(s.getSceneId()) && matchedScene != null) {
                                    s.setSceneId(matchedScene.getId());
                                }
                                if (s.getResSceneId() == null && matchedScene != null) {
                                    s.setResSceneId(matchedScene.getExistingSceneId());
                                }

                                // 提示词与负向词完全由人工或创作者主动调用 AI 填写，拆解阶段不注入默认值或强塞兜底
                                s.setPrompt(StringUtils.trimToNull(s.getPrompt()));
                                s.setFirstFramePrompt(StringUtils.trimToNull(s.getFirstFramePrompt()));
                                s.setEndFramePrompt(StringUtils.trimToNull(s.getEndFramePrompt()));
                                s.setNegativePrompt(StringUtils.trimToNull(s.getNegativePrompt()));
                                s.setVideoPrompt(StringUtils.trimToNull(s.getVideoPrompt()));
                                allFlattenedShots.add(s);
                            }
                        }
                    }

                    // 同步平铺 shots
                    List<DecomposedShotVO> flattened = new ArrayList<>();
                    for (DecomposedShotGroupVO g : sc.getShotGroups()) {
                        if (g.getShots() != null) flattened.addAll(g.getShots());
                    }
                    sc.setShots(flattened);
                }
            }
        }

        return totalShots;
    }

    @Override
    public DecomposedEpisodeVO decomposeEpisode(ScriptDecomposeRequestDTO request) {
        validateDecomposeRequest(request);
        ScriptDecomposeResultVO fullResult = decompose(request);
        if (fullResult != null && fullResult.getEpisodes() != null && !fullResult.getEpisodes().isEmpty()) {
            return fullResult.getEpisodes().get(0);
        }
        return new DecomposedEpisodeVO();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long commit(ScriptDecomposeCommitDTO commitDTO) {
        if (commitDTO == null) {
            throw new BizException("提交入库参数不能为空");
        }

        log.info("[ScriptDecomposeCommit] 收到剧本拆解入库请求: dramaId={}, dramaTitle='{}', genre='{}', 集数={}, 角色数={}, 场景数={}",
                commitDTO.getDramaId(),
                commitDTO.getDramaTitle(),
                commitDTO.getGenre(),
                commitDTO.getEpisodes() != null ? commitDTO.getEpisodes().size() : 0,
                commitDTO.getCharacters() != null ? commitDTO.getCharacters().size() : 0,
                commitDTO.getScenes() != null ? commitDTO.getScenes().size() : 0);

        // 1. 处理短剧主记录 (Drama)
        Long dramaId = commitDTO.getDramaId();
        if (dramaId == null) {
            Drama drama = new Drama();
            drama.setTitle(StringUtils.defaultIfBlank(commitDTO.getDramaTitle(), "AI一键生成短剧_" + System.currentTimeMillis() % 10000));
            drama.setGenre(StringUtils.defaultIfBlank(commitDTO.getGenre(), "DOMINANT_CEO"));
            drama.setSynopsis(commitDTO.getSynopsis());
            drama.setAspectRatio(StringUtils.defaultIfBlank(commitDTO.getAspectRatio(), "9:16"));
            drama.setStylePreset(StringUtils.defaultIfBlank(commitDTO.getStylePreset(), "cinematic-realism"));
            drama.setStyleTone(commitDTO.getStyleTone());
            drama.setTargetEpisodes(commitDTO.getEpisodes() != null ? commitDTO.getEpisodes().size() : 1);
            drama.setStatus("PLANNING");
            drama.setSortOrder(0);
            dramaMapper.insert(drama);
            dramaId = drama.getId();
        } else {
            // 已有短剧：保持短剧原有基础信息（名称、题材、画幅、风格等），绝不被 AI 单次拆解推断内容覆盖
            log.info("[ScriptDecomposeCommit] 绑定已有短剧 (dramaId={})，保持已有短剧标题与基础设定不变", dramaId);
        }

        // 1.1 若请求关联了拆解任务 ID，回填绑定任务及其子任务归属此短剧 (确保历史记录在短剧下可被准确检索)
        if (commitDTO.getTaskId() != null && aiTaskService != null) {
            aiTaskService.bindDrama(commitDTO.getTaskId(), dramaId);
        }

        // 2. 处理角色资产入库与消歧映射
        validatePlannerCharacterReferences(commitDTO.getCharacters(), validPlannerCharacterIds(dramaId));
        Map<String, Long> characterNameToId = new HashMap<>();
        Map<String, Long> characterNameToLookId = new HashMap<>();
        if (commitDTO.getCharacters() != null) {
            for (DecomposedCharacterVO charVO : commitDTO.getCharacters()) {
                if (charVO == null || IdentityStatus.UNRESOLVED.name().equalsIgnoreCase(charVO.getIdentityStatus())) {
                    continue;
                }
                String primaryName = StringUtils.firstNonBlank(charVO.getCanonicalName(), charVO.getName(), charVO.getDisplayName());
                if (StringUtils.isBlank(primaryName)) continue;

                Long charId = charVO.getMatchedCharacterId() != null ? charVO.getMatchedCharacterId() : charVO.getExistingCharacterId();
                if (charId != null) {
                    ResCharacter existingChar = characterMapper.selectById(charId);
                    if (existingChar == null || !Objects.equals(existingChar.getDramaId(), dramaId)) {
                        throw new BizException(400, "角色 ID 不属于当前短剧，无法保存引用关系: " + charId);
                    }
                    boolean updated = false;
                    if (StringUtils.isNotBlank(charVO.getCanonicalName())
                            && (StringUtils.isBlank(existingChar.getCanonicalName())
                            || IdentityStatus.PARTIAL.name().equalsIgnoreCase(existingChar.getIdentityStatus()))) {
                        existingChar.setCanonicalName(charVO.getCanonicalName().trim());
                        existingChar.setName(charVO.getCanonicalName().trim());
                        existingChar.setIdentityStatus(IdentityStatus.CONFIRMED.name());
                        updated = true;
                    }
                    if (StringUtils.isNotBlank(charVO.getAppearancePrompt()) && StringUtils.isBlank(existingChar.getAppearancePrompt())) {
                        existingChar.setAppearancePrompt(charVO.getAppearancePrompt());
                        updated = true;
                    }
                    if (updated) characterMapper.updateById(existingChar);

                    ResCharacterOutfit defaultOutfit = outfitMapper.selectOne(new LambdaQueryWrapper<ResCharacterOutfit>()
                            .eq(ResCharacterOutfit::getCharacterId, charId)
                            .eq(ResCharacterOutfit::getIsDefault, 1)
                            .last("LIMIT 1"));
                    Long lookId = defaultOutfit != null ? defaultOutfit.getId() : null;
                    bindNamesToCharacterMaps(charVO, charId, lookId, characterNameToId, characterNameToLookId);
                } else {
                    ResCharacter resChar = new ResCharacter();
                    resChar.setDramaId(dramaId);
                    resChar.setName(primaryName);
                    resChar.setCanonicalName(charVO.getCanonicalName());
                    resChar.setDisplayName(StringUtils.defaultIfBlank(charVO.getDisplayName(), primaryName));
                    resChar.setRoleType(StringUtils.defaultIfBlank(charVO.getRoleType(), "PROTAGONIST"));
                    resChar.setGender(StringUtils.defaultIfBlank(charVO.getGender(), "UNKNOWN"));
                    resChar.setAgeGroup(charVO.getAgeGroup());
                    resChar.setIdentityStatus(StringUtils.defaultIfBlank(charVO.getIdentityStatus(),
                            StringUtils.isNotBlank(charVO.getCanonicalName()) ? IdentityStatus.CONFIRMED.name() : IdentityStatus.PARTIAL.name()));
                    resChar.setPersonality(charVO.getPersonality());
                    resChar.setAppearanceDesc(charVO.getAppearanceDesc());
                    resChar.setAppearancePrompt(charVO.getAppearancePrompt());
                    resChar.setTriggerWords(charVO.getTriggerWords());
                    resChar.setStatus(1);
                    resChar.setSortOrder(0);
                    characterMapper.insert(resChar);
                    charId = resChar.getId();

                    ResCharacterOutfit outfit = new ResCharacterOutfit();
                    outfit.setCharacterId(charId);
                    outfit.setLookName("默认造型");
                    outfit.setIsDefault(1);
                    outfit.setOutfitPrompt(StringUtils.defaultIfBlank(charVO.getOutfitPrompt(), ""));
                    outfit.setStatus(1);
                    outfit.setSortOrder(0);
                    outfitMapper.insert(outfit);

                    bindNamesToCharacterMaps(charVO, charId, outfit.getId(), characterNameToId, characterNameToLookId);
                }

                saveCharacterAliases(charVO, charId, dramaId);

                if (StringUtils.isNotBlank(charVO.getEvidenceText()) || StringUtils.isNotBlank(charVO.getEvidenceType())) {
                    characterRegistryService.recordEvidence(
                            dramaId,
                            charId,
                            null,
                            null,
                            StringUtils.defaultIfBlank(charVO.getEvidenceText(), "剧本解析提取角色: " + primaryName),
                            StringUtils.defaultIfBlank(charVO.getEvidenceType(), EvidenceType.FIRST_APPEARANCE.name()),
                            BigDecimal.valueOf(charVO.getConfidence() != null ? charVO.getConfidence() : 1.00),
                            "AI 剧本拆解自动沉淀依据"
                    );
                }
            }
            characterRegistryService.persistUnresolvedPreview(commitDTO.getCharacters(), dramaId, null);
        }

        // 3. 处理场景资产入库与映射
        Map<String, Long> sceneNameToId = new HashMap<>();
        Map<String, Long> sceneLocalIdToAssetId = new HashMap<>();
        if (commitDTO.getScenes() != null) {
            for (DecomposedSceneVO scVO : commitDTO.getScenes()) {
                if (StringUtils.isBlank(scVO.getSceneName())) continue;
                String localSceneId = StringUtils.trimToNull(scVO.getId());
                Long mappedSceneId = localSceneId == null ? null : sceneLocalIdToAssetId.get(localSceneId);
                Long scId = scVO.getExistingSceneId();
                if (scId != null) {
                    requireExistingSceneReference(scId, dramaId);
                }
                if (mappedSceneId != null && scId != null && !Objects.equals(mappedSceneId, scId)) {
                    throw new BizException(400, "同一拆解场景编号不能绑定到不同的场景资产: " + localSceneId);
                }
                if (mappedSceneId != null) scId = mappedSceneId;
                if (scId == null) {
                    LambdaQueryWrapper<ResScene> query = new LambdaQueryWrapper<ResScene>()
                            .eq(ResScene::getStatus, 1)
                            .eq(ResScene::getName, scVO.getSceneName().trim());
                    if (dramaId != null) {
                        Long finalDramaId = dramaId;
                        query.and(w -> w.eq(ResScene::getDramaId, finalDramaId).or().eq(ResScene::getDramaId, 0L));
                    } else {
                        query.eq(ResScene::getDramaId, 0L);
                    }
                    query.last("LIMIT 1");
                    ResScene existingScene = resSceneMapper.selectOne(query);
                    if (existingScene != null) scId = existingScene.getId();
                }

                if (scId == null) {
                    ResScene resScene = new ResScene();
                    resScene.setDramaId(dramaId);
                    resScene.setName(scVO.getSceneName().trim());
                    resScene.setDescription(scVO.getDescription());
                    resScene.setSceneType(StringUtils.defaultIfBlank(scVO.getSceneType(), "INDOOR"));
                    resScene.setTimeOfDay(StringUtils.defaultIfBlank(scVO.getTimeOfDay(), "DAY"));
                    resScene.setWeatherAtmosphere(scVO.getWeatherAtmosphere());
                    resScene.setScenePrompt(StringUtils.defaultIfBlank(scVO.getScenePrompt(), scVO.getSceneName()));
                    resScene.setStatus(1);
                    resScene.setSortOrder(0);
                    resSceneMapper.insert(resScene);
                    scId = resScene.getId();
                }
                sceneNameToId.putIfAbsent(scVO.getSceneName().trim(), scId);
                if (localSceneId != null) {
                    sceneLocalIdToAssetId.putIfAbsent(localSceneId, scId);
                    sceneNameToId.put(localSceneId, scId);
                }
            }
        }

        // 3.5 处理道具资产入库与映射
        Map<String, Long> propRefToId = new HashMap<>();
        if (commitDTO.getProps() != null && resPropMapper != null) {
            for (DecomposedPropVO propVO : commitDTO.getProps()) {
                if (StringUtils.isBlank(propVO.getName())) continue;
                Long pId = propVO.getExistingPropId();
                if (pId == null) {
                    ResProp existingProp = resPropMapper.selectOne(new LambdaQueryWrapper<ResProp>()
                            .eq(ResProp::getDramaId, dramaId)
                            .eq(ResProp::getName, propVO.getName().trim())
                            .last("LIMIT 1"));
                    if (existingProp != null) pId = existingProp.getId();
                }

                if (pId == null) {
                    ResProp resProp = new ResProp();
                    resProp.setDramaId(dramaId);
                    resProp.setName(propVO.getName().trim());
                    resProp.setPropType(StringUtils.defaultIfBlank(propVO.getPropType(), "KEY_PROP"));
                    resProp.setDescription(propVO.getDescription());
                    resProp.setPropPrompt(StringUtils.defaultIfBlank(propVO.getPropPrompt(), propVO.getName()));
                    resProp.setStatus(1);
                    resProp.setSortOrder(0);
                    resPropMapper.insert(resProp);
                    pId = resProp.getId();
                }
                propRefToId.put(propVO.getName().trim(), pId);
                if (StringUtils.isNotBlank(propVO.getId())) {
                    propRefToId.put(propVO.getId().trim(), pId);
                }
            }
        }

        // 4. 级联持久化剧集、场次、连续镜头组与分镜 (DramaEpisode -> DramaScene -> DramaShotGroup -> DramaShot)
        String commitMode = StringUtils.defaultIfBlank(commitDTO.getCommitMode(), "REPLACE_ALL");
        Integer customTargetEpNo = commitDTO.getTargetEpisodeNo();

        if (commitDTO.getEpisodes() != null && !commitDTO.getEpisodes().isEmpty()) {
            if ("APPEND_TO_EPISODE".equalsIgnoreCase(commitMode)) {
                // =========================================================================
                // 策略 A: 累计追加到已有集 (不分集模式，如整部短剧只有第 1 集，场次与镜头顺延接在第 1 集末尾)
                // =========================================================================
                int targetEpNo = (customTargetEpNo != null && customTargetEpNo > 0) ? customTargetEpNo : 1;
                DramaEpisode existingEpisode = episodeMapper.selectAnyByDramaIdAndEpisodeNo(dramaId, targetEpNo);
                Long episodeId;
                int baseSceneNo = 0;
                int baseGroupNo = 0;
                int baseShotNo = 0;

                if (existingEpisode != null) {
                    episodeId = existingEpisode.getId();
                    baseSceneNo = dramaSceneMapper.selectMaxSceneNoByEpisodeId(episodeId);
                    baseGroupNo = shotGroupMapper.selectMaxGroupNoByEpisodeId(episodeId);
                    baseShotNo = shotMapper.selectMaxShotNoByEpisodeId(episodeId);
                } else {
                    DramaEpisode episode = new DramaEpisode();
                    episode.setDramaId(dramaId);
                    episode.setEpisodeNo(targetEpNo);
                    episode.setTitle("第" + targetEpNo + "集");
                    episode.setTargetDuration(300);
                    episode.setActualDuration(BigDecimal.ZERO);
                    episode.setStatus("DRAFT");
                    episode.setSortOrder(targetEpNo);
                    episodeMapper.insert(episode);
                    episodeId = episode.getId();
                }

                int appendedSceneCount = 0;
                int appendedGroupCount = 0;
                int appendedShotCount = 0;

                for (DecomposedEpisodeVO epVO : commitDTO.getEpisodes()) {
                    if (epVO.getScenes() == null) continue;
                    for (DecomposedEpisodeSceneVO scVO : epVO.getScenes()) {
                        appendedSceneCount++;
                        int currentSceneNo = baseSceneNo + appendedSceneCount;
                        Long matchedResSceneId = null;
                        if (StringUtils.isNotBlank(scVO.getSceneId())) {
                            matchedResSceneId = sceneNameToId.get(scVO.getSceneId().trim());
                        }
                        if (matchedResSceneId == null && StringUtils.isNotBlank(scVO.getSceneName())) {
                            matchedResSceneId = sceneNameToId.get(scVO.getSceneName().trim());
                        }
                        if (matchedResSceneId == null) {
                            matchedResSceneId = scVO.getResSceneId();
                        }

                        DramaScene dramaScene = new DramaScene();
                        dramaScene.setDramaId(dramaId);
                        dramaScene.setEpisodeId(episodeId);
                        dramaScene.setSceneNo(currentSceneNo);
                        dramaScene.setName(StringUtils.defaultIfBlank(scVO.getSceneName(), "场次" + currentSceneNo));
                        dramaScene.setResSceneId(matchedResSceneId);
                        dramaScene.setSummary(scVO.getSummary());
                        dramaScene.setScriptContent(scVO.getScriptContent());
                        dramaScene.setSortOrder(currentSceneNo);
                        dramaSceneMapper.insert(dramaScene);
                        Long sceneId = dramaScene.getId();

                        List<DecomposedShotGroupVO> groupsToProcess = scVO.getShotGroups();
                        if (groupsToProcess == null || groupsToProcess.isEmpty()) {
                            if (scVO.getShots() != null && !scVO.getShots().isEmpty()) {
                                DecomposedShotGroupVO defaultG = new DecomposedShotGroupVO();
                                defaultG.setGroupNo(1);
                                defaultG.setName(dramaScene.getName() + " - 连续动作组");
                                defaultG.setPurpose("连续视听动作单元");
                                defaultG.setShots(scVO.getShots());
                                groupsToProcess = List.of(defaultG);
                            }
                        }

                        if (groupsToProcess != null) {
                            for (DecomposedShotGroupVO gVO : groupsToProcess) {
                                appendedGroupCount++;
                                int currentGroupNo = baseGroupNo + appendedGroupCount;

                                DramaShotGroup dramaGroup = new DramaShotGroup();
                                dramaGroup.setDramaId(dramaId);
                                dramaGroup.setEpisodeId(episodeId);
                                dramaGroup.setSceneId(sceneId);
                                dramaGroup.setGroupNo(currentGroupNo);
                                dramaGroup.setName(StringUtils.defaultIfBlank(gVO.getName(), String.format("镜头组 %02d", currentGroupNo)));
                                dramaGroup.setPurpose(gVO.getPurpose());
                                dramaGroup.setSortOrder(currentGroupNo);
                                shotGroupMapper.insert(dramaGroup);
                                Long groupId = dramaGroup.getId();

                                if (gVO.getShots() != null) {
                                    for (int shotIdx = 0; shotIdx < gVO.getShots().size(); shotIdx++) {
                                        appendedShotCount++;
                                        int currentShotNo = baseShotNo + appendedShotCount;
                                        DecomposedShotVO shotVO = gVO.getShots().get(shotIdx);
                                        BigDecimal duration = shotVO.getDuration() != null
                                                ? BigDecimal.valueOf(shotVO.getDuration()) : BigDecimal.valueOf(5.0);

                                        String charRefsJson = buildCharacterRefsJson(shotVO.getCharacterNames(), characterNameToId, characterNameToLookId);
                                        String propRefsJson = buildPropRefsJson(shotVO.getPropIds(), propRefToId, commitDTO.getProps());

                                        Long shotResSceneId = matchedResSceneId;
                                        if (StringUtils.isNotBlank(shotVO.getSceneId())) {
                                            Long directId = sceneNameToId.get(shotVO.getSceneId().trim());
                                            if (directId != null) shotResSceneId = directId;
                                        }
                                        if (shotResSceneId == null) {
                                            shotResSceneId = shotVO.getResSceneId();
                                        }

                                        DramaShot shot = new DramaShot();
                                        shot.setDramaId(dramaId);
                                        shot.setEpisodeId(episodeId);
                                        shot.setSceneId(sceneId);
                                        shot.setShotGroupId(groupId);
                                        shot.setShotNo(currentShotNo);
                                        shot.setShotName(String.format("S%02d-%02d", targetEpNo, currentShotNo));
                                        shot.setShotType(StringUtils.trimToNull(shotVO.getShotType()));
                                        shot.setCameraMovement(StringUtils.trimToNull(shotVO.getCameraMovement()));
                                        shot.setShotTypeLocked(isExplicitCameraConstraint(shotVO.getShotTypeLocked(), shotVO.getShotType()));
                                        shot.setCameraMovementLocked(isExplicitCameraConstraint(shotVO.getCameraMovementLocked(), shotVO.getCameraMovement()));
                                        shot.setDuration(duration);
                                        shot.setScriptContent(shotVO.getScriptContent());
                                        shot.setActionDescription(shotVO.getActionDescription());
                                        shot.setDialogueSpeaker(shotVO.getDialogueSpeaker());
                                        shot.setDialogue(shotVO.getDialogue());
                                        shot.setVoiceover(shotVO.getVoiceover());
                                        shot.setSoundEffect(shotVO.getSoundEffect());
                                        shot.setResSceneId(shotResSceneId);
                                        shot.setCharacterRefsJson(charRefsJson);
                                        shot.setPropRefsJson(propRefsJson);
                                        shot.setPrompt(shotVO.getPrompt());
                                        shot.setFirstFramePrompt(shotVO.getFirstFramePrompt());
                                        shot.setEndFramePrompt(shotVO.getEndFramePrompt());
                                        shot.setNegativePrompt(shotVO.getNegativePrompt());
                                        shot.setVideoPrompt(shotVO.getVideoPrompt());
                                        shot.setStylePreset(commitDTO.getStylePreset());
                                        shot.setGenerationMode("REFERENCE_MODE");
                                        shot.setRenderStatus("INIT");
                                        shot.setSortOrder(currentShotNo);

                                        shotMapper.insert(shot);
                                    }
                                }
                            }
                        }
                    }
                }

                // 重新汇总更新该集的累计分镜实际时长
                List<DramaShot> allEpisodeShots = shotMapper.selectList(new LambdaQueryWrapper<DramaShot>().eq(DramaShot::getEpisodeId, episodeId));
                BigDecimal episodeTotalDuration = BigDecimal.ZERO;
                if (allEpisodeShots != null) {
                    for (DramaShot s : allEpisodeShots) {
                        if (s.getDuration() != null) episodeTotalDuration = episodeTotalDuration.add(s.getDuration());
                    }
                }
                DramaEpisode epUpdate = new DramaEpisode();
                epUpdate.setId(episodeId);
                epUpdate.setActualDuration(episodeTotalDuration);
                episodeMapper.updateById(epUpdate);

            } else {
                // =========================================================================
                // 策略 B: NEW_EPISODE (作为新集数追加) / 策略 C: OVERWRITE_EPISODE (覆盖指定集) / REPLACE_ALL
                // =========================================================================
                for (int epIdx = 0; epIdx < commitDTO.getEpisodes().size(); epIdx++) {
                    DecomposedEpisodeVO epVO = commitDTO.getEpisodes().get(epIdx);
                    int epNo = epVO.getEpisodeNo() != null ? epVO.getEpisodeNo() : (epIdx + 1);

                    if ("NEW_EPISODE".equalsIgnoreCase(commitMode)) {
                        if (customTargetEpNo != null && customTargetEpNo > 0) {
                            epNo = customTargetEpNo + epIdx;
                        } else {
                            Integer maxEp = episodeMapper.selectMaxEpisodeNoByDramaId(dramaId);
                            epNo = (maxEp != null ? maxEp : 0) + epIdx + 1;
                        }
                    } else if ("OVERWRITE_EPISODE".equalsIgnoreCase(commitMode) && customTargetEpNo != null && customTargetEpNo > 0) {
                        epNo = customTargetEpNo;
                    }

                    DramaEpisode existingEpisode = episodeMapper.selectAnyByDramaIdAndEpisodeNo(dramaId, epNo);
                    Long episodeId;
                    if (existingEpisode != null) {
                        existingEpisode.setTitle(StringUtils.defaultIfBlank(epVO.getTitle(), "第" + epNo + "集"));
                        existingEpisode.setSummary(epVO.getSummary());
                        existingEpisode.setScriptContent(epVO.getScriptContent());
                        if (epVO.getTargetDuration() != null) {
                            existingEpisode.setTargetDuration(epVO.getTargetDuration());
                        }
                        existingEpisode.setActualDuration(BigDecimal.ZERO);
                        existingEpisode.setStatus("DRAFT");
                        episodeMapper.restoreAndUpdate(existingEpisode);
                        episodeId = existingEpisode.getId();

                        List<DramaScene> oldScenes = dramaSceneMapper.selectList(new LambdaQueryWrapper<DramaScene>()
                                .eq(DramaScene::getEpisodeId, episodeId));
                        if (oldScenes != null && !oldScenes.isEmpty()) {
                            List<Long> oldSceneIds = oldScenes.stream().map(DramaScene::getId).toList();
                            shotMapper.delete(new LambdaQueryWrapper<DramaShot>().in(DramaShot::getSceneId, oldSceneIds));
                            shotGroupMapper.delete(new LambdaQueryWrapper<DramaShotGroup>().in(DramaShotGroup::getSceneId, oldSceneIds));
                            dramaSceneMapper.delete(new LambdaQueryWrapper<DramaScene>().eq(DramaScene::getEpisodeId, episodeId));
                        }
                    } else {
                        DramaEpisode episode = new DramaEpisode();
                        episode.setDramaId(dramaId);
                        episode.setEpisodeNo(epNo);
                        episode.setTitle(StringUtils.defaultIfBlank(epVO.getTitle(), "第" + epNo + "集"));
                        episode.setSummary(epVO.getSummary());
                        episode.setScriptContent(epVO.getScriptContent());
                        episode.setTargetDuration(epVO.getTargetDuration() != null ? epVO.getTargetDuration() : 300);
                        episode.setActualDuration(BigDecimal.ZERO);
                        episode.setStatus("DRAFT");
                        episode.setSortOrder(epNo);
                        episodeMapper.insert(episode);
                        episodeId = episode.getId();
                    }

                    BigDecimal episodeActualDuration = BigDecimal.ZERO;
                    if (epVO.getScenes() != null) {
                        for (int scIdx = 0; scIdx < epVO.getScenes().size(); scIdx++) {
                            DecomposedEpisodeSceneVO scVO = epVO.getScenes().get(scIdx);
                            int scNo = scVO.getSceneNo() != null ? scVO.getSceneNo() : (scIdx + 1);
                            Long matchedResSceneId = null;
                            if (StringUtils.isNotBlank(scVO.getSceneId())) {
                                matchedResSceneId = sceneNameToId.get(scVO.getSceneId().trim());
                            }
                            if (matchedResSceneId == null && StringUtils.isNotBlank(scVO.getSceneName())) {
                                matchedResSceneId = sceneNameToId.get(scVO.getSceneName().trim());
                            }
                            if (matchedResSceneId == null) {
                                matchedResSceneId = scVO.getResSceneId();
                            }

                            DramaScene dramaScene = new DramaScene();
                            dramaScene.setDramaId(dramaId);
                            dramaScene.setEpisodeId(episodeId);
                            dramaScene.setSceneNo(scNo);
                            dramaScene.setName(StringUtils.defaultIfBlank(scVO.getSceneName(), "场次" + scNo));
                            dramaScene.setResSceneId(matchedResSceneId);
                            dramaScene.setSummary(scVO.getSummary());
                            dramaScene.setScriptContent(scVO.getScriptContent());
                            dramaScene.setSortOrder(scIdx + 1);
                            dramaSceneMapper.insert(dramaScene);
                            Long sceneId = dramaScene.getId();

                            List<DecomposedShotGroupVO> groupsToProcess = scVO.getShotGroups();
                            if (groupsToProcess == null || groupsToProcess.isEmpty()) {
                                if (scVO.getShots() != null && !scVO.getShots().isEmpty()) {
                                    DecomposedShotGroupVO defaultG = new DecomposedShotGroupVO();
                                    defaultG.setGroupNo(1);
                                    defaultG.setName(dramaScene.getName() + " - 连续动作组 1");
                                    defaultG.setPurpose("连续动作与对白单元");
                                    defaultG.setShots(scVO.getShots());
                                    groupsToProcess = List.of(defaultG);
                                }
                            }

                            if (groupsToProcess != null && !groupsToProcess.isEmpty()) {
                                int totalShotIndex = 0;
                                for (int gIdx = 0; gIdx < groupsToProcess.size(); gIdx++) {
                                    DecomposedShotGroupVO gVO = groupsToProcess.get(gIdx);
                                    int gNo = gVO.getGroupNo() != null ? gVO.getGroupNo() : (gIdx + 1);

                                    DramaShotGroup dramaGroup = new DramaShotGroup();
                                    dramaGroup.setDramaId(dramaId);
                                    dramaGroup.setEpisodeId(episodeId);
                                    dramaGroup.setSceneId(sceneId);
                                    dramaGroup.setGroupNo(gNo);
                                    dramaGroup.setName(StringUtils.defaultIfBlank(gVO.getName(), String.format("镜头组 %02d", gNo)));
                                    dramaGroup.setPurpose(gVO.getPurpose());
                                    dramaGroup.setSortOrder(gIdx + 1);
                                    shotGroupMapper.insert(dramaGroup);
                                    Long groupId = dramaGroup.getId();

                                    if (gVO.getShots() != null) {
                                        for (int shotIdx = 0; shotIdx < gVO.getShots().size(); shotIdx++) {
                                            totalShotIndex++;
                                            DecomposedShotVO shotVO = gVO.getShots().get(shotIdx);
                                            int shotNo = shotVO.getShotNo() != null ? shotVO.getShotNo() : totalShotIndex;
                                            BigDecimal duration = shotVO.getDuration() != null
                                                    ? BigDecimal.valueOf(shotVO.getDuration()) : BigDecimal.valueOf(5.0);
                                            episodeActualDuration = episodeActualDuration.add(duration);

                                            String charRefsJson = buildCharacterRefsJson(shotVO.getCharacterNames(), characterNameToId, characterNameToLookId);
                                            String propRefsJson = buildPropRefsJson(shotVO.getPropIds(), propRefToId, commitDTO.getProps());

                                            Long shotResSceneId = matchedResSceneId;
                                            if (StringUtils.isNotBlank(shotVO.getSceneId())) {
                                                Long directId = sceneNameToId.get(shotVO.getSceneId().trim());
                                                if (directId != null) shotResSceneId = directId;
                                            }
                                            if (shotResSceneId == null) {
                                                shotResSceneId = shotVO.getResSceneId();
                                            }

                                            DramaShot shot = new DramaShot();
                                            shot.setDramaId(dramaId);
                                            shot.setEpisodeId(episodeId);
                                            shot.setSceneId(sceneId);
                                            shot.setShotGroupId(groupId);
                                            shot.setShotNo(shotNo);
                                            shot.setShotName(StringUtils.defaultIfBlank(shotVO.getShotName(), String.format("S%02d-%02d", epNo, shotNo)));
                                            shot.setShotType(StringUtils.trimToNull(shotVO.getShotType()));
                                            shot.setCameraMovement(StringUtils.trimToNull(shotVO.getCameraMovement()));
                                            shot.setShotTypeLocked(isExplicitCameraConstraint(shotVO.getShotTypeLocked(), shotVO.getShotType()));
                                            shot.setCameraMovementLocked(isExplicitCameraConstraint(shotVO.getCameraMovementLocked(), shotVO.getCameraMovement()));
                                            shot.setDuration(duration);
                                            shot.setScriptContent(shotVO.getScriptContent());
                                            shot.setActionDescription(shotVO.getActionDescription());
                                            shot.setDialogueSpeaker(shotVO.getDialogueSpeaker());
                                            shot.setDialogue(shotVO.getDialogue());
                                            shot.setVoiceover(shotVO.getVoiceover());
                                            shot.setSoundEffect(shotVO.getSoundEffect());
                                            shot.setResSceneId(shotResSceneId);
                                            shot.setCharacterRefsJson(charRefsJson);
                                            shot.setPropRefsJson(propRefsJson);
                                            shot.setPrompt(shotVO.getPrompt());
                                            shot.setFirstFramePrompt(shotVO.getFirstFramePrompt());
                                            shot.setEndFramePrompt(shotVO.getEndFramePrompt());
                                            shot.setNegativePrompt(shotVO.getNegativePrompt());
                                            shot.setVideoPrompt(shotVO.getVideoPrompt());
                                            shot.setStylePreset(commitDTO.getStylePreset());
                                            shot.setGenerationMode("REFERENCE_MODE");
                                            shot.setRenderStatus("INIT");
                                            shot.setSortOrder(shotIdx + 1);

                                            shotMapper.insert(shot);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    DramaEpisode epUpdate = new DramaEpisode();
                    epUpdate.setId(episodeId);
                    epUpdate.setActualDuration(episodeActualDuration);
                    episodeMapper.updateById(epUpdate);
                }
            }
        }

        // 5. 动态同步更新 Drama 的目标集数 (targetEpisodes)
        Integer maxEpisodeNo = episodeMapper.selectMaxEpisodeNoByDramaId(dramaId);
        if (maxEpisodeNo != null && maxEpisodeNo > 0) {
            Drama dramaUpdate = new Drama();
            dramaUpdate.setId(dramaId);
            dramaUpdate.setTargetEpisodes(maxEpisodeNo);
            dramaMapper.updateById(dramaUpdate);
        }

        log.info("[ScriptDecomposeCommit] 剧本拆解数据入库完成: dramaId={}, commitMode={}, 角色映射数={}, 场景映射数={}",
                dramaId, commitMode, characterNameToId.size(), sceneNameToId.size());
        return dramaId;
    }

    static String buildWorkerCharacterRegistry(String existingRegistry, List<DecomposedCharacterVO> characters) {
        StringBuilder prompt = new StringBuilder();
        if (StringUtils.isNotBlank(existingRegistry) && !existingRegistry.contains("无已有角色")) {
            prompt.append(existingRegistry).append("\n");
        }
        prompt.append("【本章节登场人物清单 (请在分镜的 characterIds 字段中严格填写对应人物姓名)】:\n");
        if (characters != null) {
            for (DecomposedCharacterVO character : characters) {
                if (character == null) continue;
                String name = StringUtils.firstNonBlank(character.getCanonicalName(), character.getName());
                prompt.append(String.format("- %s (%s, %s)\n",
                        name,
                        StringUtils.defaultIfBlank(character.getRoleType(), "角色"),
                        StringUtils.defaultIfBlank(character.getGender(), "未知性别")));
            }
        }
        return prompt.toString();
    }

    private Set<Long> validPlannerCharacterIds(Long dramaId) {
        if (dramaId == null) return Set.of();
        List<CharacterRegistryItemVO> registry = characterRegistryService.buildRegistryContext(dramaId);
        if (registry == null || registry.isEmpty()) return Set.of();
        Set<Long> ids = new LinkedHashSet<>();
        for (CharacterRegistryItemVO item : registry) {
            if (item != null && item.getId() != null) ids.add(item.getId());
        }
        return ids;
    }

    private String buildPlannerSceneRegistryPrompt(Long dramaId) {
        LambdaQueryWrapper<ResScene> query = new LambdaQueryWrapper<ResScene>()
                .eq(ResScene::getStatus, 1)
                .orderByAsc(ResScene::getSortOrder)
                .orderByAsc(ResScene::getId);
        if (dramaId != null) {
            query.and(w -> w.eq(ResScene::getDramaId, dramaId).or().eq(ResScene::getDramaId, 0L));
        } else {
            query.eq(ResScene::getDramaId, 0L);
        }

        List<ResScene> scenes = resSceneMapper.selectList(query);
        if (scenes == null || scenes.isEmpty()) {
            return "当前短剧没有可复用的已登记场景。";
        }

        StringBuilder prompt = new StringBuilder();
        for (ResScene scene : scenes) {
            if (scene == null || scene.getId() == null || StringUtils.isBlank(scene.getName())) continue;
            prompt.append("- 数据库场景ID: ").append(scene.getId())
                    .append(" | 归属: ").append(Objects.equals(scene.getDramaId(), 0L) ? "公共场景" : "当前短剧")
                    .append(" | 名称: ").append(scene.getName().trim());
            if (StringUtils.isNotBlank(scene.getDescription())) {
                prompt.append(" | 描述: ").append(scene.getDescription().trim());
            }
            if (StringUtils.isNotBlank(scene.getSceneType())) {
                prompt.append(" | 空间类型: ").append(scene.getSceneType());
            }
            if (StringUtils.isNotBlank(scene.getTimeOfDay())) {
                prompt.append(" | 时间时段: ").append(scene.getTimeOfDay());
            }
            if (StringUtils.isNotBlank(scene.getWeatherAtmosphere())) {
                prompt.append(" | 天气氛围: ").append(scene.getWeatherAtmosphere());
            }
            prompt.append('\n');
        }
        return prompt.length() == 0 ? "当前短剧没有可复用的已登记场景。" : prompt.toString().trim();
    }

    static void validatePlannerCharacterReferences(List<DecomposedCharacterVO> characters,
                                                  Set<Long> validCharacterIds) {
        if (characters == null || characters.isEmpty()) return;
        Set<Long> validIds = validCharacterIds == null ? Set.of() : validCharacterIds;
        for (DecomposedCharacterVO character : characters) {
            if (character == null) continue;
            Long matchedId = character.getMatchedCharacterId();
            Long existingId = character.getExistingCharacterId();
            if (IdentityStatus.UNRESOLVED.name().equalsIgnoreCase(character.getIdentityStatus())
                    && (matchedId != null || existingId != null)) {
                throw new BizException(400, "Planner 标记角色身份未决时不能同时绑定已有角色 ID: "
                        + StringUtils.firstNonBlank(character.getName(), character.getDisplayName()));
            }
            if (matchedId != null && existingId != null && !matchedId.equals(existingId)) {
                throw new BizException(400, "Planner 返回的角色 ID 字段不一致: matchedCharacterId="
                        + matchedId + ", existingCharacterId=" + existingId);
            }
            validateCharacterId(matchedId, validIds);
            validateCharacterId(existingId, validIds);
            if (character.getCandidateCharacterIds() != null) {
                for (Long candidateId : character.getCandidateCharacterIds()) {
                    validateCharacterId(candidateId, validIds);
                }
            }
        }
    }

    private static void validateCharacterId(Long characterId, Set<Long> validIds) {
        if (characterId != null && !validIds.contains(characterId)) {
            throw new BizException(400, "Planner 返回了当前项目不存在或未绑定的角色 ID: " + characterId);
        }
    }

    private List<DecomposedSceneVO> deduplicateAndAlignScenes(List<DecomposedSceneVO> scenes, Long dramaId) {
        if (scenes == null || scenes.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, DecomposedSceneVO> uniqueMap = new LinkedHashMap<>();
        int scNo = 1;
        for (DecomposedSceneVO s : scenes) {
            if (StringUtils.isNotBlank(s.getSceneName())) {
                if (StringUtils.isBlank(s.getId())) {
                    s.setId(String.format("SC%03d", scNo));
                }
                uniqueMap.putIfAbsent(s.getSceneName().trim(), s);
                scNo++;
            }
        }
        List<DecomposedSceneVO> result = new ArrayList<>(uniqueMap.values());
        for (DecomposedSceneVO sceneVO : result) {
            if (sceneVO.getExistingSceneId() != null) {
                ResScene selected = requireExistingSceneReference(sceneVO.getExistingSceneId(), dramaId);
                if (StringUtils.isBlank(sceneVO.getDescription()) && StringUtils.isNotBlank(selected.getDescription())) {
                    sceneVO.setDescription(selected.getDescription());
                }
                continue;
            }
            try {
                LambdaQueryWrapper<ResScene> query = new LambdaQueryWrapper<ResScene>()
                        .eq(ResScene::getStatus, 1)
                        .eq(ResScene::getName, sceneVO.getSceneName().trim());
                if (dramaId != null) {
                    query.and(w -> w.eq(ResScene::getDramaId, dramaId).or().eq(ResScene::getDramaId, 0L));
                } else {
                    query.eq(ResScene::getDramaId, 0L);
                }
                ResScene existing = resSceneMapper.selectOne(query.last("LIMIT 1"));
                if (existing != null) {
                    sceneVO.setExistingSceneId(existing.getId());
                    if (StringUtils.isBlank(sceneVO.getDescription()) && StringUtils.isNotBlank(existing.getDescription())) {
                        sceneVO.setDescription(existing.getDescription());
                    }
                }
            } catch (Exception e) {
                log.debug("[AlignScenes] 匹配场景异常: {}", e.getMessage());
            }
        }
        return result;
    }

    private ResScene requireExistingSceneReference(Long sceneId, Long dramaId) {
        ResScene scene = resSceneMapper.selectById(sceneId);
        boolean belongsToDrama = scene != null && dramaId != null
                && Objects.equals(scene.getDramaId(), dramaId);
        boolean isSharedScene = scene != null && Objects.equals(scene.getDramaId(), 0L);
        if (scene == null || !Objects.equals(scene.getStatus(), 1) || (!belongsToDrama && !isSharedScene)) {
            throw new BizException(400, "Planner 返回的场景 ID 不存在或不属于当前短剧/公共场景: " + sceneId);
        }
        return scene;
    }

    private List<DecomposedPropVO> deduplicateAndAlignProps(List<DecomposedPropVO> props, Long dramaId) {
        if (props == null || props.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, DecomposedPropVO> uniqueMap = new LinkedHashMap<>();
        int prNo = 1;
        for (DecomposedPropVO p : props) {
            if (StringUtils.isNotBlank(p.getName())) {
                if (StringUtils.isBlank(p.getId())) {
                    p.setId(String.format("PR%03d", prNo));
                }
                uniqueMap.putIfAbsent(p.getName().trim(), p);
                prNo++;
            }
        }
        List<DecomposedPropVO> result = new ArrayList<>(uniqueMap.values());
        for (DecomposedPropVO propVO : result) {
            try {
                if (resPropMapper != null) {
                    ResProp existing = resPropMapper.selectOne(new LambdaQueryWrapper<ResProp>()
                            .eq(ResProp::getName, propVO.getName().trim())
                            .and(dramaId != null, w -> w.eq(ResProp::getDramaId, dramaId).or().eq(ResProp::getDramaId, 0L))
                            .last("LIMIT 1"));
                    if (existing != null) {
                        propVO.setExistingPropId(existing.getId());
                    }
                }
            } catch (Exception e) {
                log.debug("[AlignProps] 匹配道具异常: {}", e.getMessage());
            }
        }
        return result;
    }

    private void prepareSkillRequest(ScriptDecomposeRequestDTO request) {
        if (scriptSkillRuntime == null) {
            throw new BizException(400, "Planner 必须加载 character-disambiguation Skill，但 Skill 运行服务不可用");
        }
        requirePlannerCharacterDisambiguationSkill(request);
        scriptSkillRuntime.prepareRequest(request);
    }

    static void requirePlannerCharacterDisambiguationSkill(ScriptDecomposeRequestDTO request) {
        ScriptSkillPolicy policy = request.getSkillPolicy();
        if (policy == null) {
            policy = new ScriptSkillPolicy();
            request.setSkillPolicy(policy);
        }
        ScriptSkillStagePolicy planner = policy.getPlanner();
        if (planner == null) {
            planner = new ScriptSkillStagePolicy();
            policy.setPlanner(planner);
        }
        LinkedHashSet<String> requiredSkills = new LinkedHashSet<>();
        if (planner.getRequiredSkillNames() != null) {
            for (String skillName : planner.getRequiredSkillNames()) {
                if (StringUtils.isNotBlank(skillName)) requiredSkills.add(skillName.trim());
            }
        }
        if (requiredSkills.stream().noneMatch(name -> "character-disambiguation".equalsIgnoreCase(name))) {
            requiredSkills.add("character-disambiguation");
        }
        planner.setRequiredSkillNames(List.copyOf(requiredSkills));
    }

    private void validateDecomposeRequest(ScriptDecomposeRequestDTO request) {
        if (request == null) {
            throw new BizException("请求参数不能为空");
        }
        if (request.getProviderId() == null) {
            throw new BizException("请选择 AI 提供商");
        }
        if (StringUtils.isBlank(request.getModelCode())) {
            throw new BizException("请选择 AI 模型");
        }
        if (StringUtils.isBlank(request.getRawText())) {
            throw new BizException("请输入待拆解的剧本文本内容");
        }
        if (request.getRawText().length() > 100000) {
            throw new BizException("单次剧本拆解文本长度不能超过 100,000 字（当前 " + request.getRawText().length() + " 字）");
        }
    }

    private void bindNamesToCharacterMaps(DecomposedCharacterVO charVO, Long charId, Long lookId,
                                          Map<String, Long> nameToId, Map<String, Long> nameToLookId) {
        Set<String> allNames = new HashSet<>();
        if (StringUtils.isNotBlank(charVO.getName())) allNames.add(charVO.getName().trim());
        if (StringUtils.isNotBlank(charVO.getCanonicalName())) allNames.add(charVO.getCanonicalName().trim());
        if (StringUtils.isNotBlank(charVO.getDisplayName())) allNames.add(charVO.getDisplayName().trim());
        if (charVO.getAliases() != null) {
            for (String a : charVO.getAliases()) {
                if (StringUtils.isNotBlank(a)) allNames.add(a.trim());
            }
        }

        for (String name : allNames) {
            nameToId.put(name, charId);
            if (lookId != null) {
                nameToLookId.put(name, lookId);
            }
        }
    }

    private void saveCharacterAliases(DecomposedCharacterVO charVO, Long charId, Long dramaId) {
        Set<String> allAliases = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(charVO.getName())) allAliases.add(charVO.getName().trim());
        if (StringUtils.isNotBlank(charVO.getDisplayName())) allAliases.add(charVO.getDisplayName().trim());
        if (StringUtils.isNotBlank(charVO.getCanonicalName())) allAliases.add(charVO.getCanonicalName().trim());
        if (charVO.getAliases() != null) {
            for (String a : charVO.getAliases()) {
                if (StringUtils.isNotBlank(a)) allAliases.add(a.trim());
            }
        }

        for (String alias : allAliases) {
            ResCharacterAliasDTO aliasDTO = new ResCharacterAliasDTO();
            aliasDTO.setDramaId(dramaId);
            aliasDTO.setCharacterId(charId);
            aliasDTO.setAlias(alias);
            aliasDTO.setAliasType(AliasType.NAME.name());
            aliasDTO.setConfidence(BigDecimal.valueOf(charVO.getConfidence() != null ? charVO.getConfidence() : 1.00));
            aliasDTO.setStatus(1);
            characterRegistryService.addAlias(aliasDTO);
        }
    }

    private String buildCharacterRefsJson(List<String> characterNames,
                                          Map<String, Long> nameToId,
                                          Map<String, Long> nameToLookId) {
        if (characterNames == null || characterNames.isEmpty()) {
            return null;
        }
        List<CharacterShotRefDTO> refList = new ArrayList<>();
        Set<Long> addedCharIds = new HashSet<>();

        for (String rawName : characterNames) {
            if (StringUtils.isBlank(rawName)) continue;
            String name = rawName.trim();
            Long charId = nameToId.get(name);
            Long lookId = nameToLookId.get(name);

            if (charId != null && !addedCharIds.contains(charId)) {
                CharacterShotRefDTO ref = new CharacterShotRefDTO();
                ref.setCharacterId(charId);
                ref.setLookId(lookId);
                refList.add(ref);
                addedCharIds.add(charId);
            }
        }
        if (refList.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(refList);
        } catch (Exception e) {
            log.warn("序列化 characterRefsJson 失败", e);
            return null;
        }
    }

    private String buildPropRefsJson(List<String> propIdsOrNames,
                                     Map<String, Long> propRefToId,
                                     List<DecomposedPropVO> props) {
        if (propIdsOrNames == null || propIdsOrNames.isEmpty()) {
            return null;
        }
        List<PropShotRefDTO> refList = new ArrayList<>();
        Map<Long, DecomposedPropVO> propVoMap = new HashMap<>();
        if (props != null) {
            for (DecomposedPropVO p : props) {
                if (p.getName() != null && propRefToId.containsKey(p.getName().trim())) {
                    propVoMap.put(propRefToId.get(p.getName().trim()), p);
                } else if (p.getId() != null && propRefToId.containsKey(p.getId().trim())) {
                    propVoMap.put(propRefToId.get(p.getId().trim()), p);
                }
            }
        }
        Set<Long> addedIds = new HashSet<>();
        for (String ref : propIdsOrNames) {
            if (StringUtils.isBlank(ref)) continue;
            Long propId = propRefToId.get(ref.trim());
            if (propId != null && addedIds.add(propId)) {
                DecomposedPropVO vo = propVoMap.get(propId);
                PropShotRefDTO dto = PropShotRefDTO.builder()
                        .propId(propId)
                        .propName(vo != null ? vo.getName() : ref)
                        .propType(vo != null ? vo.getPropType() : "KEY_PROP")
                        .propPrompt(vo != null ? vo.getPropPrompt() : "")
                        .build();
                refList.add(dto);
            }
        }
        if (refList.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(refList);
        } catch (Exception e) {
            log.warn("序列化 propRefsJson 失败", e);
            return null;
        }
    }


    @Override
    public ScriptDecomposeResultVO retryWorker(Long taskId, WorkerRetryDTO retryDTO) {
        if (taskId == null || retryDTO == null || StringUtils.isBlank(retryDTO.getSegmentId())) {
            throw new BizException("任务 ID 和分段编号不能为空");
        }
        if (aiTaskService == null) {
            throw new BizException("AI 任务持久化服务不可用");
        }

        AiTaskDTO taskDTO = aiTaskService.getTaskById(taskId);
        if (taskDTO == null) {
            throw new BizException(404, "拆解任务记录不存在: " + taskId);
        }

        ScriptDecomposeResultVO currentResult = aiTaskService.getDecomposePreview(taskId);
        if (currentResult == null || currentResult.getSegments() == null) {
            throw new BizException("未能从任务载荷中恢复预览大纲");
        }

        String targetSegId = retryDTO.getSegmentId().trim();
        List<StorySegment> segments = currentResult.getSegments();
        int targetIdx = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (targetSegId.equalsIgnoreCase(segments.get(i).getId())) {
                targetIdx = i;
                break;
            }
        }
        if (targetIdx < 0) {
            throw new BizException("未在任务大纲中找到分段: " + targetSegId);
        }

        StorySegment targetSegment = segments.get(targetIdx);

        // 如果用户提供了修改/脱敏后的小说文本覆盖
        if (StringUtils.isNotBlank(retryDTO.getRawTextOverride())) {
            targetSegment.setRawText(retryDTO.getRawTextOverride().trim());
        }

        // 构造请求上下文
        ScriptDecomposeRequestDTO request = draftStore != null ? draftStore.getRequest(taskId) : null;
        if (request == null && StringUtils.isNotBlank(taskDTO.getInputPayload())) {
            try {
                request = objectMapper.readValue(taskDTO.getInputPayload(), ScriptDecomposeRequestDTO.class);
            } catch (Exception ignored) {}
        }
        if (request == null) throw new BizException("Redis 中的原文草稿已过期，无法重试 Worker");
        if (retryDTO.getProviderIdOverride() != null && retryDTO.getProviderIdOverride() > 0) {
            request.setProviderId(retryDTO.getProviderIdOverride());
        }
        if (StringUtils.isNotBlank(retryDTO.getModelCodeOverride())) {
            request.setModelCode(retryDTO.getModelCodeOverride());
        }
        if (retryDTO.getSkillPolicyOverride() != null) {
            if (request.getSkillPolicy() == null) request.setSkillPolicy(new ScriptSkillPolicy());
            request.getSkillPolicy().setWorker(retryDTO.getSkillPolicyOverride());
        }
        if (scriptSkillRuntime != null && request.getSkillPolicy() != null) {
            scriptSkillRuntime.validateRetry(request, request.getSkillPolicy().getWorker());
        }

        String registryContext = characterRegistryService.formatRegistryForPrompt(request.getDramaId());
        GlobalStoryContext globalContext = GlobalStoryContext.builder()
                .taskId(taskId)
                .dramaId(request.getDramaId())
                .episodeId(request.getEpisodeId())
                .startEpisodeNo(request.getStartEpisodeNo() != null ? request.getStartEpisodeNo() : 1)
                .chapterTitle(request.getChapterTitle())
                .aspectRatio(request.getAspectRatio())
                .stylePreset(request.getStylePreset())
                .pacingPreset(request.getPacingPreset())
                .characters(currentResult.getCharacters())
                .scenes(currentResult.getScenes())
                .props(currentResult.getProps())
                .characterRegistryPromptText(registryContext)
                .build();
        if (currentResult.getSkillEvents() != null) {
            globalContext.getSkillEvents().addAll(currentResult.getSkillEvents());
        }

        if (currentResult.getScenes() != null) {
            StringBuilder sceneSb = new StringBuilder();
            for (DecomposedSceneVO sc : currentResult.getScenes()) {
                sceneSb.append(String.format("- 场景编号: %s | 空间名称: %s | 场景生图描述: %s\n",
                        StringUtils.defaultIfBlank(sc.getId(), "SC001"),
                        sc.getSceneName(),
                        StringUtils.defaultIfBlank(sc.getScenePrompt(), "interior scene")));
            }
            globalContext.setSceneRegistryPromptText(sceneSb.toString());
        }
        if (currentResult.getProps() != null) {
            StringBuilder propSb = new StringBuilder();
            for (DecomposedPropVO pr : currentResult.getProps()) {
                propSb.append(String.format("- 道具编号: %s | 名称: %s | 视觉生图词: %s\n",
                        StringUtils.defaultIfBlank(pr.getId(), "PR001"),
                        pr.getName(),
                        StringUtils.defaultIfBlank(pr.getPropPrompt(), "key prop")));
            }
            globalContext.setPropRegistryPromptText(propSb.toString());
        }

        String prevContext = (targetIdx > 0) ? segments.get(targetIdx - 1).getTitle() : null;
        String nextContext = (targetIdx < segments.size() - 1) ? segments.get(targetIdx + 1).getTitle() : null;

        log.info("[ScriptDecompose] 针对分段 {} 触发单独 Worker 重试 (taskId={})", targetSegId, taskId);

        SegmentShotResult newSegResult = parallelShotGenerationService.retrySingleSegment(
                targetSegment,
                globalContext,
                prevContext,
                nextContext,
                request,
                retryDTO.getCustomInstructions(),
                log::info
        );

        if (!"SUCCESS".equalsIgnoreCase(newSegResult.getStatus())) {
            throw new BizException("分段 " + targetSegId + " 重试失败: " + StringUtils.defaultIfBlank(newSegResult.getErrorMessage(), "AI 未返回有效数据"));
        }

        List<SegmentShotResult> segmentResults = currentResult.getSegmentResults() != null
                ? new ArrayList<>(currentResult.getSegmentResults())
                : new ArrayList<>();

        boolean replaced = false;
        for (int i = 0; i < segmentResults.size(); i++) {
            if (targetSegId.equalsIgnoreCase(segmentResults.get(i).getSegmentId())) {
                segmentResults.set(i, newSegResult);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            segmentResults.add(newSegResult);
        }
        segmentResults.sort(Comparator.comparingInt(r -> r.getSequence() != null ? r.getSequence() : 0));

        PlannerDecomposeResultVO plannerResult = PlannerDecomposeResultVO.builder()
                .dramaTitle(currentResult.getDramaTitle())
                .genre(currentResult.getGenre())
                .synopsis(currentResult.getSynopsis())
                .segments(currentResult.getSegments())
                .characters(currentResult.getCharacters())
                .scenes(currentResult.getScenes())
                .props(currentResult.getProps())
                .build();

        ScriptDecomposeResultVO reMergedResult = shotMergeService.mergeSegmentResults(
                segmentResults, plannerResult, globalContext, log::info
        );
        if (draftStore != null && taskId != null) {
            for (SegmentShotResult segmentResult : segmentResults) draftStore.saveWorker(taskId, segmentResult);
        }
        reMergedResult.setSkillEvents(List.copyOf(globalContext.getSkillEvents()));

        Set<String> registeredNames = new HashSet<>();
        if (currentResult.getCharacters() != null) {
            for (DecomposedCharacterVO c : currentResult.getCharacters()) {
                if (StringUtils.isNotBlank(c.getName())) registeredNames.add(c.getName().trim());
                if (StringUtils.isNotBlank(c.getCanonicalName())) registeredNames.add(c.getCanonicalName().trim());
            }
        }

        List<DecomposedShotVO> allFlattenedShots = new ArrayList<>();
        processScenesAndShots(reMergedResult, registeredNames, currentResult.getScenes(), currentResult.getProps(), request.getStylePreset(), allFlattenedShots, log::info);

        FragmentationStatsVO fragStats = shotMergeService.calculateFragmentationStats(allFlattenedShots);
        reMergedResult.setFragmentationStats(fragStats);
        reMergedResult.setTaskId(taskId);
        reMergedResult.setSegmentResults(segmentResults);

        boolean stillAnyFailed = segmentResults.stream().anyMatch(sr -> "FAILED".equalsIgnoreCase(sr.getStatus()));
        List<String> remainingFailed = segmentResults.stream().filter(sr -> "FAILED".equalsIgnoreCase(sr.getStatus())).map(SegmentShotResult::getSegmentId).toList();

        reMergedResult.setStatus(stillAnyFailed ? "PARTIAL_SUCCESS" : "SUCCESS");
        if (draftStore != null) {
            draftStore.saveWorker(taskId, newSegResult);
            draftStore.saveResult(taskId, reMergedResult);
        }
        String outputJson = draftStore == null ? objectMapper.writeValueAsString(reMergedResult) : null;
        if (stillAnyFailed) {
            aiTaskService.markPartialSuccess(taskId, outputJson, "部分分段失败待补救: " + String.join(", ", remainingFailed));
        } else {
            aiTaskService.markSuccess(taskId, outputJson, 0);
        }

        return reMergedResult;
    }

    /**
     * 聚合镜头引用的关键道具 Prompt
     */
    private String buildPropPromptsForShot(List<String> propIds, List<com.astra.freyja.dto.script.DecomposedPropVO> props) {
        if (propIds == null || propIds.isEmpty() || props == null || props.isEmpty()) {
            return null;
        }
        List<String> matched = new ArrayList<>();
        for (String pRef : propIds) {
            if (StringUtils.isBlank(pRef)) continue;
            String trimmed = pRef.trim();
            for (com.astra.freyja.dto.script.DecomposedPropVO p : props) {
                if (trimmed.equalsIgnoreCase(p.getId()) || trimmed.equalsIgnoreCase(p.getName())) {
                    if (StringUtils.isNotBlank(p.getPropPrompt())) {
                        matched.add(p.getPropPrompt().trim());
                    }
                    break;
                }
            }
        }
        return matched.isEmpty() ? null : String.join(", ", matched);
    }

    private boolean isExplicitCameraConstraint(Boolean locked, String value) {
        return Boolean.TRUE.equals(locked) && StringUtils.isNotBlank(value) && !"AUTO".equalsIgnoreCase(value.trim());
    }
}
