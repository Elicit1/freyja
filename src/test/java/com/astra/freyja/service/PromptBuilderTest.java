package com.astra.freyja.service;


import com.astra.freyja.dto.script.DecomposedCharacterVO;
import com.astra.freyja.dto.script.DecomposedSceneVO;
import com.astra.freyja.dto.script.DecomposedShotVO;
import com.astra.freyja.service.prompt.impl.PromptBuilderImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PromptBuilderTest {

    @Mock
    private SysConfigService sysConfigService;

    private PromptBuilderImpl promptBuilder;

    @BeforeEach
    void setUp() {
        lenient().when(sysConfigService.getConfigValue(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        promptBuilder = new PromptBuilderImpl(sysConfigService);
    }

    @Test
    @DisplayName("Test 1: 本地组合资产与画面描述，镜头运动仅透传创作者明确锁定项")
    void testBuildPositivePrompt() {
        DecomposedCharacterVO character = DecomposedCharacterVO.builder()
                .name("葛明")
                .appearancePrompt("1man, 30yo, sharp eyes, short black hair")
                .outfitPrompt("wearing black business suit")
                .build();

        DecomposedSceneVO scene = DecomposedSceneVO.builder()
                .sceneName("现代办公室")
                .scenePrompt("modern luxury office, large glass window, city view")
                .timeOfDay("DAY")
                .weatherAtmosphere("SUNNY")
                .build();

        DecomposedShotVO shot = DecomposedShotVO.builder()
                .shotNo(1)
                .shotName("S01-01")
                .shotType("MEDIUM_SHOT")
                .cameraMovement("PUSH_IN")
                .cameraMovementLocked(true)
                .duration(4.5)
                .actionDescription("葛明推开大门大步走进办公室，目光严峻")
                .firstFrameVisual("1man pushing open heavy frosted glass door, stepping into luxury office, stern intense gaze")
                .primaryCharacter("葛明")
                .characterNames(List.of("葛明"))
                .build();

        String positivePrompt = promptBuilder.buildPositivePrompt(shot, List.of(character), scene, "cinematic-realism");
        assertNotNull(positivePrompt);
        assertTrue(positivePrompt.contains("1man"));
        assertTrue(positivePrompt.contains("black business suit"));
        assertTrue(positivePrompt.contains("modern luxury office"));
        assertTrue(positivePrompt.contains("pushing open heavy frosted glass door"));
        // 验证彻底剔除机械状态词与多余景别硬词
        assertFalse(positivePrompt.contains("medium shot"), "首帧存在原生 visualFraming 时不应重复追加景别词");
        assertFalse(positivePrompt.contains("full body shot"), "严禁出现 full body shot 破坏画面构图与面部细节");
        assertFalse(positivePrompt.contains("push in")); // 首图静态生图词严格剔除运镜动词
        assertFalse(positivePrompt.contains("office_door"), "连续性状态位置严禁作为机械文本拼接进生图词");
        assertFalse(positivePrompt.contains("holding briefcase"), "连续性状态持物严禁作为机械文本拼接进生图词");
        assertFalse(positivePrompt.contains("looking at"), "严禁机械拼接 looking at");
        assertFalse(positivePrompt.matches(".*[\\u4e00-\\u9fa5]+.*"), "首帧生图提示词必须为纯英文，严禁中文残留");

        String videoPrompt = promptBuilder.buildVideoPrompt(shot, scene, null);
        assertNotNull(videoPrompt);
        assertTrue(videoPrompt.contains("push in")); // 运镜动词归属于视频生成提示词
        assertFalse(videoPrompt.contains("medium shot"), "视频提示词严禁注入静态景别词");
        assertFalse(videoPrompt.contains("full body shot"), "视频提示词严禁注入 full body shot 避免画面锁死");

        String negativePrompt = promptBuilder.buildNegativePrompt("cinematic-realism", null);
        assertNotNull(negativePrompt);
        assertTrue(negativePrompt.contains("worst quality"));
    }

    @Test
    @DisplayName("Test 1b: 当首帧构图与动作为空时仅透传创作者明确锁定的景别")
    void testShotTypeFallbackWhenVisualEmpty() {
        DecomposedShotVO shot = DecomposedShotVO.builder()
                .shotNo(1)
                .shotName("S01-01")
                .shotType("FULL_SHOT")
                .shotTypeLocked(true)
                .build();

        String positivePrompt = promptBuilder.buildPositivePrompt(shot, List.of(), null, null);
        assertNotNull(positivePrompt);
        assertTrue(positivePrompt.contains("full shot"), "完全无画面描述时作为兜底");
        assertFalse(positivePrompt.contains("full body shot"), "严禁使用 full body shot 词汇");
        assertFalse(positivePrompt.contains("looking at"), "严禁机械拼接 looking at");
        assertFalse(positivePrompt.contains("holding"), "严禁机械拼接 holding");
    }

    @Test
    @DisplayName("Test 2: 暗光密闭场景纯净生成物理环境与定向光位补偿，首图无动态运镜词与无闪烁词")
    void testDarkScenePurePhysicalPrompt() {
        DecomposedSceneVO darkScene = DecomposedSceneVO.builder()
                .sceneName("昏暗破旧暗室")
                .scenePrompt("dim retro abandoned room, vintage tungsten bulb")
                .timeOfDay("NIGHT")
                .weatherAtmosphere("MOODY")
                .build();

        DecomposedShotVO shot = DecomposedShotVO.builder()
                .shotNo(1)
                .shotName("S01-01")
                .shotType("MEDIUM_SHOT")
                .cameraMovement("TILT_DOWN")
                .cameraMovementLocked(true)
                .actionDescription("镜头从微弱钨丝灯特写开始缓慢下移")
                .firstFrameVisual("extreme close-up of vintage tungsten bulb hanging in dim room, warm amber filament glow")
                .build();

        String positivePrompt = promptBuilder.buildPositivePrompt(shot, List.of(), darkScene, null);
        assertNotNull(positivePrompt);
        assertTrue(positivePrompt.contains("dim retro abandoned room"));
        assertTrue(positivePrompt.contains("vintage tungsten bulb"));
        assertTrue(positivePrompt.contains("MOODY atmosphere"));
        assertTrue(positivePrompt.contains("NIGHT time"));
        assertTrue(positivePrompt.contains("directional side key lighting"));
        assertTrue(positivePrompt.contains("sharp edge rim light"));
        assertFalse(positivePrompt.contains("tilt down"), "首帧静态生图不含运镜调度词");
        assertFalse(positivePrompt.matches(".*[\\u4e00-\\u9fa5]+.*"), "首帧生图必须是纯英文");

        String videoPrompt = promptBuilder.buildVideoPrompt(shot, darkScene, null);
        assertNotNull(videoPrompt);
        assertTrue(videoPrompt.contains("tilt down")); // 视频提示词包含运镜
        assertTrue(videoPrompt.contains("MOODY dynamics"));
    }

    @Test
    @DisplayName("Test 3: 角色资产完整透传，不按人数或景别猜测群像构图")
    void testCrowdShotSingleFocusFilter() {
        DecomposedCharacterVO c1 = DecomposedCharacterVO.builder()
                .name("齐夏")
                .appearancePrompt("1man, messy black hair, bandage on head")
                .build();
        DecomposedCharacterVO c2 = DecomposedCharacterVO.builder()
                .name("花臂男")
                .appearancePrompt("1man, muscular, dragon tattoo on right arm")
                .build();
        DecomposedCharacterVO c3 = DecomposedCharacterVO.builder()
                .name("医生")
                .appearancePrompt("1woman, glasses, white lab coat")
                .build();

        DecomposedShotVO crowdShot = DecomposedShotVO.builder()
                .shotNo(1)
                .shotName("S01-01")
                .shotType("LONG_SHOT")
                .cameraMovement("ORBIT")
                .actionDescription("十个人以各异姿势趴在大圆桌上沉睡")
                .characterNames(List.of("齐夏", "花臂男", "医生"))
                .build();

        String prompt = promptBuilder.buildPositivePrompt(crowdShot, List.of(c1, c2, c3), null, "cinematic-realism");
        assertNotNull(prompt);
        assertTrue(prompt.contains("dragon tattoo"), "实际出场角色的身份设定应透传给后续视觉导演");
        assertTrue(prompt.contains("white lab coat"), "实际出场角色的服装设定应透传给后续视觉导演");
        assertFalse(prompt.contains("wide angle crowd composition"), "Java 不根据人数或景别补造群像构图");
    }

    @Test
    @DisplayName("Test 4: Java 不根据主次角色字段推断过肩构图或角色虚焦")
    void testOverTheShoulderShotFocusAndBlur() {
        DecomposedCharacterVO hero = DecomposedCharacterVO.builder()
                .name("苏清雪")
                .appearancePrompt("1woman, 22yo, delicate face, cold expression")
                .outfitPrompt("wearing white elegant dress")
                .build();
        DecomposedCharacterVO villain = DecomposedCharacterVO.builder()
                .name("杜宁")
                .appearancePrompt("1man, scar on left cheek, angry eyes")
                .outfitPrompt("wearing leather jacket")
                .build();

        DecomposedShotVO otsShot = DecomposedShotVO.builder()
                .shotNo(2)
                .shotName("S01-02")
                .shotType("OVER_SHOULDER")
                .cameraMovement("STATIC")
                .duration(4.0)
                .actionDescription("镜头从杜宁背后过肩看去，苏清雪端坐桌前冷冷注视")
                .primaryCharacter("苏清雪")
                .secondaryCharacter("杜宁")
                .characterNames(List.of("苏清雪", "杜宁"))
                .build();

        String prompt = promptBuilder.buildPositivePrompt(otsShot, List.of(hero, villain), null, "cinematic-realism");
        assertNotNull(prompt);
        assertTrue(prompt.contains("1woman"), "实际出场角色苏清雪的身份设定应透传");
        assertTrue(prompt.contains("white elegant dress"), "实际出场角色苏清雪的服装设定应透传");
        assertTrue(prompt.contains("scar on left cheek"), "实际出场角色杜宁的身份设定应透传");
        assertTrue(prompt.contains("leather jacket"), "实际出场角色杜宁的服装设定应透传");
        assertFalse(prompt.contains("over-the-shoulder composition"), "Java 不根据 OVER_SHOULDER 推断构图");
        assertFalse(prompt.contains("blurred out-of-focus shoulder"), "Java 不推断次要角色虚焦");
    }

    @Test
    @DisplayName("Test 5: 遵循职责归位原则，Java 组装不再擅自篡改语义或正则硬编码替换服装词")
    void testPureAssemblyNoSemanticTampering() {
        DecomposedCharacterVO hero = DecomposedCharacterVO.builder()
                .name("林渊")
                .appearancePrompt("1man, 20yo, pale handsome face, resolute eyes")
                .outfitPrompt("wearing distressed vintage jacket, dark pants, 衣衫褴褛且破烂不堪")
                .build();

        DecomposedShotVO shot = DecomposedShotVO.builder()
                .shotNo(1)
                .shotName("S01-01")
                .shotType("CLOSE_UP")
                .actionDescription("林渊站在雨中，目光坚毅")
                .firstFrameVisual("1man standing in pouring rain, resolute piercing eyes")
                .primaryCharacter("林渊")
                .characterNames(List.of("林渊"))
                .build();

        String prompt = promptBuilder.buildPositivePrompt(shot, List.of(hero), null, "cinematic-realism");
        assertNotNull(prompt);
        assertTrue(prompt.contains("distressed vintage jacket"), "忠实透视角色服装描述");
        assertFalse(prompt.matches(".*[\\u4e00-\\u9fa5]+.*"), "中文描述必须剥离保证纯英文生图");
    }

    @Test
    @DisplayName("Test 6: 纯净组装 - 视觉构图与透视由 AI 源头 firstFrameVisual 统筹，Java 负责角色服装与无损组装")
    void testPerspectiveConsistency_RearView() {
        DecomposedCharacterVO hero = DecomposedCharacterVO.builder()
                .name("夜鹰")
                .appearancePrompt("1man, 28yo, handsome face, short dark hair")
                .outfitPrompt("wearing dark trench coat")
                .build();

        DecomposedShotVO rearShot = DecomposedShotVO.builder()
                .shotNo(1)
                .shotName("S01-01")
                .shotType("MEDIUM_SHOT")
                .cameraMovement("STATIC")
                .actionDescription("夜鹰背对镜头伫立在落地窗前")
                .firstFrameVisual("back view of 1man seen from behind standing in front of floor-to-ceiling glass window")
                .primaryCharacter("夜鹰")
                .characterNames(List.of("夜鹰"))
                .build();

        String prompt = promptBuilder.buildPositivePrompt(rearShot, List.of(hero), null, "cinematic-realism");
        assertNotNull(prompt);
        assertTrue(prompt.contains("back view"), "必须包含 AI 源头输出的背对视点");
        assertTrue(prompt.contains("dark trench coat"), "服装后侧必须保留");
        assertTrue(prompt.contains("short dark hair"), "发型特征必须保留");
    }

    @Test
    @DisplayName("Test 7: 强 ID 道具与宏观构图纯净装配 - 细节粒度由 AI 拆解源头统筹，Java 杜绝硬编码猜谜")
    void testWideShotSuperResolutionDowngrade() {
        DecomposedCharacterVO hero = DecomposedCharacterVO.builder()
                .name("探长")
                .appearancePrompt("1man, 40yo, grey fedora hat")
                .outfitPrompt("wearing brown tweed suit")
                .build();

        com.astra.freyja.dto.script.DecomposedPropVO clockProp = com.astra.freyja.dto.script.DecomposedPropVO.builder()
                .id("PR001")
                .name("复古座钟")
                .propPrompt("antique brass desk clock, vintage mahogany desk")
                .build();

        DecomposedShotVO wideShot = DecomposedShotVO.builder()
                .shotNo(1)
                .shotName("S01-01")
                .shotType("LONG_SHOT")
                .cameraMovement("STATIC")
                .actionDescription("全景展示空旷书房全貌")
                .firstFrameVisual("wide angle view of spacious antique study room, lone detective standing in center")
                .primaryCharacter("探长")
                .characterNames(List.of("探长"))
                .propIds(List.of("PR001"))
                .build();

        String prompt = promptBuilder.buildPositivePrompt(wideShot, List.of(hero), null, List.of(clockProp), "cinematic-realism");
        assertNotNull(prompt);
        assertTrue(prompt.contains("antique brass desk clock"), "道具宏观主体必须保留");
        assertTrue(prompt.contains("wide angle view"), "首帧构图描写必须保留");
    }

    @Test
    @DisplayName("Test 8: 纯英文、实体包含消解去重与废词修剪")
    void testPureEnglishAndSubsumptionDeduplication() {
        DecomposedCharacterVO hero = DecomposedCharacterVO.builder()
                .name("周巡")
                .appearancePrompt("1man, 35yo, short beard, weathered face")
                .outfitPrompt("wearing tailored black business suit")
                .build();

        DecomposedSceneVO scene = DecomposedSceneVO.builder()
                .sceneName("审讯室")
                .scenePrompt("cold sterile interrogation room, rustic wooden desk in center")
                .build();

        com.astra.freyja.dto.script.DecomposedPropVO deskProp = com.astra.freyja.dto.script.DecomposedPropVO.builder()
                .id("PR002")
                .name("木桌")
                .propPrompt("wooden desk")
                .build();

        DecomposedShotVO shot = DecomposedShotVO.builder()
                .shotNo(1)
                .shotName("S01-01")
                .shotType("MEDIUM_SHOT")
                .actionDescription("周巡坐在深褐色大木桌后面，穿着黑色西装")
                .firstFrameVisual("1man sitting behind a large rustic wooden desk in cold room, black business suit")
                .primaryCharacter("周巡")
                .characterNames(List.of("周巡"))
                .propIds(List.of("PR002"))
                .build();

        String prompt = promptBuilder.buildPositivePrompt(shot, List.of(hero), scene, List.of(deskProp), "cinematic-realism");
        assertNotNull(prompt);
        assertFalse(prompt.matches(".*[\\u4e00-\\u9fa5]+.*"), "必须 100% 纯英文，无中文");

        // 验证包含消解：短语 "wooden desk" 应被 "rustic wooden desk in center" 或更丰富短语包含去重
        // 且 "black business suit" 重复项被去重
        int suitCount = org.apache.commons.lang3.StringUtils.countMatches(prompt, "black business suit");
        assertEquals(1, suitCount, "重复出现的 black business suit 必须被去重为单个实体声明");
    }

    @Test
    @DisplayName("Test 9: 动漫风格自动注入轻线稿与防粗黑描边负向提示词")
    void testAnimeNegativePromptInjection() {
        // 普通写实风格
        String realNeg = promptBuilder.buildNegativePrompt("cinematic-realism", "ugly face");
        assertNotNull(realNeg);
        assertTrue(realNeg.contains("ugly face"));
        assertFalse(realNeg.contains("thick black outline"), "写实风格不包含动漫轻线稿专属负向词");

        // 动漫风格 (anime-2d)
        String animeNeg = promptBuilder.buildNegativePrompt("anime-2d", "lowres");
        assertNotNull(animeNeg);
        assertTrue(animeNeg.contains("lowres"));
        assertTrue(animeNeg.contains("thick black outline"), "动漫风格必须自动注入防粗黑描边负向词");
        assertTrue(animeNeg.contains("heavy lineart"));
        assertTrue(animeNeg.contains("hard cel shading"));
        assertTrue(animeNeg.contains("manga ink lines"));
    }

}
