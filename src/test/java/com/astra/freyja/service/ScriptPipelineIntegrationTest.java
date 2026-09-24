package com.astra.freyja.service;

import com.astra.freyja.dao.*;
import com.astra.freyja.dto.script.*;
import com.astra.freyja.entity.*;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 剧本并行分段解析与连续性重构全面集成测试套件。
 */
@ExtendWith(MockitoExtension.class)
class ScriptPipelineIntegrationTest {

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
    private ChapterDecompositionService chapterDecompositionService;
    private ParallelShotGenerationService parallelShotGenerationService;
    private ShotMergeService shotMergeService;
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
        when(scriptSkillRuntime.begin(any(), any(), any(), anyString(), anyString(), any(), anyInt(), any()))
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
    @DisplayName("Test 1: 核心验收用例 (整章 1 次 Planner AI + 1 次 Worker AI，Scene 办公室，G001 镜头组，5~8s 连贯分镜，杜绝 1s 碎镜头)")
    void test1_CoreAcceptanceScenario_ParallelPipeline_NoFragmentedShots() {
        String script = "葛明推门进入办公室，看见杜宁正在收拾桌上的文件。葛明停下脚步，望着她。杜宁察觉到葛明的目光，却没有说话。葛明走到她面前，问她是不是准备离开。杜宁沉默片刻，将手中的文件放在桌上，然后抬头看向葛明。";

        ChatModel mockChatModel = mock(ChatModel.class);
        when(aiModelFactory.getChatModel(1L, "deepseek-chat")).thenReturn(mockChatModel);

        String plannerJsonResponse = """
            {
              "dramaTitle": "离别之誓",
              "genre": "URBAN_ABILITY",
              "synopsis": "葛明推门进入办公室，与正在整理离开文件的杜宁产生关键戏剧交锋",
              "characters": [
                {
                  "name": "葛明",
                  "canonicalName": "葛明",
                  "roleType": "PROTAGONIST",
                  "gender": "MALE",
                  "appearancePrompt": "1man, 30yo, sharp eyes, formal attire",
                  "outfitPrompt": "wearing black tailored suit"
                },
                {
                  "name": "杜宁",
                  "canonicalName": "杜宁",
                  "roleType": "PROTAGONIST",
                  "gender": "FEMALE",
                  "appearancePrompt": "1woman, 26yo, sorrowful expression, elegant",
                  "outfitPrompt": "wearing grey office suit"
                }
              ],
              "scenes": [
                {
                  "sceneName": "办公室",
                  "sceneType": "INDOOR",
                  "timeOfDay": "DAY",
                  "scenePrompt": "quiet modern corporate office, desk with papers"
                }
              ],
              "segments": [
                {
                  "id": "SEG001",
                  "sequence": 1,
                  "startOffset": 0,
                  "endOffset": 100,
                  "title": "进入办公室并对峙",
                  "summary": "葛明进入办公室发现杜宁准备离开",
                  "characterIds": ["葛明", "杜宁"],
                  "locationIds": ["办公室"],
                  "narrativePurpose": "建立空间与情感冲突"
                }
              ]
            }
            """;

        String workerJsonResponse = """
            {
              "segmentId": "SEG001",
              "scenes": [
                {
                  "localId": "SC001",
                  "name": "办公室",
                  "shots": [
                    {
                      "localId": "S001",
                      "groupLocalId": "G001",
                      "groupName": "葛明进入办公室并发现杜宁",
                      "sequence": 1,
                      "duration": 5.5,
                      "scriptContent": "葛明推门进入办公室，神色冷峻，皮鞋踏在木地板上发出沉闷声响。",
                      "action": "葛明推门进入办公室",
                      "characterIds": ["葛明"],
                      "locationId": "办公室",
                      "camera": { "shotSize": "medium", "movement": "push_in" },
                      "continuityHint": {
                        "fromPrevious": false,
                        "importantState": { "position": "office_door", "pose": "standing" }
                      }
                    },
                    {
                      "localId": "S002",
                      "groupLocalId": "G001",
                      "groupName": "葛明进入办公室并发现杜宁",
                      "sequence": 2,
                      "duration": 5.0,
                      "scriptContent": "葛明脚步微微一顿，抬眼发现杜宁正背对着他迅速整理办公桌上的散落文件。",
                      "action": "葛明停下，发现杜宁正在整理桌上的文件",
                      "characterIds": ["葛明", "杜宁"],
                      "locationId": "办公室",
                      "camera": { "shotSize": "medium", "movement": "static" },
                      "continuityHint": {
                        "fromPrevious": true,
                        "importantState": { "position": "office_door", "pose": "standing", "gaze": "du_ning" }
                      }
                    },
                    {
                      "localId": "S003",
                      "groupLocalId": "G001",
                      "groupName": "葛明进入办公室并发现杜宁",
                      "sequence": 3,
                      "duration": 5.0,
                      "scriptContent": "葛明大步走到杜宁面前居高临下开口质问，杜宁神情一滞，默默将文件夹合上扣在桌上。",
                      "action": "葛明走到杜宁面前开口询问，杜宁沉默放下文件",
                      "dialogueSpeaker": "葛明",
                      "dialogue": "你这是准备离开？",
                      "characterIds": ["葛明", "杜宁"],
                      "locationId": "办公室",
                      "camera": { "shotSize": "over_shoulder", "movement": "static" },
                      "continuityHint": {
                        "fromPrevious": true,
                        "importantState": { "position": "desk_front", "pose": "standing", "gaze": "ge_ming" }
                      }
                    }
                  ]
                }
              ]
            }
            """;

        when(mockChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(plannerJsonResponse)))))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(workerJsonResponse)))));

        ScriptDecomposeRequestDTO request = new ScriptDecomposeRequestDTO();
        request.setProviderId(1L);
        request.setModelCode("deepseek-chat");
        request.setRawText(script);

        ScriptDecomposeResultVO result = scriptDecomposeService.decompose(request);

        // 1. 验证 AI 调用次数恰好为 2 次 (1 次 Planner + 1 次 Worker)
        verify(mockChatModel, times(2)).call(any(Prompt.class));

        // 2. 验证 Scene、ShotGroups 与 Shots 结构
        assertNotNull(result);
        assertEquals("离别之誓", result.getDramaTitle());
        assertEquals(2, result.getCharacters().size());
        assertEquals(1, result.getScenes().size());
        assertEquals("办公室", result.getScenes().get(0).getSceneName());

        List<DecomposedEpisodeSceneVO> scenes = result.getEpisodes().get(0).getScenes();
        assertEquals(1, scenes.size());
        assertEquals(1, scenes.get(0).getShotGroups().size());

        DecomposedShotGroupVO g1 = scenes.get(0).getShotGroups().get(0);
        assertEquals(3, g1.getShots().size());

        // 3. 验证无 1s 碎镜头，镜头时长符合标准节奏下限 (>= 5s)，镜头剧本已生成
        for (DecomposedShotVO s : g1.getShots()) {
            assertTrue(s.getDuration() >= 5.0, "镜头时长应大于等于 5 秒，杜绝 1s 碎镜头");
            assertNotNull(s.getScriptContent(), "Worker 拆解必须生成镜头剧本 scriptContent");
        }
    }
}
