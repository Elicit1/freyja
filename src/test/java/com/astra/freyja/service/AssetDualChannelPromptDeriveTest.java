package com.astra.freyja.service;

import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.ResPropMapper;
import com.astra.freyja.dao.ResSceneMapper;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.entity.ResProp;
import com.astra.freyja.entity.ResScene;
import com.astra.freyja.service.impl.ResPropServiceImpl;
import com.astra.freyja.service.impl.ResSceneServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetDualChannelPromptDeriveTest {

    @Mock
    private ResPropMapper propMapper;
    @Mock
    private ResSceneMapper sceneMapper;
    @Mock
    private DramaMapper dramaMapper;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private AiModelFactory aiModelFactory;

    @InjectMocks
    private ResPropServiceImpl propService;

    @InjectMocks
    private ResSceneServiceImpl sceneService;

    @Test
    @DisplayName("Prop: buildPromptPackage 与 parseAndValidateDerivedPrompt 全流程")
    void testPropDualChannelFlow() {
        when(sysConfigService.getConfigValue(eq("ai.prompt.prop_prompt_enrich_system"), anyString()))
                .thenReturn("Prop System Prompt");

        PropPromptDeriveDTO dto = new PropPromptDeriveDTO();
        dto.setName("龙渊古剑");
        dto.setPropType("WEAPON");
        dto.setDescription("剑身如一泓秋水，铭刻繁复龙纹，剑柄嵌有青色古玉");
        dto.setStylePreset("cinematic-realism");

        AssetPromptPackageVO packageVO = propService.buildPromptPackage(dto);
        assertNotNull(packageVO);
        assertEquals("PROP", packageVO.getAssetType());
        assertEquals("龙渊古剑", packageVO.getAssetName());
        assertTrue(packageVO.getContextFingerprint().startsWith("sha256:"));

        String rawJson = """
                ```json
                {
                  "propPrompt": "cinematic product photography, 35mm macro lens, ancient chinese longsword with dragon engravings, dark steel blade, jade embedded hilt",
                  "negativePrompt": "hands, human body, person, complex background"
                }
                ```
                """;

        PropPromptParseRequestDTO request = PropPromptParseRequestDTO.builder()
                .name("龙渊古剑")
                .propType("WEAPON")
                .description("剑身如一泓秋水，铭刻繁复龙纹，剑柄嵌有青色古玉")
                .stylePreset("cinematic-realism")
                .rawResponse(rawJson)
                .contextFingerprint(packageVO.getContextFingerprint())
                .build();

        PropPromptValidationResult validationResult = propService.parseAndValidateDerivedPrompt(request);
        assertNotNull(validationResult);
        assertFalse(validationResult.hasErrors());
        assertTrue(validationResult.getFingerprintMatched());
        assertNotNull(validationResult.getResult().getPropPrompt());
        assertTrue(validationResult.getResult().getPropPrompt().contains("dragon engravings"));
    }

    @Test
    @DisplayName("Scene: buildPromptPackage 与 parseAndValidateDerivedPrompt 全流程")
    void testSceneDualChannelFlow() {
        when(sysConfigService.getConfigValue(eq("ai.prompt.scene_prompt_enrich_system"), anyString()))
                .thenReturn("Scene System Prompt");

        ScenePromptDeriveDTO dto = new ScenePromptDeriveDTO();
        dto.setName("青云大殿");
        dto.setSceneType("INDOOR");
        dto.setTimeOfDay("SUNSET");
        dto.setWeatherAtmosphere("夕阳余晖，庄严肃穆");
        dto.setDescription("宏伟的修仙宗门大殿，九根蟠龙汉白玉巨柱，青石地面倒映残阳金光");

        AssetPromptPackageVO packageVO = sceneService.buildPromptPackage(dto);
        assertNotNull(packageVO);
        assertEquals("SCENE", packageVO.getAssetType());
        assertEquals("青云大殿", packageVO.getAssetName());
        assertTrue(packageVO.getContextFingerprint().startsWith("sha256:"));

        String rawJson = """
                {
                  "scenePrompt": "cinematic film still, 35mm photography, grand ancient chinese daoist temple hall, nine massive white marble dragon pillars, sunset golden light reflections on stone floor",
                  "negativePrompt": "people, crowds, modern buildings, daylight"
                }
                """;

        ScenePromptParseRequestDTO request = ScenePromptParseRequestDTO.builder()
                .name("青云大殿")
                .sceneType("INDOOR")
                .timeOfDay("SUNSET")
                .weatherAtmosphere("夕阳余晖，庄严肃穆")
                .description("宏伟的修仙宗门大殿，九根蟠龙汉白玉巨柱，青石地面倒映残阳金光")
                .rawResponse(rawJson)
                .contextFingerprint(packageVO.getContextFingerprint())
                .build();

        ScenePromptValidationResult validationResult = sceneService.parseAndValidateDerivedPrompt(request);
        assertNotNull(validationResult);
        assertFalse(validationResult.hasErrors());
        assertTrue(validationResult.getFingerprintMatched());
        assertNotNull(validationResult.getResult().getScenePrompt());
        assertTrue(validationResult.getResult().getScenePrompt().contains("grand ancient chinese"));
    }
}
