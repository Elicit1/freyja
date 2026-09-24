package com.astra.freyja.service;

import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.CharacterShotRefInfoVO;
import com.astra.freyja.dto.drama.PropShotRefInfoVO;
import com.astra.freyja.dto.drama.ShotPromptDeriveDTO;
import com.astra.freyja.dto.drama.ShotPromptDeriveVO;
import com.astra.freyja.dto.drama.ShotPromptPackageVO;
import com.astra.freyja.dto.drama.ShotPromptValidationResult;
import com.astra.freyja.dto.drama.ShotRefAudioDTO;
import com.astra.freyja.dto.drama.ShotRefImageDTO;
import com.astra.freyja.dto.drama.manifest.ReferenceManifest;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.entity.ResCharacterOutfit;
import com.astra.freyja.entity.ResProp;
import com.astra.freyja.entity.ResScene;
import com.astra.freyja.service.impl.ShotAiVisualPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import com.astra.freyja.common.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShotPromptDeriveH3Test {

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
        lenient().when(sysConfigService.getConfigValue(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    @DisplayName("构建 MiniMax H3 ReferenceManifest 映射与上下文")
    void testBuildReferenceManifest() {
        // Mock Scene & Character
        ResScene scene = new ResScene();
        scene.setId(101L);
        scene.setName("顶层办公室");
        scene.setScenePrompt("dark walnut wall panels, modern glass windows");
        when(resSceneMapper.selectById(101L)).thenReturn(scene);

        ResCharacter character = new ResCharacter();
        character.setId(201L);
        character.setName("林默");
        character.setAppearanceDesc("lean young Chinese man, short black hair");
        when(characterMapper.selectById(201L)).thenReturn(character);

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .generationMode("REFERENCE_MODE")
                .promptTarget("MINIMAX_H3")
                .refImages(List.of(
                        ShotRefImageDTO.builder()
                                .id("ref_scene_1")
                                .sourceType("SCENE")
                                .sourceId(101L)
                                .name("顶层办公室")
                                .imageUrl("http://minio/scene1.png")
                                .build(),
                        ShotRefImageDTO.builder()
                                .id("ref_char_1")
                                .sourceType("CHARACTER")
                                .sourceId(201L)
                                .name("林默")
                                .imageUrl("http://minio/char1.png")
                                .build()
                ))
                .refAudios(List.of(
                        ShotRefAudioDTO.builder()
                                .id("ref_aud_1")
                                .sourceType("TTS")
                                .characterId(201L)
                                .characterName("林默")
                                .usageMode("VOICE_TIMBRE")
                                .language("Chinese")
                                .text("这是最后的警告。")
                                .audioUrl("http://minio/audio1.wav")
                                .duration(new BigDecimal("4.5"))
                                .build()
                ))
                .build();

        ReferenceManifest manifest = service.buildReferenceManifest(dto);

        assertNotNull(manifest);
        assertEquals(2, manifest.getPictures().size());
        assertEquals(1, manifest.getAudios().size());

        ReferenceManifest.PictureManifestItem pic1 = manifest.getPictures().get(0);
        assertEquals(1, pic1.getPictureIndex());
        assertEquals("SCENE", pic1.getSourceType());
        assertEquals("SCENE_LAYOUT", pic1.getUsageRole());
        assertTrue(pic1.getDescription().contains("dark walnut"));

        ReferenceManifest.PictureManifestItem pic2 = manifest.getPictures().get(1);
        assertEquals(2, pic2.getPictureIndex());
        assertEquals("CHARACTER", pic2.getSourceType());
        assertEquals("IDENTITY", pic2.getUsageRole());
        assertTrue(pic2.getDescription().contains("lean young Chinese man"));

        ReferenceManifest.AudioManifestItem aud1 = manifest.getAudios().get(0);
        assertEquals(1, aud1.getAudioIndex());
        assertEquals("VOICE_TIMBRE", aud1.getUsageMode());
        assertEquals("Chinese", aud1.getLanguage());

        String context = manifest.toPromptContext();
        assertTrue(context.contains("Picture 1"));
        assertTrue(context.contains("<Picture 1>"));
        assertTrue(context.contains("Picture 2"));
        assertTrue(context.contains("<Picture 2>"));
        assertTrue(context.contains("Audio 1"));
        assertTrue(context.contains("<Audio 1>"));
    }

    @Test
    @DisplayName("MiniMax H3 Ref2VA 六段式与标签校验")
    void testValidateH3Ref2VaOutput() {
        ReferenceManifest manifest = ReferenceManifest.builder()
                .pictures(List.of(
                        ReferenceManifest.PictureManifestItem.builder().pictureIndex(1).build(),
                        ReferenceManifest.PictureManifestItem.builder().pictureIndex(2).build()
                ))
                .audios(List.of(
                        ReferenceManifest.AudioManifestItem.builder().audioIndex(1).build()
                ))
                .build();

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .dialogue("这是最后的警告。")
                .build();

        String validPrompt = """
                subject_definitions:
                <Subject 1> is the office in <Picture 1>.
                <Subject 2> is character in <Picture 2>.
                <Audio 1> is voice reference.
                
                summary:
                Target video is a shot showing <Subject 2> inside <Subject 1>.
                
                retention_analysis:
                <Subject 1>: fully_preserved.
                <Subject 2>: fully_preserved.
                <Audio 1>: reference.
                
                detailed_description:
                [Shot 1] A medium shot of <Subject 2>. He says, <d>[Chinese] 这是最后的警告。</d>.
                
                overall_soundscape:
                Rain on window.
                
                non_diegetic_music:
                N/A.
                """;

        ShotPromptDeriveVO vo = ShotPromptDeriveVO.builder()
                .prompt(validPrompt)
                .videoPrompt(validPrompt)
                .build();

        // Should not throw exceptions
        assertDoesNotThrow(() -> service.validateH3Ref2VaOutput(vo, manifest, dto));
    }

    @Test
    @DisplayName("PROP_CONTEXT 支持无图片道具，覆盖5项文本设定与去重过滤要求")
    void testBuildPropContext_TextOnlyPropsAndDeduplication() {
        // 1. 有 propPrompt、无 coverUrl 的道具
        ResProp prop1 = new ResProp();
        prop1.setId(301L);
        prop1.setName("维吉尔的十字星传送门");
        prop1.setPropType("MAGIC_DEVICE");
        prop1.setPropPrompt("a luminous cruciform stellar portal, ethereal blue starlight");
        prop1.setCoverUrl(null); // 无封面图
        when(propMapper.selectById(301L)).thenReturn(prop1);

        // 2. 有 description、无 propPrompt 的道具
        ResProp prop2 = new ResProp();
        prop2.setId(302L);
        prop2.setName("古老羊皮纸卷轴");
        prop2.setPropType("KEY_PROP");
        prop2.setDescription("记录失落纪元契约的羊皮纸卷轴，边缘有烧焦痕迹");
        prop2.setPropPrompt(null);
        when(propMapper.selectById(302L)).thenReturn(prop2);

        // 3. description 和 propPrompt 都为空的道具
        ResProp prop3 = new ResProp();
        prop3.setId(303L);
        prop3.setName("普通黄铜钥匙");
        prop3.setPropType(null); // 测试默认兜底 KEY_PROP
        prop3.setDescription("");
        prop3.setPropPrompt(null);
        when(propMapper.selectById(303L)).thenReturn(prop3);

        // 5. 不存在的 propId 404L
        when(propMapper.selectById(404L)).thenReturn(null);

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .propRefs(List.of(
                        // 1. 无图有prompt
                        PropShotRefInfoVO.builder().propId(301L).build(),
                        // 4. 重复 propId (301L 再次出现)
                        PropShotRefInfoVO.builder().propId(301L).build(),
                        // 2. 有 description 无 prompt
                        PropShotRefInfoVO.builder().propId(302L).build(),
                        // 3. 两者皆空
                        PropShotRefInfoVO.builder().propId(303L).build(),
                        // 5. 不存在的道具
                        PropShotRefInfoVO.builder().propId(404L).build()
                ))
                .build();

        String propContext = service.buildPropContext(dto);

        assertNotNull(propContext);
        assertTrue(propContext.contains("【关键道具文字设定】"));

        // 验证 1: 有 propPrompt 无 coverUrl 进入上下文
        assertTrue(propContext.contains("维吉尔的十字星传送门"));
        assertTrue(propContext.contains("MAGIC_DEVICE"));
        assertTrue(propContext.contains("a luminous cruciform stellar portal"));

        // 验证 2: 有 description 无 propPrompt 进入上下文
        assertTrue(propContext.contains("古老羊皮纸卷轴"));
        assertTrue(propContext.contains("记录失落纪元契约的羊皮纸卷轴"));

        // 验证 3: description 和 propPrompt 都为空时保留名称和默认类型 KEY_PROP
        assertTrue(propContext.contains("普通黄铜钥匙"));
        assertTrue(propContext.contains("KEY_PROP"));

        // 验证 4: 重复 propId 只输出一次
        int count301 = (propContext.length() - propContext.replace("维吉尔的十字星传送门", "").length()) / "维吉尔的十字星传送门".length();
        assertEquals(1, count301);

        // 验证 5: 不存在的 propId 404L 未输出
        assertFalse(propContext.contains("404"));

        // 验证引用规则约束声明
        assertTrue(propContext.contains("只有 Reference Manifest 中存在对应图片时，才能声明图片引用"));
    }

    @Test
    @DisplayName("ReferenceManifest 过滤无真实 imageUrl 的空图片项且 Picture 编号连续")
    void testBuildReferenceManifest_FiltersBlankImageUrlAndMaintainsContinuousIndex() {
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .refImages(List.of(
                        // 7. 有效 imageUrl 正常生成 Picture 1
                        ShotRefImageDTO.builder()
                                .id("ref_img_1")
                                .sourceType("SCENE")
                                .sourceId(101L)
                                .name("有效场景图")
                                .imageUrl("http://minio/scene.png")
                                .build(),
                        // 6. imageUrl 为空但 name 有值，不生成 Picture
                        ShotRefImageDTO.builder()
                                .id("ref_img_empty_1")
                                .sourceType("PROP")
                                .sourceId(301L)
                                .name("维吉尔的十字星传送门(无图)")
                                .imageUrl("")
                                .build(),
                        // 6. imageUrl 为 null 但 name 有值，不生成 Picture
                        ShotRefImageDTO.builder()
                                .id("ref_img_empty_2")
                                .sourceType("PROP")
                                .sourceId(302L)
                                .name("空图片道具2")
                                .imageUrl(null)
                                .build(),
                        // 7 & 8. 有效 imageUrl 正常生成 Picture 2，编号连续不跳号
                        ShotRefImageDTO.builder()
                                .id("ref_img_2")
                                .sourceType("CHARACTER")
                                .sourceId(201L)
                                .name("有效角色图")
                                .imageUrl("http://minio/char.png")
                                .build()
                ))
                .build();

        ReferenceManifest manifest = service.buildReferenceManifest(dto);

        assertNotNull(manifest);
        // 只有 2 张真实图片进入 manifest
        assertEquals(2, manifest.getPictures().size());

        // 8. 编号连续
        ReferenceManifest.PictureManifestItem pic1 = manifest.getPictures().get(0);
        assertEquals(1, pic1.getPictureIndex());
        assertEquals("http://minio/scene.png", pic1.getImageUrl());
        assertEquals("有效场景图", pic1.getEntityName());

        ReferenceManifest.PictureManifestItem pic2 = manifest.getPictures().get(1);
        assertEquals(2, pic2.getPictureIndex());
        assertEquals("http://minio/char.png", pic2.getImageUrl());
        assertEquals("有效角色图", pic2.getEntityName());

        // 无图道具未出现在 promptContext 中
        String promptContext = manifest.toPromptContext();
        assertFalse(promptContext.contains("维吉尔的十字星传送门(无图)"));
        assertFalse(promptContext.contains("空图片道具2"));
        assertTrue(promptContext.contains("Picture 1"));
        assertTrue(promptContext.contains("Picture 2"));
        assertFalse(promptContext.contains("Picture 3"));
    }

    @Test
    @DisplayName("BGM 默认为关时，提示词中严格注入禁用 BGM 且设置为 N/A 的约束")
    void testBuildPromptPackage_BgmDisabledByDefault() {
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .actionDescription("主角在夜雨中奔跑")
                .includeBgm(false)
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto);

        assertNotNull(pkg);
        assertTrue(pkg.getSystemPrompt().contains("Camera Motion: Motion Type + Amplitude + Speed"));
        assertTrue(pkg.getSystemPrompt().contains("integrated_multimodal_description"));
        assertTrue(pkg.getUserPrompt().contains("【音频与BGM约束 (严禁BGM)】"));
        assertTrue(pkg.getUserPrompt().contains("non_diegetic_music 严格设置为 \"N/A\""));
        assertFalse(pkg.getUserPrompt().contains("【背景配乐 (BGM)】: 开启"));
    }

    @Test
    @DisplayName("BGM 显式开启时，提示词中注入允许情绪配乐与编配指南")
    void testBuildPromptPackage_BgmEnabled() {
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .shotNo(2)
                .actionDescription("主角推开沉重的大门")
                .includeBgm(true)
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto);

        assertNotNull(pkg);
        assertTrue(pkg.getUserPrompt().contains("【背景配乐 (BGM)】: 开启"));
        assertFalse(pkg.getUserPrompt().contains("【音频与BGM约束 (严禁BGM)】"));
    }

    @Test
    @DisplayName("validateAndNormalize 以结构化字段执行 BGM 归一化")
    void testValidateAndNormalize_NormalizeStructuredBgm() {
        // 1. FL2VA 模式下，BGM 禁用时仅归一化结构化字段，不修改自由文本
        String fl2vaPromptWithMusic = """
                How the reference pictures align with the target video — Picture 1 (from Shot 1) aligns with the 0.00-second mark of the target video; Picture 2 (from Shot 1) aligns with the 5.00-second mark of the target video.
                integrated_multimodal_description: [Shot 1] Cinematic shot.
                overall_soundscape: Rain falling on pavement.
                non_diegetic_music: Intense dramatic orchestral violin strings.
                """;

        ShotPromptDeriveVO fl2vaVo = ShotPromptDeriveVO.builder()
                .firstFramePrompt("start frame")
                .endFramePrompt("end frame")
                .prompt(fl2vaPromptWithMusic)
                .videoPrompt(fl2vaPromptWithMusic)
                .nonDiegeticMusic("Intense dramatic orchestral violin strings")
                .build();

        ShotPromptValidationResult resFl2va = service.validateAndNormalize(
                fl2vaVo, "FIRST_LAST_FRAME", null, null, true, false
        );

        assertNotNull(resFl2va.getResult());
        assertTrue(resFl2va.getResult().getPrompt().contains("Intense dramatic orchestral"));
        assertEquals("N/A", resFl2va.getResult().getNonDiegeticMusic());

        // 2. Ref2VA 模式下清洗多行段落配乐
        String ref2vaPromptWithMusic = """
                subject_definitions:
                <Subject 1> is the room.
                summary:
                Continuous shot.
                retention_analysis:
                <Subject 1>: preserved.
                detailed_description:
                The camera tracks slowly.
                overall_soundscape:
                Footsteps and room tone.
                non_diegetic_music:
                Melancholic piano chords slowly building.
                """;

        ShotPromptDeriveVO refVo = ShotPromptDeriveVO.builder()
                .prompt(ref2vaPromptWithMusic)
                .videoPrompt(ref2vaPromptWithMusic)
                .nonDiegeticMusic("Melancholic piano chords slowly building")
                .build();

        ShotPromptValidationResult resRef = service.validateAndNormalize(
                refVo, "REFERENCE_MODE", null, null, true, false
        );

        assertNotNull(resRef.getResult());
        assertTrue(resRef.getResult().getPrompt().contains("Melancholic piano chords"));
        assertEquals("N/A", resRef.getResult().getNonDiegeticMusic());
    }

    @Test
    void testBuildPromptPackageIncludesCharacterDesignDesc() {
        ResCharacter character = new ResCharacter();
        character.setId(501L);
        character.setName("范闲");
        character.setCanonicalName("范闲");
        character.setRoleType("PROTAGONIST");
        character.setAppearanceDesc("容貌俊美，双眼狭长，肤色白皙");
        when(characterMapper.selectById(501L)).thenReturn(character);

        // 1. 测试直接传递的 designDesc
        ShotPromptDeriveDTO dtoWithDirectDesc = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .scriptContent("范闲缓步走入内室")
                .generationMode("FIRST_LAST_FRAME")
                .characterRefs(List.of(
                        CharacterShotRefInfoVO.builder()
                                .characterId(501L)
                                .characterName("范闲")
                                .designDesc("一袭深青色织锦长袍，银丝暗纹滚边，腰系墨玉带")
                                .actionPrompt("walking slowly into the inner room")
                                .build()
                ))
                .build();

        ShotPromptPackageVO pkg1 = service.buildPromptPackage(dtoWithDirectDesc);
        assertNotNull(pkg1);
        assertTrue(pkg1.getUserPrompt().contains("【出场角色与造型装配】"));
        assertTrue(pkg1.getUserPrompt().contains("中文造型视觉概念描述: 一袭深青色织锦长袍，银丝暗纹滚边，腰系墨玉带"));
        assertTrue(pkg1.getUserPrompt().contains("范闲 (定位: PROTAGONIST)"));

        // 2. 测试通过 lookId 获取 outfit 的 designDesc
        ResCharacterOutfit outfit = new ResCharacterOutfit();
        outfit.setId(601L);
        outfit.setCharacterId(501L);
        outfit.setLookName("夜行黑衣");
        outfit.setDesignDesc("黑色夜行紧身衣，配皮质护腕与黑色面巾");
        outfit.setOutfitPrompt("black tight night suit, leather bracers");
        when(outfitMapper.selectById(601L)).thenReturn(outfit);

        ShotPromptDeriveDTO dtoWithLookId = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .scriptContent("范闲潜入皇宫")
                .generationMode("FIRST_LAST_FRAME")
                .characterRefs(List.of(
                        CharacterShotRefInfoVO.builder()
                                .characterId(501L)
                                .lookId(601L)
                                .build()
                ))
                .build();

        ShotPromptPackageVO pkg2 = service.buildPromptPackage(dtoWithLookId);
        assertNotNull(pkg2);
        assertTrue(pkg2.getUserPrompt().contains("中文造型视觉概念描述: 黑色夜行紧身衣，配皮质护腕与黑色面巾"));
        assertTrue(pkg2.getUserPrompt().contains("本镜装配服装Prompt: black tight night suit, leather bracers"));
    }

    @Test
    @DisplayName("1. 角色描述是经典女仆装，显式选择婚纱造型：最终提示词包含婚纱，不包含女仆装、女仆发箍、白围裙")
    void testExplicitLookEliminatesClassicMaidOutfit() {
        ResCharacter rem = new ResCharacter();
        rem.setId(701L);
        rem.setName("雷姆");
        rem.setCanonicalName("雷姆");
        rem.setRoleType("PROTAGONIST");
        rem.setAppearanceDesc("浅蓝色短发遮住右眼，常年身穿经典洛丽塔女仆黑白短裙装，白色围裙，头戴女仆发箍");
        when(characterMapper.selectById(701L)).thenReturn(rem);

        ResCharacterOutfit weddingLook = new ResCharacterOutfit();
        weddingLook.setId(801L);
        weddingLook.setCharacterId(701L);
        weddingLook.setLookName("花嫁婚纱");
        weddingLook.setDesignDesc("白色抹胸拖地婚纱，轻薄蕾丝刺绣头纱");
        weddingLook.setOutfitPrompt("white off-shoulder bridal gown, delicate lace embroidery veil");
        weddingLook.setStatus(1);
        when(outfitMapper.selectById(801L)).thenReturn(weddingLook);

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .scriptContent("雷姆手捧捧花微笑着走向礼堂前方")
                .characterRefs(List.of(
                        CharacterShotRefInfoVO.builder()
                                .characterId(701L)
                                .lookId(801L)
                                .build()
                ))
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto);
        assertNotNull(pkg);
        String userPrompt = pkg.getUserPrompt();

        // 包含显式造型
        assertTrue(userPrompt.contains("白色抹胸拖地婚纱"));
        assertTrue(userPrompt.contains("white off-shoulder bridal gown"));

        // 严格排他：绝不能包含女仆装、女仆发箍、白围裙等原著外貌描述
        assertFalse(userPrompt.contains("女仆装"));
        assertFalse(userPrompt.contains("女仆发箍"));
        assertFalse(userPrompt.contains("白围裙"));
        assertFalse(userPrompt.contains("原著外貌视觉SSOT"));
    }

    @Test
    @DisplayName("2. 只传中文 designDesc，outfitPrompt 为空：提示词正常生成")
    void testOnlyChineseDesignDescWorksStandaloneWithoutOutfitPrompt() {
        ResCharacter character = new ResCharacter();
        character.setId(702L);
        character.setName("林婉清");
        character.setCanonicalName("林婉清");
        character.setRoleType("PROTAGONIST");
        character.setAppearanceDesc("容颜清丽脱俗，眉目如画");
        when(characterMapper.selectById(702L)).thenReturn(character);

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .scriptContent("林婉清持剑立于风中")
                .characterRefs(List.of(
                        CharacterShotRefInfoVO.builder()
                                .characterId(702L)
                                .designDesc("一袭素白暗纹深衣，腰系银丝带，长发飘逸")
                                .outfitPrompt(null) // outfitPrompt 为空
                                .build()
                ))
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto);
        assertNotNull(pkg);
        String userPrompt = pkg.getUserPrompt();

        assertTrue(userPrompt.contains("中文造型视觉概念描述: 一袭素白暗纹深衣，腰系银丝带，长发飘逸"));
        assertFalse(userPrompt.contains("原著外貌视觉SSOT"));
    }

    @Test
    @DisplayName("3. 完全不传造型：使用角色 appearanceDesc 兜底")
    void testFallbackToAppearanceDescWhenNoLookProvided() {
        ResCharacter character = new ResCharacter();
        character.setId(703L);
        character.setName("叶孤城");
        character.setCanonicalName("叶孤城");
        character.setRoleType("PROTAGONIST");
        character.setAppearanceDesc("墨发高束，身穿月白色暗纹长袍，气质清冷如霜");
        when(characterMapper.selectById(703L)).thenReturn(character);

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .scriptContent("叶孤城负手站在城楼之上")
                .characterRefs(List.of(
                        CharacterShotRefInfoVO.builder()
                                .characterId(703L)
                                .lookId(null)
                                .designDesc(null)
                                .appearancePrompt(null)
                                .outfitPrompt(null)
                                .build()
                ))
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto);
        assertNotNull(pkg);
        String userPrompt = pkg.getUserPrompt();

        assertTrue(userPrompt.contains("原著外貌视觉SSOT: 墨发高束，身穿月白色暗纹长袍，气质清冷如霜"));
        assertFalse(userPrompt.contains("中文造型视觉概念描述"));
    }

    @Test
    @DisplayName("4. 不传造型时：不得自动查询或装配默认造型")
    void testNoLookDoesNotQueryDefaultLook() {
        ResCharacter character = new ResCharacter();
        character.setId(704L);
        character.setName("萧炎");
        character.setCanonicalName("萧炎");
        character.setRoleType("PROTAGONIST");
        character.setAppearanceDesc("黑袍少年，背负玄重尺");
        when(characterMapper.selectById(704L)).thenReturn(character);

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .scriptContent("萧炎行于大漠")
                .characterRefs(List.of(
                        CharacterShotRefInfoVO.builder()
                                .characterId(704L)
                                .build()
                ))
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto);
        assertNotNull(pkg);
        String userPrompt = pkg.getUserPrompt();

        assertTrue(userPrompt.contains("原著外貌视觉SSOT: 黑袍少年，背负玄重尺"));
        // 验证没有通过 outfitMapper 查询默认造型或输出任何装配服装
        assertFalse(userPrompt.contains("本镜装配服装Prompt"));
        assertFalse(userPrompt.contains("中文造型视觉概念描述"));
    }

    @Test
    @DisplayName("5. 传入不存在或归属错误的 lookId：明确报错")
    void testInvalidOrMismatchedLookIdThrowsException() {
        ResCharacter character = new ResCharacter();
        character.setId(705L);
        character.setName("楚留香");
        when(characterMapper.selectById(705L)).thenReturn(character);

        // 1. 不存在的 lookId
        when(outfitMapper.selectById(99999L)).thenReturn(null);
        ShotPromptDeriveDTO dtoNonExistent = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .scriptContent("楚留香踏月而来")
                .characterRefs(List.of(
                        CharacterShotRefInfoVO.builder()
                                .characterId(705L)
                                .lookId(99999L)
                                .build()
                ))
                .build();

        BizException ex1 = assertThrows(BizException.class, () -> service.buildPromptPackage(dtoNonExistent));
        assertTrue(ex1.getMessage().contains("所选人物造型不存在或已停用"));

        // 2. 属于其他角色的 lookId
        ResCharacterOutfit mismatchedLook = new ResCharacterOutfit();
        mismatchedLook.setId(888L);
        mismatchedLook.setCharacterId(999L); // 属于角色 999
        mismatchedLook.setStatus(1);
        when(outfitMapper.selectById(888L)).thenReturn(mismatchedLook);

        ShotPromptDeriveDTO dtoMismatched = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .scriptContent("楚留香踏月而来")
                .characterRefs(List.of(
                        CharacterShotRefInfoVO.builder()
                                .characterId(705L)
                                .lookId(888L)
                                .build()
                ))
                .build();

        BizException ex2 = assertThrows(BizException.class, () -> service.buildPromptPackage(dtoMismatched));
        assertTrue(ex2.getMessage().contains("所选造型不属于指定人物"));
    }

    @Test
    @DisplayName("6. ReferenceManifest 同样遵循造型覆盖规则：婚纱造型时不混入女仆装")
    void testReferenceManifestFollowsLookOverrideRules() {
        ResCharacter rem = new ResCharacter();
        rem.setId(706L);
        rem.setName("雷姆");
        rem.setAppearanceDesc("浅蓝色短发遮右眼，身穿经典洛丽塔女仆黑白短裙装，白色围裙，头戴女仆发箍");
        when(characterMapper.selectById(706L)).thenReturn(rem);

        ResCharacterOutfit weddingLook = new ResCharacterOutfit();
        weddingLook.setId(806L);
        weddingLook.setCharacterId(706L);
        weddingLook.setLookName("花嫁婚纱");
        weddingLook.setDesignDesc("白色抹胸拖地婚纱，轻薄蕾丝刺绣头纱");
        weddingLook.setOutfitPrompt("white off-shoulder bridal gown, lace veil");
        weddingLook.setStatus(1);
        when(outfitMapper.selectById(806L)).thenReturn(weddingLook);

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .generationMode("REFERENCE_MODE")
                .characterRefs(List.of(
                        CharacterShotRefInfoVO.builder()
                                .characterId(706L)
                                .lookId(806L)
                                .build()
                ))
                .refImages(List.of(
                        ShotRefImageDTO.builder()
                                .id("ref_rem_1")
                                .sourceType("CHARACTER")
                                .sourceId(706L)
                                .lookId(806L)
                                .name("雷姆")
                                .imageUrl("http://minio/rem_wedding.png")
                                .build()
                ))
                .build();

        ReferenceManifest manifest = service.buildReferenceManifest(dto);
        assertNotNull(manifest);
        assertEquals(1, manifest.getPictures().size());

        ReferenceManifest.PictureManifestItem pic = manifest.getPictures().get(0);
        String desc = pic.getDescription();

        // 验证造型描述被正确装配
        assertTrue(desc.contains("white off-shoulder bridal gown") || desc.contains("白色抹胸拖地婚纱"));
        // 严格排他：绝不能包含女仆装描述
        assertFalse(desc.contains("女仆装"));
        assertFalse(desc.contains("白围裙"));
        assertFalse(desc.contains("女仆发箍"));

        String promptContext = manifest.toPromptContext();
        assertFalse(promptContext.contains("女仆装"));
        assertFalse(promptContext.contains("白围裙"));
    }

    @Test
    @DisplayName("7. 人物、造型、场景、道具、音频都包含内部 URL：生成的模型消息中不得出现任何 URL")
    void testInternalUrlsCompletelyIsolatedFromModelContextAndIntercepted() {
        ResScene scene = new ResScene();
        scene.setId(101L);
        scene.setName("宗门大殿");
        scene.setScenePrompt("grand ancient oriental hall");
        scene.setReferenceImageUrl("http://minio/freyja/scenes/scene_101.png");
        when(resSceneMapper.selectById(101L)).thenReturn(scene);

        ResCharacter character = new ResCharacter();
        character.setId(201L);
        character.setName("林默");
        character.setAppearanceDesc("黑发青年");
        character.setReferenceImageUrl("http://minio/freyja/characters/char_201.png");
        when(characterMapper.selectById(201L)).thenReturn(character);

        ResCharacterOutfit outfit = new ResCharacterOutfit();
        outfit.setId(301L);
        outfit.setCharacterId(201L);
        outfit.setLookName("战袍");
        outfit.setOutfitPrompt("black tactical armor");
        outfit.setReferenceImageUrl("http://minio/freyja/outfits/look_301.png");
        outfit.setStatus(1);
        when(outfitMapper.selectById(301L)).thenReturn(outfit);

        ResProp prop = new ResProp();
        prop.setId(401L);
        prop.setName("斩仙剑");
        prop.setPropPrompt("ancient glowing sword");
        prop.setCoverUrl("http://minio/freyja/props/prop_401.png");
        when(propMapper.selectById(401L)).thenReturn(prop);

        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .resSceneId(101L)
                .generationMode("REFERENCE_MODE")
                .scriptContent("林默拔出斩仙剑")
                .characterRefs(List.of(
                        CharacterShotRefInfoVO.builder()
                                .characterId(201L)
                                .lookId(301L)
                                .build()
                ))
                .propRefs(List.of(
                        PropShotRefInfoVO.builder().propId(401L).build()
                ))
                .refImages(List.of(
                        ShotRefImageDTO.builder()
                                .id("ref_scene")
                                .sourceType("SCENE")
                                .sourceId(101L)
                                .name("宗门大殿")
                                .imageUrl("http://minio/freyja/scenes/scene_101.png")
                                .build(),
                        ShotRefImageDTO.builder()
                                .id("ref_char")
                                .sourceType("CHARACTER")
                                .sourceId(201L)
                                .lookId(301L)
                                .name("林默")
                                .imageUrl("http://minio/freyja/outfits/look_301.png")
                                .build()
                ))
                .refAudios(List.of(
                        ShotRefAudioDTO.builder()
                                .id("ref_aud")
                                .sourceType("TTS")
                                .characterId(201L)
                                .characterName("林默")
                                .audioUrl("http://minio/freyja/audios/sample.wav")
                                .build()
                ))
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto);
        assertNotNull(pkg);

        // 最终发给大模型的 systemPrompt 和 userPrompt 中严格不包含任何内部资源/媒体 URL
        assertFalse(pkg.getSystemPrompt().contains("http://"));
        assertFalse(pkg.getSystemPrompt().contains("minio"));
        assertFalse(pkg.getSystemPrompt().contains(".png"));
        assertFalse(pkg.getSystemPrompt().contains(".wav"));

        assertFalse(pkg.getUserPrompt().contains("http://"));
        assertFalse(pkg.getUserPrompt().contains("minio"));
        assertFalse(pkg.getUserPrompt().contains(".png"));
        assertFalse(pkg.getUserPrompt().contains(".wav"));

        assertFalse(pkg.getCombinedPrompt().contains("http://"));
        assertFalse(pkg.getCombinedPrompt().contains("minio"));
        assertFalse(pkg.getCombinedPrompt().contains(".png"));
        assertFalse(pkg.getCombinedPrompt().contains(".wav"));

        // 验证恶意注入 URL 时，安全守卫阻断
        ShotPromptDeriveDTO maliciousDto = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .userInstruction("请参考这个图片 http://minio/malicious.png 生成")
                .build();
        BizException securityEx = assertThrows(BizException.class, () -> service.buildPromptPackage(maliciousDto));
        assertTrue(securityEx.getMessage().contains("内部媒体资源 URL"));
    }

    @Test
    @DisplayName("8. 使用任务 2102207362021310466 的载荷重放：最终角色上下文只能出现“花嫁雷姆”造型，不出现经典女仆装")
    void testReplayTaskPayload_2102207362021310466() {
        Long remCharacterId = 2102207362021310466L;
        Long remWeddingLookId = 2102207362021310467L;

        ResCharacter rem = new ResCharacter();
        rem.setId(remCharacterId);
        rem.setName("雷姆");
        rem.setCanonicalName("雷姆");
        rem.setRoleType("PROTAGONIST");
        rem.setAppearanceDesc("蓝色短发，右眼常被刘海遮挡，常年身穿经典洛丽塔女仆黑白短裙装，白色围裙，头戴女仆发箍");
        when(characterMapper.selectById(remCharacterId)).thenReturn(rem);

        ResCharacterOutfit weddingLook = new ResCharacterOutfit();
        weddingLook.setId(remWeddingLookId);
        weddingLook.setCharacterId(remCharacterId);
        weddingLook.setLookName("花嫁雷姆");
        weddingLook.setDesignDesc("花嫁雷姆，纯白抹胸婚纱，轻薄头纱与白色花朵发饰，手捧白玫瑰");
        weddingLook.setOutfitPrompt("Rem in gorgeous white wedding dress, bridal gown, white rose bouquet");
        weddingLook.setStatus(1);
        when(outfitMapper.selectById(remWeddingLookId)).thenReturn(weddingLook);

        // 重放任务 2102207362021310466 核心载荷
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder()
                .shotNo(1)
                .scriptContent("雷姆微笑着站在花海中，微风拂动白色婚纱")
                .actionDescription("雷姆手持白玫瑰花束，眼中充满深情与幸福")
                .generationMode("FIRST_LAST_FRAME")
                .characterRefs(List.of(
                        CharacterShotRefInfoVO.builder()
                                .characterId(remCharacterId)
                                .lookId(remWeddingLookId)
                                .actionPrompt("holding white roses gently, smiling warmly")
                                .emotionPrompt("deep affection, gentle smile")
                                .positionTag("特写居中")
                                .build()
                ))
                .build();

        ShotPromptPackageVO pkg = service.buildPromptPackage(dto);
        assertNotNull(pkg);
        String userPrompt = pkg.getUserPrompt();

        // 验证：最终角色上下文只能出现“花嫁雷姆”造型
        assertTrue(userPrompt.contains("花嫁雷姆，纯白抹胸婚纱，轻薄头纱与白色花朵发饰，手捧白玫瑰"));
        assertTrue(userPrompt.contains("Rem in gorgeous white wedding dress"));
        assertTrue(userPrompt.contains("holding white roses gently"));

        // 验证：绝对不出现经典女仆装、女仆发箍、白色围裙
        assertFalse(userPrompt.contains("经典洛丽塔女仆黑白短裙装"));
        assertFalse(userPrompt.contains("女仆装"));
        assertFalse(userPrompt.contains("女仆发箍"));
        assertFalse(userPrompt.contains("白色围裙"));
        assertFalse(userPrompt.contains("原著外貌视觉SSOT"));
    }
}
