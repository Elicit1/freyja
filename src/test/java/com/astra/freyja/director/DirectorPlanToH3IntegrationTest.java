package com.astra.freyja.director;

import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dao.AiProviderMapper;
import com.astra.freyja.dao.DramaEpisodeMapper;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.DramaSceneMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dao.ResCharacterOutfitMapper;
import com.astra.freyja.dao.ResPropMapper;
import com.astra.freyja.dao.ResSceneMapper;
import com.astra.freyja.director.model.AppliedSkillRef;
import com.astra.freyja.director.model.CameraBeat;
import com.astra.freyja.director.model.DirectorPlan;
import com.astra.freyja.director.service.DirectorPlanMergeService;
import com.astra.freyja.director.service.DirectorPlanningService;
import com.astra.freyja.dto.drama.ShotPromptDeriveDTO;
import com.astra.freyja.dto.drama.ShotPromptPackageVO;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.service.AIOutputValidationService;
import com.astra.freyja.service.AiModelFactory;
import com.astra.freyja.service.SysConfigService;
import com.astra.freyja.service.impl.ShotAiVisualPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DirectorPlanToH3IntegrationTest {

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
    private com.astra.freyja.dao.ResCharacterLookMapper lookMapper;
    @Mock
    private ResCharacterOutfitMapper outfitMapper;
    @Mock
    private ResPropMapper propMapper;
    @Mock
    private AiProviderMapper providerMapper;
    @Mock
    private AiModelMapper modelMapper;
    @Mock
    private AiModelFactory aiModelFactory;
    @Mock
    private AIOutputValidationService validationService;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private DirectorPlanningService directorPlanningService;
    @Mock
    private DirectorPlanMergeService directorPlanMergeService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ShotAiVisualPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShotAiVisualPlanServiceImpl(
                shotMapper,
                dramaMapper,
                dramaEpisodeMapper,
                sceneMapper,
                resSceneMapper,
                characterMapper,
                outfitMapper,
                propMapper,
                providerMapper,
                modelMapper,
                aiModelFactory,
                validationService,
                sysConfigService,
                objectMapper,
                directorPlanningService,
                directorPlanMergeService,
                new com.astra.freyja.service.impl.CharacterVisualAssetResolverImpl(characterMapper, outfitMapper)
        );
    }

    @Test
    @DisplayName("FL2VA 模式下将多 Beat DirectorPlan 注入 Prompt 任务包并遵循最高执行优先级约束")
    void testBuildPromptPackage_WithMultiBeatDirectorPlan() {
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_system"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_user"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        Drama drama = new Drama();
        drama.setId(100L);
        drama.setTitle("都市潜龙");
        drama.setStylePreset("影视级写实");
        when(dramaMapper.selectById(100L)).thenReturn(drama);

        DirectorPlan plan = DirectorPlan.builder()
                .duration(new BigDecimal("4.00"))
                .shotSize("MEDIUM_CLOSE_UP")
                .cameraAngle("EYE_LEVEL")
                .narrativeIntent("先确立角色冷峻对峙状态，随后快速推入眼部特写以放大压迫感")
                .subjectAction("林峰静止凝视，瞳孔微缩")
                .gaze("平视正前方，眼神冰冷聚焦")
                .cameraBeats(List.of(
                        CameraBeat.builder()
                                .startSec(new BigDecimal("0.00"))
                                .endSec(new BigDecimal("2.00"))
                                .movement("STATIC")
                                .direction(null)
                                .speed("NONE")
                                .startCue("镜头定格林峰半身")
                                .stopCue("眼神微动")
                                .narrativePurpose("建立冷峻对峙态势")
                                .build(),
                        CameraBeat.builder()
                                .startSec(new BigDecimal("2.00"))
                                .endSec(new BigDecimal("4.00"))
                                .movement("PUSH_IN")
                                .direction("FORWARD")
                                .speed("NORMAL")
                                .startCue("对峙第2秒起步")
                                .stopCue("推进至面部特写定格")
                                .narrativePurpose("放大压迫感与决意")
                                .build()
                ))
                .skills(List.of(
                        AppliedSkillRef.builder().name("cinematography").version("1.0.0").build()
                ))
                .build();

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .dramaId(100L)
                .shotNo(3)
                .generationMode("FIRST_LAST_FRAME")
                .scriptContent("林峰立于雨中，神色冰冷地注视着前方的黑衣人。")
                .actionDescription("静止对峙后眼神骤然收紧")
                .duration(4.0)
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto, plan);

        assertNotNull(pkg);
        assertEquals("FIRST_LAST_FRAME", pkg.getGenerationMode());

        // 验证系统提示词包含忠实翻译原则
        assertTrue(pkg.getSystemPrompt().contains("videoPrompt 必须是完整的 MiniMax H3 FL2VA 正文"));

        // 验证用户任务包含格式化后的 DirectorPlan 时间轴及 Beats 约束
        String userPrompt = pkg.getUserPrompt();
        assertTrue(userPrompt.contains("【结构化导演机位与运镜设计 (DIRECTOR_PLAN - 最高执行优先级)】"));
        assertTrue(userPrompt.contains("MEDIUM_CLOSE_UP"));
        assertTrue(userPrompt.contains("EYE_LEVEL"));
        assertTrue(userPrompt.contains("林峰静止凝视，瞳孔微缩"));
        assertTrue(userPrompt.contains("[0.00s - 2.00s] 运镜: STATIC"));
        assertTrue(userPrompt.contains("[2.00s - 4.00s] 运镜: PUSH_IN"));
        assertTrue(userPrompt.contains("放大压迫感与决意"));
        assertTrue(userPrompt.contains("提示词中涉及的机位角度、运镜动作、景别变化必须与上述 Beats 严格对应，禁止随意添加或更改。"));
    }

    @Test
    @DisplayName("Ref2VA 模式下无 DirectorPlan 时，提示词生成安全降级不崩溃")
    void testBuildPromptPackage_NullDirectorPlan_GracefulFallback() {
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_ref2va_system"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_ref2va_user"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .generationMode("REFERENCE_MODE")
                .scriptContent("李逍遥御剑飞行，穿过云霄。")
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto, null);

        assertNotNull(pkg);
        assertEquals("REFERENCE_MODE", pkg.getGenerationMode());
        assertFalse(pkg.getUserPrompt().contains("【结构化导演机位与运镜设计"));
    }

    @Test
    @DisplayName("未锁定的历史摄影字段不进入 H3 约束，缺少运镜不表示 STATIC")
    void testUnconfirmedShotFieldsAreNotPassedAsCameraLocks() {
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_system"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_user"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .generationMode("FIRST_LAST_FRAME")
                .shotType("MEDIUM_SHOT")
                .cameraMovement("STATIC")
                .shotTypeLocked(false)
                .cameraMovementLocked(false)
                .scriptContent("两人擦肩而过，原文摄影要求（用户明确指定）：镜头轻微横摇。")
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto, null);

        assertTrue(pkg.getUserPrompt().contains("景别】: 未指定，由 Prompt AI 自主决定"));
        assertTrue(pkg.getUserPrompt().contains("运镜】: 未指定，由 Prompt AI 自主决定"));
        assertFalse(pkg.getUserPrompt().contains("创作者明确锁定运镜: STATIC"));
        assertTrue(pkg.getUserPrompt().contains("镜头轻微横摇"));
        assertTrue(pkg.getSystemPrompt().contains("缺少约束不等于 STATIC"));
    }

    @Test
    @DisplayName("未携带新锁定标记的旧请求从已保存分镜恢复明确摄影约束")
    void testStoredCreatorLocksAreRecoveredForLegacyPromptRequests() {
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_system"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_user"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        DramaShot existing = new DramaShot();
        existing.setId(700L);
        existing.setShotType("CLOSE_UP");
        existing.setShotTypeLocked(true);
        existing.setCameraMovement("STATIC");
        existing.setCameraMovementLocked(true);
        existing.setScriptContent("人物停下。原文摄影要求（用户明确指定）：固定镜头。");
        when(shotMapper.selectById(700L)).thenReturn(existing);

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .shotId(700L)
                .generationMode("FIRST_LAST_FRAME")
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto, null);

        assertTrue(pkg.getUserPrompt().contains("【创作者明确锁定景别】: CLOSE_UP"));
        assertTrue(pkg.getUserPrompt().contains("【创作者明确锁定运镜】: STATIC"));
        assertTrue(pkg.getUserPrompt().contains("原文摄影要求（用户明确指定）：固定镜头"));
    }

    @Test
    @DisplayName("H3 请求忠实保留导演规划的轻微横摇，不改写为固定机位")
    void testAiPlannedPanSurvivesIntoFinalH3Prompt() {
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_system"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_user"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        DirectorPlan plan = DirectorPlan.builder()
                .duration(new BigDecimal("5.0"))
                .shotSize("MEDIUM_SHOT")
                .cameraAngle("EYE_LEVEL")
                .cameraBeats(List.of(CameraBeat.builder()
                        .startSec(BigDecimal.ZERO)
                        .endSec(new BigDecimal("5.0"))
                        .movement("PAN_RIGHT")
                        .direction("RIGHT")
                        .speed("SLOW")
                        .narrativePurpose("轻微横摇跟随两人擦肩")
                        .build()))
                .build();
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .generationMode("FIRST_LAST_FRAME")
                .scriptContent("两人擦肩而过")
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto, plan);

        assertTrue(pkg.getUserPrompt().contains("运镜: PAN_RIGHT"));
        assertTrue(pkg.getUserPrompt().contains("轻微横摇跟随两人擦肩"));
        assertFalse(pkg.getUserPrompt().contains("运镜: STATIC"));
    }

    @Test
    @DisplayName("H3 最终提示词保留 AI 自主选择的跟拍和剧情道具数量")
    void testAiPlannedTrackingAndPropContinuityReachH3Prompt() {
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_system"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_user"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        DirectorPlan plan = DirectorPlan.builder()
                .duration(new BigDecimal("5.0"))
                .shotSize("MEDIUM_SHOT")
                .cameraAngle("EYE_LEVEL")
                .cameraBeats(List.of(CameraBeat.builder()
                        .startSec(BigDecimal.ZERO)
                        .endSec(new BigDecimal("5.0"))
                        .movement("TRACKING")
                        .direction("FORWARD")
                        .speed("SLOW")
                        .narrativePurpose("跟随两人擦肩并保持人物关系清晰")
                        .build()))
                .build();
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .generationMode("FIRST_LAST_FRAME")
                .scriptContent("雷姆左手提一个购物袋、右手提一个购物袋，共两个。菜月昴持有一部手机，两人擦肩而过。")
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto, plan);

        assertTrue(pkg.getUserPrompt().contains("运镜: TRACKING"));
        assertTrue(pkg.getUserPrompt().contains("雷姆左手提一个购物袋、右手提一个购物袋，共两个"));
        assertTrue(pkg.getUserPrompt().contains("菜月昴持有一部手机"));
        assertFalse(pkg.getUserPrompt().contains("运镜: AUTO"));
        assertFalse(pkg.getUserPrompt().contains("运镜: STATIC"));
    }

    @Test
    @DisplayName("H3 最终提示词保留 AI 自主选择的 STATIC 固定镜头")
    void testAiPlannedStaticReachesH3Prompt() {
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_system"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_user"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        DirectorPlan plan = DirectorPlan.builder()
                .duration(new BigDecimal("4.0"))
                .shotSize("CLOSE_UP")
                .cameraAngle("EYE_LEVEL")
                .cameraBeats(List.of(CameraBeat.builder()
                        .startSec(BigDecimal.ZERO)
                        .endSec(new BigDecimal("4.0"))
                        .movement("STATIC")
                        .speed("NONE")
                        .narrativePurpose("让人物停顿与目光变化完整呈现")
                        .build()))
                .build();
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .generationMode("FIRST_LAST_FRAME")
                .scriptContent("人物停下并注视前方。")
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto, plan);

        assertTrue(pkg.getUserPrompt().contains("运镜: STATIC"));
        assertTrue(pkg.getUserPrompt().contains("shotSize): CLOSE_UP"));
        assertFalse(pkg.getUserPrompt().contains("运镜: AUTO"));
    }

    @Test
    @DisplayName("创作者明确锁定 STATIC 时 H3 收到固定机位约束")
    void testExplicitStaticLockReachesH3Prompt() {
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_system"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_user"), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .generationMode("FIRST_LAST_FRAME")
                .shotType("CLOSE_UP")
                .shotTypeLocked(true)
                .cameraMovement("STATIC")
                .cameraMovementLocked(true)
                .scriptContent("人物停下并直视前方")
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto, null);

        assertTrue(pkg.getUserPrompt().contains("【创作者明确锁定景别】: CLOSE_UP"));
        assertTrue(pkg.getUserPrompt().contains("【创作者明确锁定运镜】: STATIC"));
    }
}
