package com.astra.freyja.service;

import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.dto.drama.manifest.ReferenceManifest;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.service.impl.ShotAiVisualPlanServiceImpl;
import com.astra.freyja.skill.model.SkillPromptContext;
import com.astra.freyja.skill.service.SkillPromptContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShotDualChannelPromptDeriveTest {

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
    private com.astra.freyja.director.service.DirectorPlanningService directorPlanningService;
    @Mock
    private com.astra.freyja.director.service.DirectorPlanMergeService directorPlanMergeService;
    @Mock
    private SkillPromptContextService skillPromptContextService;

    private ObjectMapper objectMapper = new ObjectMapper();

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
    @DisplayName("buildPromptPackage 能够构建包含 combinedPrompt 与 contextFingerprint 的完整任务包")
    void testBuildPromptPackage_Success() {
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_ref2va_system"), anyString()))
                .thenReturn("System H3 Rules");
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_ref2va_user"), anyString()))
                .thenReturn("User Task: ${DRAMA_CONTEXT} ${SHOT_SPEC} ${REFERENCE_MANIFEST}");

        Drama drama = new Drama();
        drama.setId(10L);
        drama.setTitle("天命龙帝");
        drama.setStylePreset("国风玄幻");
        when(dramaMapper.selectById(10L)).thenReturn(drama);

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .dramaId(10L)
                .shotNo(1)
                .generationMode("REFERENCE_MODE")
                .scriptContent("林辰持剑推门而出，眼神如电。")
                .actionDescription("拔剑出鞘")
                .refImages(List.of(
                        ShotRefImageDTO.builder().imageUrl("http://minio/char.png").name("林辰").usageRole("IDENTITY").build()
                ))
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto);

        assertNotNull(pkg);
        assertEquals("REFERENCE_MODE", pkg.getGenerationMode());
        assertEquals("minimax-h3-ref2va-v1", pkg.getTemplateVersion());
        assertNotNull(pkg.getContextFingerprint());
        assertTrue(pkg.getContextFingerprint().startsWith("sha256:"));
        assertTrue(pkg.getCombinedPrompt().contains("SYSTEM INSTRUCTIONS"));
        assertTrue(pkg.getCombinedPrompt().contains("USER TASK"));
        assertTrue(pkg.getCombinedPrompt().contains("RESPONSE REQUIREMENT"));
        assertTrue(pkg.getCombinedPrompt().contains("天命龙帝"));
        // SysConfig 是 H3 模板唯一来源；测试配置覆写后应原样保留。
        assertTrue(pkg.getSystemPrompt().contains("System H3 Rules"));
        assertTrue(pkg.getOutputFormat().contains("videoPrompt"));
        assertFalse(pkg.getSystemPrompt().contains("H3_SKILL_USAGE_PROTOCOL"));
        assertNotNull(pkg.getReferenceManifest());
        assertEquals(1, pkg.getReferenceManifest().getPictures().size());
    }

    @Test
    @DisplayName("首尾帧与参考图模式都会展开用户选择的本地 Skill，并纳入上下文指纹")
    void testBuildPromptPackage_InjectsSelectedSkillsForBothModes() {
        ReflectionTestUtils.setField(service, "skillPromptContextService", skillPromptContextService);

        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_system"), anyString()))
                .thenReturn("FL2VA_SYSTEM");
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_fl2va_user"), anyString()))
                .thenReturn("FL2VA_USER ${SHOT_SPEC}");
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_ref2va_system"), anyString()))
                .thenReturn("REF2VA_SYSTEM");
        when(sysConfigService.getConfigValue(eq("ai.prompt.minimax_h3_ref2va_user"), anyString()))
                .thenReturn("REF2VA_USER ${SHOT_SPEC}");

        when(skillPromptContextService.loadSelected(anyString(), anyString(), anyList())).thenReturn(new SkillPromptContext(
                "===== BEGIN LOCAL SKILL: cinematography / v7 =====\n"
                        + "cinematography rule: keep camera beats physically consistent\n"
                        + "===== END LOCAL SKILL: cinematography =====\n"
                        + "===== BEGIN LOCAL SKILL: sound-design / v3 =====\n"
                        + "sound rule: preserve diegetic sound continuity\n"
                        + "===== END LOCAL SKILL: sound-design =====",
                "sha256:test-skills",
                List.of("cinematography@v7", "sound-design@v3")
        ));
        when(skillPromptContextService.appendToSystemPrompt(anyString(), any(SkillPromptContext.class)))
                .thenAnswer(inv -> inv.getArgument(0, String.class) + "\n"
                        + inv.getArgument(1, SkillPromptContext.class).prompt());

        ShotPromptDeriveDTO firstLastDto = ShotPromptDeriveDTO.builder()
                .generationMode("FIRST_LAST_FRAME")
                .scriptContent("人物推门进入房间")
                .selectedSkillNames(List.of("cinematography", "sound-design"))
                .build();
        ShotPromptDeriveDTO referenceDto = ShotPromptDeriveDTO.builder()
                .generationMode("REFERENCE_MODE")
                .scriptContent("人物推门进入房间")
                .selectedSkillNames(List.of("cinematography", "sound-design"))
                .build();

        ShotPromptPackageVO firstLastPackage = service.buildPromptPackage(firstLastDto);
        ShotPromptPackageVO referencePackage = service.buildPromptPackage(referenceDto);

        assertTrue(firstLastPackage.getSystemPrompt().contains("cinematography rule"));
        assertTrue(firstLastPackage.getSystemPrompt().contains("sound rule"));
        assertTrue(referencePackage.getSystemPrompt().contains("cinematography rule"));
        assertTrue(referencePackage.getSystemPrompt().contains("sound rule"));
        assertNotEquals(firstLastPackage.getContextFingerprint(), referencePackage.getContextFingerprint());
    }

    @Test
    @DisplayName("cleanAndParseJson 解析 Markdown 代码块并忽略旧版说明字段")
    void testCleanAndParseJson_WithMarkdownFence() {
        String rawMarkdown = """
                这是由 Claude 生成的提示词方案：
                `json
                {
                  "prompt": "subject_definitions: ...",
                  "videoPrompt": "subject_definitions: ...",
                  "focusTarget": "林辰",
                  "compositionNote": "中景特写"
                }
                `
                希望对您有帮助！
                """;

        ShotPromptDeriveVO vo = service.cleanAndParseJson(rawMarkdown);

        assertNotNull(vo);
        assertEquals("subject_definitions: ...", vo.getPrompt());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(vo);
        assertFalse(json.has("focusTarget"));
        assertFalse(json.has("compositionNote"));
    }

    @Test
    @DisplayName("validateAndNormalize 在 REFERENCE_MODE 下识别越界 Picture 并给出警告与错误")
    void testValidateAndNormalize_PictureBoundaryAndMissingSections() {
        ReferenceManifest manifest = ReferenceManifest.builder()
                .pictures(List.of(
                        ReferenceManifest.PictureManifestItem.builder().pictureIndex(1).entityName("林辰").build()
                ))
                .build();

        // prompt 引用了未分配的 <Picture 5>，且缺少部分必填段落与 <d> 标签
        ShotPromptDeriveVO vo = ShotPromptDeriveVO.builder()
                .prompt("subject_definitions: <Picture 5> 林辰. summary: test")
                .firstFramePrompt("invalid first frame")
                .build();

        ShotPromptValidationResult result = service.validateAndNormalize(
                vo,
                "REFERENCE_MODE",
                manifest,
                "你必须离开这里！",
                true
        );

        assertNotNull(result);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("<Picture 5>")));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("缺少 MiniMax H3 官方必填段落")));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("<d>[Language] ...</d>")));
        // 首尾帧提示词被强制归一化置空
        assertNull(result.getResult().getFirstFramePrompt());
        assertNull(result.getResult().getEndFramePrompt());
    }

    @Test
    @DisplayName("parseAndValidateDerivedPrompt 外部结果解析端到端完整流程与指纹比对")
    void testParseAndValidateDerivedPrompt_EndToEnd() {
        String rawResponse = """
                `json
                {
                  "videoPrompt": "How the reference pictures align with the target video — Picture 1 aligns with the 0.00-second mark of the target video; Picture 2 aligns with the 5.00-second mark of the target video.\\nintegrated_multimodal_description: Camera pushes in.\\noverall_soundscape: Wind blowing.\\nnon_diegetic_music: N/A",
                  "firstFramePrompt": "Cinematic shot of hero standing on cliff",
                  "endFramePrompt": "Hero turning around looking at distance",
                  "negativePrompt": "low quality, blurry"
                }
                `
                """;

        ShotPromptParseRequestDTO request = ShotPromptParseRequestDTO.builder()
                .generationMode("FIRST_LAST_FRAME")
                .rawResponse(rawResponse)
                .contextFingerprint("sha256:dummyfingerprint")
                .build();

        ShotPromptValidationResult valResult = service.parseAndValidateDerivedPrompt(request);

        assertNotNull(valResult);
        assertFalse(valResult.hasErrors());
        assertNotNull(valResult.getResult());
        assertEquals("Cinematic shot of hero standing on cliff", valResult.getResult().getFirstFramePrompt());
        assertEquals("Hero turning around looking at distance", valResult.getResult().getEndFramePrompt());
        assertNull(valResult.getResult().getPrompt());
        assertNull(valResult.getResult().getNegativePrompt());
        assertTrue(valResult.getResult().getVideoPrompt().contains("integrated_multimodal_description:"));
        // 指纹不匹配时会给出黄色警告
        assertEquals(Boolean.FALSE, valResult.getFingerprintMatched());
        assertTrue(valResult.getWarnings().stream().anyMatch(w -> w.contains("上下文指纹不一致")));
    }
}
