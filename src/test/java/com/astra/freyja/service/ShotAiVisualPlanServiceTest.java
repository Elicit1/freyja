package com.astra.freyja.service;

import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.CharacterShotRefInfoVO;
import com.astra.freyja.dto.drama.PropShotRefInfoVO;
import com.astra.freyja.dto.drama.ShotPromptDeriveDTO;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.impl.ShotAiVisualPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShotAiVisualPlanServiceTest {

    @Mock
    private DramaShotMapper shotMapper;
    @Mock
    private DramaMapper dramaMapper;
    @Mock
    private DramaEpisodeMapper dramaEpisodeMapper;
    @Mock
    private DramaSceneMapper sceneMapper;
    @Mock
    private ResSceneMapper resSceneMapper;
    @Mock
    private ResCharacterMapper characterMapper;
    @Mock
    private ResCharacterOutfitMapper outfitMapper;
    @Mock
    private ResPropMapper propMapper;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private AiModelFactory aiModelFactory;
    @Mock
    private AiProviderMapper providerMapper;
    @Mock
    private AiModelMapper modelMapper;
    @Mock
    private tools.jackson.databind.ObjectMapper objectMapper;
    @Mock
    private com.astra.freyja.director.service.DirectorPlanningService directorPlanningService;
    @Mock
    private com.astra.freyja.director.service.DirectorPlanMergeService directorPlanMergeService;
    @Mock
    private CharacterVisualAssetResolver characterVisualAssetResolver;

    @InjectMocks
    private ShotAiVisualPlanServiceImpl shotAiVisualPlanService;

    @Mock
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        CharacterVisualAssetResolver realResolver = new com.astra.freyja.service.impl.CharacterVisualAssetResolverImpl(characterMapper, outfitMapper);
        when(characterVisualAssetResolver.resolvePromptContext(any(CharacterShotRefInfoVO.class)))
                .thenAnswer(inv -> realResolver.resolvePromptContext(inv.getArgument(0)));
        when(characterVisualAssetResolver.resolvePromptContext(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> realResolver.resolvePromptContext(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                        inv.getArgument(4), inv.getArgument(5), inv.getArgument(6), inv.getArgument(7)
                ));

        when(aiModelFactory.getChatModel(anyLong(), anyString())).thenReturn(chatModel);
        when(sysConfigService.getConfigValue(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        AiProvider provider = new AiProvider();
        provider.setId(1L);
        provider.setStatus(1);
        when(providerMapper.selectById(anyLong())).thenReturn(provider);

        AiModel model = new AiModel();
        model.setId(10L);
        model.setProviderId(1L);
        model.setModelCode("deepseek-chat");
        model.setModelType("CHAT");
        model.setStatus(1);
        when(modelMapper.selectOne(any())).thenReturn(model);
    }

    private CountDownLatch mockChatModelResponse(String jsonContent) {
        CountDownLatch latch = new CountDownLatch(1);
        Generation gen = new Generation(new AssistantMessage(jsonContent));
        ChatResponse resp = new ChatResponse(List.of(gen));
        when(chatModel.stream(any(Prompt.class))).thenAnswer(inv -> {
            latch.countDown();
            return Flux.just(resp);
        });
        return latch;
    }

    @Test
    void testBuildPromptContextWithAllFieldsAndScriptWeight() throws Exception {
        // 准备短剧、剧集、场景、角色、服装、道具
        Drama drama = new Drama();
        drama.setId(100L);
        drama.setTitle("天命剑主");
        drama.setStylePreset("东方玄幻");
        drama.setStyleTone("电影级冷调青灰光影");
        when(dramaMapper.selectById(100L)).thenReturn(drama);

        DramaEpisode episode = new DramaEpisode();
        episode.setId(200L);
        episode.setSummary("第一集：宗门惊变");
        episode.setScriptContent("【原著整集小说长文本】：当年林家惨遭灭门，山崩地裂……此处为数万字的小说原文。");
        when(dramaEpisodeMapper.selectById(200L)).thenReturn(episode);

        ResScene resScene = new ResScene();
        resScene.setId(300L);
        resScene.setName("宗门断魂崖");
        resScene.setScenePrompt("misty ancient cliff, desolate dark rocks");
        when(resSceneMapper.selectById(300L)).thenReturn(resScene);

        ResCharacter character = new ResCharacter();
        character.setId(400L);
        character.setName("林远");
        character.setCanonicalName("林远");
        character.setRoleType("PROTAGONIST");
        character.setAppearanceDesc("剑眉星目，左眼角有一道浅浅剑痕");
        when(characterMapper.selectById(400L)).thenReturn(character);

        ResCharacterOutfit outfit = new ResCharacterOutfit();
        outfit.setId(500L);
        outfit.setCharacterId(400L);
        outfit.setStatus(1);
        outfit.setOutfitPrompt("tattered dark blue martial robe with silver linings");
        when(outfitMapper.selectById(500L)).thenReturn(outfit);

        ResProp prop = new ResProp();
        prop.setId(600L);
        prop.setName("青霜残剑");
        prop.setPropType("WEAPON");
        prop.setPropPrompt("ancient weathered jade sword, glowing faint frost azure light");
        when(propMapper.selectById(600L)).thenReturn(prop);

        // 构造 DTO，具备本镜头独立的 scriptContent
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .dramaId(100L)
                .episodeId(200L)
                .resSceneId(300L)
                .shotNo(3)
                .shotName("S01E01-003")
                .shotType("CLOSE_UP")
                .shotTypeLocked(true)
                .cameraMovement("PUSH_IN")
                .cameraMovementLocked(true)
                .duration(4.5)
                .scriptContent("林远拔出残剑，剑锋微颤，冷冽寒芒倒映在双眸之中。")
                .actionDescription("手腕转动拔剑，剑尖直指前方")
                .dialogue("宗门欠我的，今日一并奉还。")
                .dialogueSpeaker("林远")
                .voiceover("十年隐忍，终于等到这刻。")
                .soundEffect("残剑出鞘的清脆龙吟声，伴随山风呼啸")
                .userInstruction("突出瞳孔内的剑光反光，眼神必须杀气逼人，浅景深虚化背景悬崖")
                .generationMode("FIRST_LAST_FRAME")
                .providerId(1L)
                .modelCode("deepseek-chat")
                .characterRefs(List.of(
                        CharacterShotRefInfoVO.builder()
                                .characterId(400L)
                                .lookId(500L)
                                .actionPrompt("左手握鞘，右手猛然拔出残剑")
                                .emotionPrompt("杀气毕露，嘴角泛起冷笑")
                                .positionTag("中景居中特写")
                                .build()
                ))
                .propRefs(List.of(
                        PropShotRefInfoVO.builder()
                                .propId(600L)
                                .build()
                ))
                .build();

        String fakeAiResult = """
                {
                  "prompt": "Slow cinematic push in on Lin Yuan drawing the ancient jade sword, cold frost light gleaming.",
                  "firstFramePrompt": "Cinematic close-up, Lin Yuan gripping the scabbard, eyes cold and resolute, tattered blue robe.",
                  "endFramePrompt": "The frost blade fully drawn, pointing forward with sharp killing intent in eyes.",
                  "videoPrompt": "Slow cinematic push in on Lin Yuan drawing the ancient jade sword, cold frost light gleaming.",
                  "negativePrompt": "extra limbs, bad anatomy, deformed fingers, blur, watermark",
                  "focusTarget": "林远",
                  "compositionNote": "特写构图，逆光冷调，剑锋高光微反光"
                }
                """;
        CountDownLatch latch = mockChatModelResponse(fakeAiResult);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);

        SseEmitter emitter = shotAiVisualPlanService.derivePromptStream(dto);
        assertNotNull(emitter);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        verify(chatModel).stream(promptCaptor.capture());
        Prompt capturedPrompt = promptCaptor.getValue();
        assertNotNull(capturedPrompt);

        String systemText = capturedPrompt.getInstructions().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.SystemMessage)
                .findFirst().orElseThrow().getText();

        String userText = capturedPrompt.getInstructions().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.UserMessage)
                .findFirst().orElseThrow().getText();

        // 验证系统提示词：符合 MiniMax H3 FL2VA 官方规范
        assertTrue(systemText.contains("MiniMax H3 FL2VA"));
        assertTrue(systemText.contains("integrated_multimodal_description"));
        assertTrue(systemText.contains("overall_soundscape"));
        assertTrue(systemText.contains("non_diegetic_music"));

        // 验证用户提示词上下文
        assertTrue(userText.contains("林远拔出残剑，剑锋微颤"));
        assertTrue(userText.contains("- 对白说话人: 林远"));
        assertTrue(userText.contains("- 台词对白: 宗门欠我的，今日一并奉还。"));
        assertTrue(userText.contains("- 旁白内心独白: 十年隐忍，终于等到这刻。"));
        assertTrue(userText.contains("- 音效与环境声: 残剑出鞘的清脆龙吟声，伴随山风呼啸"));
        assertTrue(userText.contains("* 本镜情绪与微表情: 杀气毕露，嘴角泛起冷笑"));
        assertTrue(userText.contains("【创作者特别指令与补充要求】: 突出瞳孔内的剑光反光"));
        assertTrue(userText.contains("宗门断魂崖"));
        assertTrue(userText.contains("青霜残剑"));

        // 核心事实：当本镜有 scriptContent 时，全集小说原文被屏蔽
        assertFalse(userText.contains("【原著整集小说长文本】"), "本镜有剧本时，不应附加全集小说长文本");
    }

    @Test
    void testEpisodeScriptFallbackWhenScriptContentBlank() throws Exception {
        DramaEpisode episode = new DramaEpisode();
        episode.setId(201L);
        episode.setSummary("剧情简介");
        episode.setScriptContent("这是全集小说的弱参考文本");
        when(dramaEpisodeMapper.selectById(201L)).thenReturn(episode);

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .episodeId(201L)
                .scriptContent(null)
                .actionDescription("主角在路上行走")
                .providerId(1L)
                .modelCode("deepseek-chat")
                .generationMode("FIRST_LAST_FRAME")
                .build();

        String fakeAiResult = """
                {
                  "prompt": "Lin Yuan walks down the path.",
                  "firstFramePrompt": "Lin Yuan starts walking.",
                  "endFramePrompt": "Lin Yuan reaches the gate.",
                  "videoPrompt": "Lin Yuan walks down the path.",
                  "negativePrompt": "blurry",
                  "focusTarget": "林远",
                  "compositionNote": "中景"
                }
                """;
        CountDownLatch latch = mockChatModelResponse(fakeAiResult);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);

        SseEmitter emitter = shotAiVisualPlanService.derivePromptStream(dto);
        assertNotNull(emitter);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        verify(chatModel).stream(promptCaptor.capture());
        String userText = promptCaptor.getValue().getInstructions().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.UserMessage)
                .findFirst().orElseThrow().getText();

        assertTrue(userText.contains("【剧情原文参考 (Raw Novel / Script Context)】:"));
        assertTrue(userText.contains("这是全集小说的弱参考文本"));
    }

    @Test
    void testReferenceModeNullifiesFirstAndEndFrames() throws Exception {
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .scriptContent("角色在雨中奔跑")
                .providerId(1L)
                .modelCode("deepseek-chat")
                .generationMode("REFERENCE_MODE")
                .build();

        // 即使 AI 误返回了首尾帧
        String fakeAiResult = """
                {
                  "prompt": "Camera tracks protagonist running through heavy rain, water splashing.",
                  "firstFramePrompt": "SHOULD_BE_NULLIFIED",
                  "endFramePrompt": "SHOULD_BE_NULLIFIED",
                  "videoPrompt": "Camera tracks protagonist running through heavy rain, water splashing.",
                  "negativePrompt": "jitter, distortion",
                  "focusTarget": "主角",
                  "compositionNote": "跟焦推镜"
                }
                """;
        CountDownLatch latch = mockChatModelResponse(fakeAiResult);

        SseEmitter emitter = shotAiVisualPlanService.derivePromptStream(dto);
        assertNotNull(emitter);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
    }

    @Test
    void testFirstLastFrameModeMissingFirstFrameFailsGracefully() throws Exception {
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .scriptContent("角色在雨中奔跑")
                .providerId(1L)
                .modelCode("deepseek-chat")
                .generationMode("FIRST_LAST_FRAME")
                .build();

        // 缺少 firstFramePrompt
        String fakeAiResult = """
                {
                  "prompt": "Camera tracks protagonist running through heavy rain, water splashing.",
                  "firstFramePrompt": "",
                  "endFramePrompt": "End frame",
                  "videoPrompt": "Camera tracks protagonist running through heavy rain, water splashing.",
                  "negativePrompt": "jitter, distortion"
                }
                """;
        CountDownLatch latch = mockChatModelResponse(fakeAiResult);

        SseEmitter emitter = shotAiVisualPlanService.derivePromptStream(dto);
        assertNotNull(emitter);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
    }

    @Test
    void testReferenceModeMissingPromptFailsGracefully() throws Exception {
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .scriptContent("角色在雨中奔跑")
                .providerId(1L)
                .modelCode("deepseek-chat")
                .generationMode("REFERENCE_MODE")
                .build();

        // 缺少 prompt 与 videoPrompt
        String fakeAiResult = """
                {
                  "prompt": "",
                  "videoPrompt": "",
                  "negativePrompt": "jitter, distortion"
                }
                """;
        CountDownLatch latch = mockChatModelResponse(fakeAiResult);

        SseEmitter emitter = shotAiVisualPlanService.derivePromptStream(dto);
        assertNotNull(emitter);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
    }

    @Test
    void testDerivePromptStreamRejectsMissingProviderOrModel() throws Exception {
        ShotPromptDeriveDTO dtoWithoutProvider = ShotPromptDeriveDTO.builder()
                .scriptContent("角色在雨中奔跑")
                .modelCode("deepseek-chat")
                .build();
        SseEmitter emitter1 = shotAiVisualPlanService.derivePromptStream(dtoWithoutProvider);
        assertNotNull(emitter1);

        ShotPromptDeriveDTO dtoWithoutModel = ShotPromptDeriveDTO.builder()
                .scriptContent("角色在雨中奔跑")
                .providerId(1L)
                .build();
        SseEmitter emitter2 = shotAiVisualPlanService.derivePromptStream(dtoWithoutModel);
        assertNotNull(emitter2);
    }

    @Test
    void testDerivePromptStreamWithoutShotTypeAndCameraMovement() throws Exception {
        Drama drama = new Drama();
        drama.setId(100L);
        drama.setTitle("太古神王");
        when(dramaMapper.selectById(100L)).thenReturn(drama);

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .dramaId(100L)
                .shotNo(1)
                .shotName("S01-01")
                .shotType(null)
                .cameraMovement(null)
                .duration(3.0)
                .scriptContent("主角凝视远方苍茫群山，微风吹拂长袍。")
                .providerId(1L)
                .modelCode("deepseek-chat")
                .generationMode("FIRST_LAST_FRAME")
                .build();

        String fakeAiResult = """
                {
                  "prompt": "Cinematic shot of protagonist looking into distant misty mountains, robes fluttering in gentle breeze.",
                  "firstFramePrompt": "A solitary traveler standing on a cliff edge overlooking endless misty mountains at dawn.",
                  "endFramePrompt": "The traveler slowly turns around as wind sweeps through the high peak.",
                  "videoPrompt": "The camera gently glides forward as mist swirls over the mountain ridge.",
                  "negativePrompt": "blurry, low quality, bad anatomy",
                  "focusTarget": "主角",
                  "compositionNote": "自然全景构图，群山层叠"
                }
                """;
        CountDownLatch latch = mockChatModelResponse(fakeAiResult);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);

        SseEmitter emitter = shotAiVisualPlanService.derivePromptStream(dto);
        assertNotNull(emitter);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        verify(chatModel).stream(promptCaptor.capture());
        Prompt capturedPrompt = promptCaptor.getValue();
        assertNotNull(capturedPrompt);

        String userText = capturedPrompt.getInstructions().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.UserMessage)
                .findFirst().orElseThrow().getText();

        // 核心验证：当未填写景别与运镜时，AI 提示词上下文中绝不出现硬编码的景别与运镜条款
        assertFalse(userText.contains("- 景别:"), "未指定景别时不应向 AI 提示词注入景别字段");
        assertFalse(userText.contains("- 运镜:"), "未指定运镜时不应向 AI 提示词注入运镜字段");
        assertFalse(userText.contains("MEDIUM_SHOT"), "未指定景别时不应兜底注入 MEDIUM_SHOT");
        assertFalse(userText.contains("STATIC"), "未指定运镜时不应兜底注入 STATIC");
        assertTrue(userText.contains("主角凝视远方苍茫群山"));
    }

    @Test
    void testGenerateThrowsExceptionWhenModelMissing() {
        DramaShot shot = new DramaShot();
        shot.setId(999L);
        when(shotMapper.selectById(999L)).thenReturn(shot);

        com.astra.freyja.dto.drama.ShotAiVisualPlanRequestDTO req = new com.astra.freyja.dto.drama.ShotAiVisualPlanRequestDTO();
        assertThrows(com.astra.freyja.common.BizException.class, () -> shotAiVisualPlanService.generate(999L, req));

        req.setProviderId(1L);
        assertThrows(com.astra.freyja.common.BizException.class, () -> shotAiVisualPlanService.generate(999L, req));
    }
}
