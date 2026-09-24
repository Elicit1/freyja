package com.astra.freyja.service;

import com.astra.freyja.dao.*;
import com.astra.freyja.dto.script.*;
import com.astra.freyja.service.impl.*;
import com.astra.freyja.skill.service.ScriptSkillRuntime;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import com.astra.freyja.service.prompt.PromptBuilder;
import com.astra.freyja.service.prompt.impl.PromptBuilderImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 短剧小说自动分镜系统——并行分段解析架构全面专项测试套件。
 */
@ExtendWith(MockitoExtension.class)
class ChapterParallelDecomposeTest {

    @Mock
    private AiModelFactory aiModelFactory;

    @Mock
    private ResCharacterMapper characterMapper;

    @Mock
    private ResCharacterOutfitMapper outfitMapper;

    @Mock
    private ResSceneMapper resSceneMapper;

    @Mock
    private ResPropMapper resPropMapper;

    @Mock
    private DramaMapper dramaMapper;

    @Mock
    private DramaEpisodeMapper episodeMapper;

    @Mock
    private DramaSceneMapper dramaSceneMapper;

    @Mock
    private DramaShotMapper shotMapper;

    @Mock
    private DramaShotGroupMapper shotGroupMapper;

    @Mock
    private PromptAssembleService promptAssembleService;

    @Mock
    private CharacterRegistryService characterRegistryService;

    @Mock
    private SysConfigService sysConfigService;

    @Mock
    private AiTaskMapper aiTaskMapper;

    private ObjectMapper objectMapper = new ObjectMapper();

    private AiTaskService aiTaskService;
    private AIOutputValidationService validationService;
    private PromptBuilder promptBuilder;
    private ChapterDecompositionServiceImpl chapterDecompositionService;
    private ParallelShotGenerationServiceImpl parallelShotGenerationService;
    private ShotMergeServiceImpl shotMergeService;
    private ScriptDecomposeServiceImpl scriptDecomposeService;

    @Mock
    private ScriptSkillRuntime scriptSkillRuntime;

    @BeforeEach
    void setUp() {
        validationService = new AIOutputValidationServiceImpl(objectMapper);
        aiTaskService = new AiTaskServiceImpl(aiTaskMapper);

        promptBuilder = new PromptBuilderImpl(sysConfigService);

        chapterDecompositionService = new ChapterDecompositionServiceImpl(
                aiModelFactory,
                sysConfigService,
                validationService,
                aiTaskService,
                objectMapper
        );

        parallelShotGenerationService = new ParallelShotGenerationServiceImpl(
                aiModelFactory,
                sysConfigService,
                validationService,
                aiTaskService,
                objectMapper
        );

        shotMergeService = new ShotMergeServiceImpl();

        scriptDecomposeService = new ScriptDecomposeServiceImpl(
                chapterDecompositionService,
                parallelShotGenerationService,
                shotMergeService,
                characterRegistryService,
                promptBuilder,
                characterMapper,
                outfitMapper,
                resSceneMapper,
                resPropMapper,
                dramaMapper,
                episodeMapper,
                dramaSceneMapper,
                shotGroupMapper,
                shotMapper,
                promptAssembleService,
                objectMapper
        );
        ReflectionTestUtils.setField(scriptDecomposeService, "scriptSkillRuntime", scriptSkillRuntime);
        ReflectionTestUtils.setField(chapterDecompositionService, "scriptSkillRuntime", scriptSkillRuntime);
        lenient().when(scriptSkillRuntime.begin(any(), any(), any(), anyString(), anyString(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    String systemPrompt = invocation.getArgument(3);
                    String stageName = invocation.getArgument(4);
                    return new ScriptSkillRuntime.Invocation(systemPrompt + "\nloaded-character-disambiguation",
                            null, new LoadSkillToolSession(java.util.Map.of("character-disambiguation", 1L)),
                            null, stageName, null, 0);
                });

        lenient().when(sysConfigService.getConfigValue(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(characterRegistryService.formatRegistryForPrompt(any())).thenReturn("【无已有角色】");
    }

    @Test
    @DisplayName("测试 1: Planner 仅调用 1 次 AI，生成合法 StorySegment 且 Java 边界自愈修正（无遗漏、无缝隙）")
    void testPlannerBoundaryValidationAndAutoHealing() {
        String novelText = "第一段：葛明进入办公室。第二段：葛明和杜宁激烈争吵。第三段：杜宁夺门而出。";
        int totalLen = novelText.length();

        // 模拟 Planner 返回存在缝隙和重叠的 Segment
        PlannerDecomposeResultVO plannerResult = PlannerDecomposeResultVO.builder()
                .dramaTitle("对峙风暴")
                .genre("DOMINANT_CEO")
                .segments(new ArrayList<>(List.of(
                        StorySegment.builder().id("SEG001").sequence(1).startOffset(2).endOffset(10).title("进门").build(),
                        StorySegment.builder().id("SEG002").sequence(2).startOffset(15).endOffset(25).title("争吵").build(),
                        StorySegment.builder().id("SEG003").sequence(3).startOffset(20).endOffset(35).title("离开").build()
                )))
                .build();

        chapterDecompositionService.validateAndFixSegments(plannerResult, novelText, System.out::println);

        List<StorySegment> fixed = plannerResult.getSegments();
        assertEquals(3, fixed.size());

        // 验证首段起点被修正为 0
        assertEquals(0, fixed.get(0).getStartOffset());

        // 验证连续性：SEG001.end == SEG002.start, SEG002.end == SEG003.start
        assertEquals(fixed.get(0).getEndOffset(), fixed.get(1).getStartOffset());
        assertEquals(fixed.get(1).getEndOffset(), fixed.get(2).getStartOffset());

        // 验证尾段终点覆盖全文长度
        assertEquals(totalLen, fixed.get(2).getEndOffset());

        // 验证切片文本非空
        for (StorySegment s : fixed) {
            assertNotNull(s.getRawText());
            assertFalse(s.getRawText().isEmpty());
        }
    }

    @Test
    @DisplayName("测试 1.2: Planner 基于起止文本锚点 (startSnippet/endSnippet) 智能定位与精准切片")
    void testPlannerSnippetAnchorLocating() {
        String novelText = "苏氏集团顶层会议室，气氛剑拔弩张。苏清雪冷冷看着众人。\n"
                + "暴雨倾盆的酒店门外，沈漫漫被淋得浑身湿透，紧紧抱着病历单。\n"
                + "午夜一点的迷雾码头，集装箱投下阴森黑影。陆远握紧对讲机，等待行动。";

        PlannerDecomposeResultVO plannerResult = PlannerDecomposeResultVO.builder()
                .dramaTitle("迷雾交锋")
                .genre("SUSPENSE")
                .segments(new ArrayList<>(List.of(
                        StorySegment.builder()
                                .id("SEG001")
                                .sequence(1)
                                .startSnippet("苏氏集团顶层会议室")
                                .endSnippet("冷冷看着众人。")
                                .title("会议室交锋")
                                .build(),
                        StorySegment.builder()
                                .id("SEG002")
                                .sequence(2)
                                .startSnippet("暴雨倾盆的酒店门外")
                                .endSnippet("紧紧抱着病历单。")
                                .title("雨中求助")
                                .build(),
                        StorySegment.builder()
                                .id("SEG003")
                                .sequence(3)
                                .startSnippet("午夜一点的迷雾码头")
                                .endSnippet("等待行动。")
                                .title("码头埋伏")
                                .build()
                )))
                .build();

        chapterDecompositionService.validateAndFixSegments(plannerResult, novelText, System.out::println);

        List<StorySegment> fixed = plannerResult.getSegments();
        assertEquals(3, fixed.size());

        // 验证段落切片精准包含对应的完整句子
        assertTrue(fixed.get(0).getRawText().contains("苏氏集团顶层会议室"));
        assertTrue(fixed.get(0).getRawText().contains("冷冷看着众人"));

        assertTrue(fixed.get(1).getRawText().contains("暴雨倾盆的酒店门外"));
        assertTrue(fixed.get(1).getRawText().contains("紧紧抱着病历单"));

        assertTrue(fixed.get(2).getRawText().contains("午夜一点的迷雾码头"));
        assertTrue(fixed.get(2).getRawText().contains("等待行动"));
    }

    @Test
    @DisplayName("测试 2: Worker 并行生成与 Semaphore 并发控制 + 隔离重试机制")
    void testWorkerParallelExecutionAndIsolatedRetry() {
        List<StorySegment> segments = List.of(
                StorySegment.builder().id("SEG001").sequence(1).startOffset(0).endOffset(100).title("分段1").rawText("剧情1").build(),
                StorySegment.builder().id("SEG002").sequence(2).startOffset(100).endOffset(200).title("分段2").rawText("剧情2").build(),
                StorySegment.builder().id("SEG003").sequence(3).startOffset(200).endOffset(300).title("分段3").rawText("剧情3").build()
        );

        GlobalStoryContext globalCtx = GlobalStoryContext.builder()
                .dramaTitle("测试短剧")
                .genre("URBAN")
                .build();

        ScriptDecomposeRequestDTO req = new ScriptDecomposeRequestDTO();
        req.setProviderId(1L);
        req.setModelCode("deepseek-chat");

        ChatModel mockChatModel = mock(ChatModel.class);
        when(aiModelFactory.getChatModel(1L, "deepseek-chat")).thenReturn(mockChatModel);

        String workerJsonTemplate = """
            {
              "segmentId": "%s",
              "scenes": [
                {
                  "localId": "SC001",
                  "name": "测试场景",
                  "shots": [
                    {
                      "localId": "S001",
                      "groupLocalId": "G001",
                      "sequence": 1,
                      "duration": 5.0,
                      "action": "人物行动",
                      "characterIds": ["char1"],
                      "locationId": "loc1",
                      "camera": { "shotSize": "medium", "movement": "static" },
                      "continuityHint": { "fromPrevious": false }
                    }
                  ]
                }
              ]
            }
            """;

        // 模拟对每个 segment 返回合法 JSON
        when(mockChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            String promptStr = prompt.getContents();
            assertTrue(promptStr.contains("时长可行性"));
            assertTrue(promptStr.contains("camera-direction"));
            assertTrue(promptStr.contains("原文摄影要求（用户明确指定）"));
            assertTrue(promptStr.contains("道具资产种类"));
            assertTrue(promptStr.contains("`duration`"));
            String segId = "SEG001";
            if (promptStr.contains("SEG002")) segId = "SEG002";
            else if (promptStr.contains("SEG003")) segId = "SEG003";

            String json = String.format(workerJsonTemplate, segId);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        });

        List<SegmentShotResult> results = parallelShotGenerationService.generateShotsInParallel(
                segments, globalCtx, req, System.out::println, null
        );

        assertEquals(3, results.size());
        assertEquals("SEG001", results.get(0).getSegmentId());
        assertEquals("SEG002", results.get(1).getSegmentId());
        assertEquals("SEG003", results.get(2).getSegmentId());
        assertTrue(results.stream().allMatch(r -> "SUCCESS".equalsIgnoreCase(r.getStatus())));
    }

    @Test
    @DisplayName("测试 3: ShotMergeService 严格按 sequence 排序、全局 ID 与序号重新编排")
    void testShotMergeServiceOrderingAndRenumbering() {
        PlannerDecomposeResultVO plannerResult = PlannerDecomposeResultVO.builder()
                .dramaTitle("合并测试")
                .genre("DOMINANT_CEO")
                .synopsis("概要")
                .segments(List.of(
                        StorySegment.builder().id("SEG001").sequence(1).build(),
                        StorySegment.builder().id("SEG002").sequence(2).build()
                ))
                .props(List.of(
                        DecomposedPropVO.builder().id("PR001").name("古董座钟").propType("KEY_PROP").propPrompt("antique desk clock").build()
                ))
                .build();

        // 乱序输入：先放 SEG002，再放 SEG001
        SegmentShotResult res2 = SegmentShotResult.builder()
                .segmentId("SEG002")
                .sequence(2)
                .status("SUCCESS")
                .shotResult(WorkerShotResult.builder()
                        .segmentId("SEG002")
                        .scenes(List.of(WorkerShotResult.WorkerSceneVO.builder()
                                .name("场景B")
                                .shots(List.of(WorkerShotResult.WorkerShotVO.builder()
                                        .localId("S001")
                                        .groupLocalId("G001")
                                        .duration(5.0)
                                        .scriptContent("在场景B中行动剧本描述")
                                        .action("在场景B中行动")
                                        .build()))
                                .build()))
                        .build())
                .build();

        SegmentShotResult res1 = SegmentShotResult.builder()
                .segmentId("SEG001")
                .sequence(1)
                .status("SUCCESS")
                .shotResult(WorkerShotResult.builder()
                        .segmentId("SEG001")
                        .scenes(List.of(WorkerShotResult.WorkerSceneVO.builder()
                                .name("场景A")
                                .shots(List.of(
                                        WorkerShotResult.WorkerShotVO.builder().localId("S001").groupLocalId("G001").duration(4.0).scriptContent("剧本A1").action("A1").build(),
                                        WorkerShotResult.WorkerShotVO.builder().localId("S002").groupLocalId("G001").duration(6.0).scriptContent("剧本A2").action("A2").build()
                                ))
                                .build()))
                        .build())
                .build();

        ScriptDecomposeResultVO merged = shotMergeService.mergeSegmentResults(
                List.of(res2, res1), plannerResult, null, System.out::println
        );

        assertNotNull(merged);
        DecomposedEpisodeVO ep = merged.getEpisodes().get(0);
        assertEquals(2, ep.getScenes().size());

        // 验证合并后顺序为 SEG001 场景A在前，SEG002 场景B在后
        assertEquals("场景A", ep.getScenes().get(0).getSceneName());
        assertEquals("场景B", ep.getScenes().get(1).getSceneName());

        // 验证全局镜头编号严格单调递增 1, 2, 3
        assertEquals(1, ep.getScenes().get(0).getShots().get(0).getShotNo());
        assertEquals(2, ep.getScenes().get(0).getShots().get(1).getShotNo());
        assertEquals(3, ep.getScenes().get(1).getShots().get(0).getShotNo());

        // 验证镜头剧本 scriptContent 正确传递
        assertEquals("剧本A1", ep.getScenes().get(0).getShots().get(0).getScriptContent());
        assertEquals("剧本A2", ep.getScenes().get(0).getShots().get(1).getScriptContent());
        assertEquals("在场景B中行动剧本描述", ep.getScenes().get(1).getShots().get(0).getScriptContent());

        // 验证 props 正确传递到 mergedResult
        assertNotNull(merged.getProps());
        assertEquals(1, merged.getProps().size());
        assertEquals("古董座钟", merged.getProps().get(0).getName());
    }

    @Test
    @DisplayName("测试 4: ShotDurationRule 与碎片率统计 (短镜头 > 30% 触发 HIGH_FRAGMENTATION 告警)")
    void testShotDurationRuleAndFragmentationStats() {
        List<DecomposedShotVO> shots = List.of(
                DecomposedShotVO.builder().shotNo(1).shotName("S01-01").duration(2.0).actionDescription("葛明拿笔").shotType("MEDIUM_SHOT").build(),
                DecomposedShotVO.builder().shotNo(2).shotName("S01-02").duration(2.0).actionDescription("葛明签字").shotType("MEDIUM_SHOT").build(),
                DecomposedShotVO.builder().shotNo(3).shotName("S01-03").duration(5.0).actionDescription("葛明站起身与杜宁对话").shotType("MEDIUM_SHOT").build()
        );

        FragmentationStatsVO stats = shotMergeService.calculateFragmentationStats(shots);

        assertEquals(3, stats.getTotalShots());
        assertEquals(2, stats.getShortShots());
        assertEquals(1, stats.getNormalShots());
        assertTrue(stats.getShortShotRatio() > 0.60);
        assertTrue(stats.getIsHighFragmentation());
        assertNotNull(stats.getWarningMessage());
    }

    @Test
    @DisplayName("测试 5: 全链路端到端集成测试 (1 次 Planner + 2 次 Worker 并行 + Java Merge + Continuity + PromptBuilder)")
    void testEndToEndParallelPipeline() {
        ScriptDecomposeRequestDTO req = new ScriptDecomposeRequestDTO();
        req.setProviderId(1L);
        req.setModelCode("deepseek-chat");
        req.setRawText("葛明进入办公室发现杜宁。两人发生争吵。杜宁摔门离开。");

        ChatModel mockChatModel = mock(ChatModel.class);
        when(aiModelFactory.getChatModel(1L, "deepseek-chat")).thenReturn(mockChatModel);

        AtomicInteger callCount = new AtomicInteger(0);
        when(mockChatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            int c = callCount.incrementAndGet();
            if (c == 1) {
                // Planner AI 调用 (仅分段)
                String plannerJson = """
                    {
                      "dramaTitle": "决裂时刻",
                      "genre": "URBAN_ABILITY",
                      "synopsis": "葛明与杜宁决裂",
                      "characters": [
                        { "name": "葛明", "roleType": "PROTAGONIST", "gender": "MALE", "canonicalName": "葛明" },
                        { "name": "杜宁", "roleType": "ANTAGONIST", "gender": "FEMALE", "canonicalName": "杜宁" }
                      ],
                      "scenes": [
                        { "sceneName": "总裁办公室", "sceneType": "INDOOR", "timeOfDay": "DAY", "scenePrompt": "corporate office" }
                      ],
                      "segments": [
                        { "id": "SEG001", "sequence": 1, "startOffset": 0, "endOffset": 15, "title": "进门", "summary": "葛明进门发现杜宁", "characterIds": ["葛明"] },
                        { "id": "SEG002", "sequence": 2, "startOffset": 15, "endOffset": 27, "title": "决裂", "summary": "杜宁离开", "characterIds": ["葛明", "杜宁"] }
                      ]
                    }
                    """;
                return new ChatResponse(List.of(new Generation(new AssistantMessage(plannerJson))));
            } else {
                // Worker AI 调用
                String workerJson = """
                    {
                      "segmentId": "SEG",
                      "scenes": [
                        {
                          "localId": "SC001",
                          "name": "总裁办公室",
                          "shots": [
                            {
                              "localId": "S001",
                              "groupLocalId": "G001",
                              "sequence": 1,
                              "duration": 5.0,
                              "scriptContent": "葛明站在办公室中神色凝重，推开门环视四周。",
                              "action": "葛明站在办公室中神色凝重",
                              "characterIds": ["葛明"],
                              "locationId": "总裁办公室",
                              "camera": { "shotSize": "medium", "movement": "push_in" },
                              "continuityHint": { "fromPrevious": false }
                            }
                          ]
                        }
                      ]
                    }
                    """;
                return new ChatResponse(List.of(new Generation(new AssistantMessage(workerJson))));
            }
        });

        ScriptDecomposeResultVO result = scriptDecomposeService.decompose(req);

        assertNotNull(result);
        assertEquals("决裂时刻", result.getDramaTitle());
        assertEquals(2, result.getSegments().size());

        // 验证 AI 调用次数恰好为 1 (Planner) + 2 (Workers) = 3 次
        assertEquals(3, callCount.get());

        // 验证镜头生成与 Prompt 组装
        DecomposedEpisodeVO ep = result.getEpisodes().get(0);
        assertNotNull(ep);
        assertTrue(ep.getScenes().size() >= 1);

        for (DecomposedEpisodeSceneVO sc : ep.getScenes()) {
            for (DecomposedShotVO s : sc.getShots()) {
                assertTrue(s.getDuration() >= 5.0);
                assertEquals("葛明站在办公室中神色凝重，推开门环视四周。", s.getScriptContent());
            }
        }
    }
}
