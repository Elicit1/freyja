package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.script.*;
import com.astra.freyja.entity.*;

import com.astra.freyja.service.impl.*;
import com.astra.freyja.skill.service.ScriptSkillRuntime;
import com.astra.freyja.skill.tool.LoadSkillToolSession;
import com.astra.freyja.service.prompt.PromptBuilder;
import com.astra.freyja.service.prompt.impl.PromptBuilderImpl;
import org.junit.jupiter.api.BeforeEach;
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

@ExtendWith(MockitoExtension.class)
class ScriptDecomposeServiceTest {

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
    private PromptAssembleService promptAssembleService;

    @Mock
    private CharacterRegistryService characterRegistryService;

    @Mock
    private DramaShotGroupMapper shotGroupMapper;

    @Mock
    private SysConfigService sysConfigService;

    @Mock
    private AiTaskMapper aiTaskMapper;

    private ScriptChunkService scriptChunkService;
    private AiTaskService aiTaskService;
    private AIOutputValidationService validationService;
    private PromptBuilder promptBuilder;
    private ChapterDecompositionService chapterDecompositionService;
    private ParallelShotGenerationService parallelShotGenerationService;
    private ShotMergeService shotMergeService;

    private ObjectMapper objectMapper = new ObjectMapper();
    private ScriptDecomposeServiceImpl scriptDecomposeService;

    @Mock
    private ScriptSkillRuntime scriptSkillRuntime;

    @BeforeEach
    void setUp() {
        validationService = new AIOutputValidationServiceImpl(objectMapper);
        scriptChunkService = new ScriptChunkServiceImpl();
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
    void testDecomposeValidationThrows() {
        ScriptDecomposeRequestDTO req = new ScriptDecomposeRequestDTO();
        assertThrows(BizException.class, () -> scriptDecomposeService.decompose(req));

        req.setProviderId(1L);
        assertThrows(BizException.class, () -> scriptDecomposeService.decompose(req));

        req.setModelCode("deepseek-chat");
        assertThrows(BizException.class, () -> scriptDecomposeService.decompose(req));
    }

    @Test
    void testDecomposeSuccessWithParallelPipeline() {
        ScriptDecomposeRequestDTO req = new ScriptDecomposeRequestDTO();
        req.setProviderId(1L);
        req.setModelCode("deepseek-chat");
        req.setRawText("葛明推门进入办公室，看见杜宁正在收拾桌上的文件。葛明停下脚步，望着她。");

        ChatModel mockChatModel = mock(ChatModel.class);
        when(aiModelFactory.getChatModel(1L, "deepseek-chat")).thenReturn(mockChatModel);

        String plannerJsonResponse = """
            {
              "dramaTitle": "都市风云",
              "genre": "URBAN_ABILITY",
              "synopsis": "葛明与杜宁在办公室展开关键对峙",
              "characters": [
                {
                  "name": "葛明",
                  "canonicalName": "葛明",
                  "roleType": "PROTAGONIST",
                  "gender": "MALE",
                  "appearancePrompt": "1man, 30yo, sharp eyes, black suit",
                  "outfitPrompt": "wearing black business suit"
                },
                {
                  "name": "杜宁",
                  "canonicalName": "杜宁",
                  "roleType": "PROTAGONIST",
                  "gender": "FEMALE",
                  "appearancePrompt": "1woman, 26yo, elegant",
                  "outfitPrompt": "wearing white silk blouse"
                }
              ],
              "scenes": [
                {
                  "sceneName": "现代办公室",
                  "sceneType": "INDOOR",
                  "timeOfDay": "DAY",
                  "scenePrompt": "modern luxury office"
                }
              ],
              "segments": [
                {
                  "id": "SEG001",
                  "sequence": 1,
                  "startOffset": 0,
                  "endOffset": 38,
                  "title": "进入办公室",
                  "summary": "葛明推门进入办公室并发现杜宁",
                  "characterIds": ["葛明", "杜宁"],
                  "locationIds": ["现代办公室"],
                  "narrativePurpose": "建立人物初次会面"
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
                  "name": "现代办公室",
                  "shots": [
                    {
                      "localId": "S001",
                      "groupLocalId": "G001",
                      "groupName": "葛明进入办公室并发现杜宁",
                      "sequence": 1,
                      "duration": 4.5,
                      "scriptContent": "葛明推门进入办公室大步走入房间，神色严肃。",
                      "action": "葛明推门进入办公室大步走入房间",
                      "characterIds": ["葛明"],
                      "locationId": "现代办公室",
                      "camera": {
                        "shotSize": "medium",
                        "movement": "push_in"
                      }
                    },
                    {
                      "localId": "S002",
                      "groupLocalId": "G001",
                      "groupName": "葛明进入办公室并发现杜宁",
                      "sequence": 2,
                      "duration": 4.0,
                      "scriptContent": "葛明停下脚步，望向正在收拾桌上文件的杜宁，目光审视。",
                      "action": "葛明停下脚步，望向正在收拾桌上文件的杜宁",
                      "characterIds": ["葛明", "杜宁"],
                      "locationId": "现代办公室",
                      "camera": {
                        "shotSize": "over_shoulder",
                        "movement": "static"
                      }
                    }
                  ]
                }
              ]
            }
            """;

        ChatResponse plannerResp = new ChatResponse(List.of(new Generation(new AssistantMessage(plannerJsonResponse))));
        ChatResponse workerResp = new ChatResponse(List.of(new Generation(new AssistantMessage(workerJsonResponse))));

        when(mockChatModel.call(any(Prompt.class)))
                .thenReturn(plannerResp)
                .thenReturn(workerResp);

        ScriptDecomposeResultVO result = scriptDecomposeService.decompose(req);

        assertNotNull(result);
        assertEquals("都市风云", result.getDramaTitle());
        assertEquals("URBAN_ABILITY", result.getGenre());
        assertEquals(2, result.getCharacters().size());
        assertEquals(1, result.getScenes().size());
        assertEquals(1, result.getSegments().size());
        assertNotNull(result.getEpisodes());
        assertEquals(1, result.getEpisodes().size());

        DecomposedEpisodeVO ep = result.getEpisodes().get(0);
        assertEquals(1, ep.getScenes().size());
        DecomposedEpisodeSceneVO sc = ep.getScenes().get(0);
        assertEquals("现代办公室", sc.getSceneName());
        assertEquals(1, sc.getShotGroups().size());
        assertEquals(2, sc.getShots().size());

        DecomposedShotVO shot1 = sc.getShots().get(0);
        assertEquals(1, shot1.getShotNo());
        assertEquals("G001", shot1.getGroupId());
        assertEquals("AUTO", shot1.getShotType());
        assertEquals("AUTO", shot1.getCameraMovement());
        assertNotNull(shot1.getScriptContent());

        assertNotNull(result.getFragmentationStats());
        assertEquals(2, result.getFragmentationStats().getTotalShots());
        assertEquals(0, result.getFragmentationStats().getShortShots());
    }

    @Test
    void testCommitSuccess() {
        ScriptDecomposeCommitDTO dto = new ScriptDecomposeCommitDTO();
        dto.setDramaTitle("入库测试短剧");
        dto.setGenre("DOMINANT_CEO");
        dto.setSynopsis("剧情概要");
        dto.setAspectRatio("9:16");
        dto.setStylePreset("cinematic-realism");

        DecomposedCharacterVO char1 = DecomposedCharacterVO.builder()
                .name("张三")
                .canonicalName("张三")
                .roleType("PROTAGONIST")
                .gender("MALE")
                .appearancePrompt("man prompt")
                .outfitPrompt("suit prompt")
                .build();
        dto.setCharacters(List.of(char1));

        DecomposedSceneVO sc1 = DecomposedSceneVO.builder()
                .sceneName("会议室")
                .sceneType("INDOOR")
                .timeOfDay("DAY")
                .scenePrompt("meeting room")
                .build();
        dto.setScenes(List.of(sc1));

        DecomposedShotVO shot1 = DecomposedShotVO.builder()
                .shotNo(1)
                .shotName("S01-01")
                .duration(4.0)
                .actionDescription("张三走入会议室")
                .characterNames(List.of("张三"))
                .prompt("test prompt")
                .build();

        DecomposedShotGroupVO g1 = DecomposedShotGroupVO.builder()
                .groupNo(1)
                .name("会议室开场")
                .purpose("交代环境")
                .shots(List.of(shot1))
                .build();

        DecomposedEpisodeSceneVO epSc = DecomposedEpisodeSceneVO.builder()
                .sceneNo(1)
                .sceneName("会议室")
                .shotGroups(List.of(g1))
                .shots(List.of(shot1))
                .build();

        DecomposedEpisodeVO ep1 = DecomposedEpisodeVO.builder()
                .episodeNo(1)
                .title("第1集")
                .scenes(List.of(epSc))
                .build();
        dto.setEpisodes(List.of(ep1));

        when(dramaMapper.insert(any(Drama.class))).thenAnswer(inv -> {
            Drama d = inv.getArgument(0);
            d.setId(1001L);
            return 1;
        });
        when(episodeMapper.insert(any(DramaEpisode.class))).thenAnswer(inv -> {
            DramaEpisode ep = inv.getArgument(0);
            ep.setId(2001L);
            return 1;
        });
        when(dramaSceneMapper.insert(any(DramaScene.class))).thenAnswer(inv -> {
            DramaScene sc = inv.getArgument(0);
            sc.setId(3001L);
            return 1;
        });
        when(shotGroupMapper.insert(any(DramaShotGroup.class))).thenAnswer(inv -> {
            DramaShotGroup g = inv.getArgument(0);
            g.setId(4001L);
            return 1;
        });

        Long dramaId = scriptDecomposeService.commit(dto);
        assertNotNull(dramaId);
        assertEquals(1001L, dramaId);
        verify(dramaMapper, times(1)).insert(any(Drama.class));
        verify(characterMapper, times(1)).insert(any(ResCharacter.class));
        verify(episodeMapper, times(1)).insert(any(DramaEpisode.class));
        verify(dramaSceneMapper, times(1)).insert(any(DramaScene.class));
        verify(shotGroupMapper, times(1)).insert(any(DramaShotGroup.class));
        verify(shotMapper, times(1)).insert(argThat((DramaShot shot) ->
                shot != null && shot.getShotType() == null && shot.getCameraMovement() == null
                        && Boolean.FALSE.equals(shot.getShotTypeLocked())
                        && Boolean.FALSE.equals(shot.getCameraMovementLocked())));
    }

    @Test
    void testCommitAppendToExistingEpisode() {
        ScriptDecomposeCommitDTO dto = new ScriptDecomposeCommitDTO();
        dto.setDramaId(100L);
        dto.setCommitMode("APPEND_TO_EPISODE");
        dto.setTargetEpisodeNo(1);

        DecomposedShotVO newShot1 = DecomposedShotVO.builder()
                .shotNo(1)
                .shotName("S01-01")
                .shotType("CLOSE_UP")
                .shotTypeLocked(true)
                .duration(5.0)
                .actionDescription("主角眼神冷冽，握紧拳头")
                .characterNames(List.of("张三"))
                .build();

        DecomposedShotGroupVO newGroup = DecomposedShotGroupVO.builder()
                .groupNo(1)
                .name("后续冲突")
                .shots(List.of(newShot1))
                .build();

        DecomposedEpisodeSceneVO newScene = DecomposedEpisodeSceneVO.builder()
                .sceneNo(1)
                .sceneName("会议室外部走廊")
                .shotGroups(List.of(newGroup))
                .shots(List.of(newShot1))
                .build();

        DecomposedEpisodeVO ep1 = DecomposedEpisodeVO.builder()
                .episodeNo(1)
                .scenes(List.of(newScene))
                .build();
        dto.setEpisodes(List.of(ep1));

        DramaEpisode existingEp = new DramaEpisode();
        existingEp.setId(200L);
        existingEp.setDramaId(100L);
        existingEp.setEpisodeNo(1);
        existingEp.setTitle("第1集");

        when(episodeMapper.selectAnyByDramaIdAndEpisodeNo(100L, 1)).thenReturn(existingEp);
        when(dramaSceneMapper.selectMaxSceneNoByEpisodeId(200L)).thenReturn(3);
        when(shotGroupMapper.selectMaxGroupNoByEpisodeId(200L)).thenReturn(2);
        when(shotMapper.selectMaxShotNoByEpisodeId(200L)).thenReturn(10);

        Long dramaId = scriptDecomposeService.commit(dto);
        assertEquals(100L, dramaId);

        // 验证没有删除原有场次
        verify(dramaSceneMapper, never()).delete(any());
        verify(shotMapper, never()).delete(any());

        // 验证新增场次序号顺延为 4 (base 3 + 1)
        verify(dramaSceneMapper, times(1)).insert(argThat((DramaScene sc) -> sc != null && sc.getSceneNo() == 4));

        // 验证新增镜头组序号顺延为 3 (base 2 + 1)
        verify(shotGroupMapper, times(1)).insert(argThat((DramaShotGroup g) -> g != null && g.getGroupNo() == 3));

        // 验证新增镜头编号顺延为 S01-11 (base 10 + 1)
        verify(shotMapper, times(1)).insert(argThat((DramaShot s) -> s != null && s.getShotNo() == 11
                && "S01-11".equals(s.getShotName()) && s.getCameraMovement() == null
                && "CLOSE_UP".equals(s.getShotType()) && Boolean.TRUE.equals(s.getShotTypeLocked())));
    }

    @Test
    void testCommitNewEpisode() {
        ScriptDecomposeCommitDTO dto = new ScriptDecomposeCommitDTO();
        dto.setDramaId(100L);
        dto.setCommitMode("NEW_EPISODE");
        dto.setTargetEpisodeNo(2);

        DecomposedShotVO shot = DecomposedShotVO.builder()
                .shotNo(1)
                .shotName("S02-01")
                .duration(4.0)
                .actionDescription("第二章开场镜头")
                .build();

        DecomposedShotGroupVO group = DecomposedShotGroupVO.builder()
                .groupNo(1)
                .name("第二集开场")
                .shots(List.of(shot))
                .build();

        DecomposedEpisodeSceneVO scene = DecomposedEpisodeSceneVO.builder()
                .sceneNo(1)
                .sceneName("地下车库")
                .shotGroups(List.of(group))
                .shots(List.of(shot))
                .build();

        DecomposedEpisodeVO ep2 = DecomposedEpisodeVO.builder()
                .episodeNo(2)
                .title("第2集")
                .scenes(List.of(scene))
                .build();
        dto.setEpisodes(List.of(ep2));

        when(episodeMapper.selectAnyByDramaIdAndEpisodeNo(100L, 2)).thenReturn(null);
        when(episodeMapper.selectMaxEpisodeNoByDramaId(100L)).thenReturn(2);

        Long dramaId = scriptDecomposeService.commit(dto);
        assertEquals(100L, dramaId);

        // 验证新建了第 2 集
        verify(episodeMapper, times(1)).insert(argThat((DramaEpisode ep) -> ep != null && ep.getEpisodeNo() == 2));
        // 验证更新了短剧的目标总集数
        verify(dramaMapper, times(1)).updateById(argThat((Drama d) -> d != null && d.getTargetEpisodes() == 2));
    }

    @Test
    void testDecomposeWithExistingDramaPreservesDramaInfo() {
        ScriptDecomposeRequestDTO req = new ScriptDecomposeRequestDTO();
        req.setDramaId(100L);
        req.setProviderId(1L);
        req.setModelCode("deepseek-chat");
        req.setRawText("葛明推门进入办公室，看见杜宁正在收拾桌上的文件。葛明停下脚步，望着她。");

        Drama existingDrama = new Drama();
        existingDrama.setId(100L);
        existingDrama.setTitle("既有短剧项目《霸总秘史》");
        existingDrama.setGenre("DOMINANT_CEO");
        existingDrama.setAspectRatio("16:9");
        existingDrama.setStylePreset("3d-animation");
        when(dramaMapper.selectById(100L)).thenReturn(existingDrama);

        ChatModel mockChatModel = mock(ChatModel.class);
        when(aiModelFactory.getChatModel(1L, "deepseek-chat")).thenReturn(mockChatModel);

        String plannerJsonResponse = """
            {
              "dramaTitle": "AI推断的章节标题_战神归来",
              "genre": "URBAN_ABILITY",
              "synopsis": "葛明与杜宁在办公室展开关键对峙",
              "characters": [
                {
                  "name": "葛明",
                  "canonicalName": "葛明",
                  "roleType": "PROTAGONIST",
                  "gender": "MALE"
                }
              ],
              "scenes": [
                {
                  "sceneName": "现代办公室",
                  "sceneType": "INDOOR",
                  "timeOfDay": "DAY"
                }
              ],
              "segments": [
                {
                  "id": "SEG001",
                  "sequence": 1,
                  "startOffset": 0,
                  "endOffset": 35,
                  "title": "进入办公室",
                  "summary": "葛明推门进入办公室"
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
                  "name": "现代办公室",
                  "shots": [
                    {
                      "localId": "S001",
                      "groupLocalId": "G001",
                      "sequence": 1,
                      "duration": 4.5,
                      "action": "葛明推门进入办公室",
                      "characterIds": ["葛明"]
                    }
                  ]
                }
              ]
            }
            """;

        ChatResponse plannerResp = new ChatResponse(List.of(new Generation(new AssistantMessage(plannerJsonResponse))));
        ChatResponse workerResp = new ChatResponse(List.of(new Generation(new AssistantMessage(workerJsonResponse))));

        when(mockChatModel.call(any(Prompt.class)))
                .thenReturn(plannerResp)
                .thenReturn(workerResp);

        ScriptDecomposeResultVO result = scriptDecomposeService.decompose(req);

        assertNotNull(result);
        // 关键断言: 保持既有短剧的名称、题材、画幅与风格设定，不被 Planner AI 覆盖
        assertEquals("既有短剧项目《霸总秘史》", result.getDramaTitle());
        assertEquals("DOMINANT_CEO", result.getGenre());
        assertEquals("16:9", result.getAspectRatio());
        assertEquals("3d-animation", result.getStylePreset());
    }

    @Test
    void testCommitWithExistingDramaDoesNotModifyDramaBasicInfo() {
        ScriptDecomposeCommitDTO dto = new ScriptDecomposeCommitDTO();
        dto.setDramaId(100L);
        dto.setDramaTitle("被AI篡改的标题");
        dto.setGenre("WAR_GOD");
        dto.setAspectRatio("1:1");
        dto.setStylePreset("anime");
        dto.setCommitMode("APPEND_TO_EPISODE");
        dto.setTargetEpisodeNo(1);

        DecomposedShotVO shot = DecomposedShotVO.builder()
                .shotNo(1)
                .shotName("S01-02")
                .duration(4.0)
                .actionDescription("测试镜头")
                .build();

        DecomposedShotGroupVO group = DecomposedShotGroupVO.builder()
                .groupNo(1)
                .name("测试组")
                .shots(List.of(shot))
                .build();

        DecomposedEpisodeSceneVO scene = DecomposedEpisodeSceneVO.builder()
                .sceneNo(1)
                .sceneName("测试场次")
                .shotGroups(List.of(group))
                .shots(List.of(shot))
                .build();

        DecomposedEpisodeVO ep = DecomposedEpisodeVO.builder()
                .episodeNo(1)
                .scenes(List.of(scene))
                .build();
        dto.setEpisodes(List.of(ep));

        DramaEpisode existingEp = new DramaEpisode();
        existingEp.setId(200L);
        existingEp.setDramaId(100L);
        existingEp.setEpisodeNo(1);
        existingEp.setTitle("第1集");

        when(episodeMapper.selectAnyByDramaIdAndEpisodeNo(100L, 1)).thenReturn(existingEp);
        when(dramaSceneMapper.selectMaxSceneNoByEpisodeId(200L)).thenReturn(1);
        when(shotGroupMapper.selectMaxGroupNoByEpisodeId(200L)).thenReturn(1);
        when(shotMapper.selectMaxShotNoByEpisodeId(200L)).thenReturn(1);

        Long dramaId = scriptDecomposeService.commit(dto);
        assertEquals(100L, dramaId);

        // 验证没有插入新短剧
        verify(dramaMapper, never()).insert(any(Drama.class));
        // 验证绝不更新短剧的 title 或 genre 等基础信息
        verify(dramaMapper, never()).updateById(argThat((Drama d) ->
                d != null && ("被AI篡改的标题".equals(d.getTitle()) || "WAR_GOD".equals(d.getGenre()))
        ));
    }
}
