package com.astra.freyja.service;

import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dao.ResCharacterOutfitMapper;
import com.astra.freyja.dao.ResSceneMapper;
import com.astra.freyja.dto.res.CharacterShotRefDTO;
import com.astra.freyja.dto.res.PromptAssembleRequestDTO;
import com.astra.freyja.dto.res.PromptAssembleResultVO;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.entity.ResCharacterOutfit;
import com.astra.freyja.entity.ResScene;
import com.astra.freyja.service.impl.PromptAssembleServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromptAssembleServiceTest {

    @Mock
    private ResSceneMapper sceneMapper;

    @Mock
    private ResCharacterMapper characterMapper;

    @Mock
    private ResCharacterOutfitMapper outfitMapper;

    @Mock
    private SysConfigService sysConfigService;

    private CharacterVisualAssetResolver characterVisualAssetResolver;
    private PromptAssembleServiceImpl promptAssembleService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        characterVisualAssetResolver = new com.astra.freyja.service.impl.CharacterVisualAssetResolverImpl(characterMapper, outfitMapper);
        promptAssembleService = new PromptAssembleServiceImpl(sceneMapper, characterMapper, outfitMapper, sysConfigService, new com.fasterxml.jackson.databind.ObjectMapper(), characterVisualAssetResolver);
    }

    @Test
    void testAssembleFullPromptSuccess() {
        lenient().when(sysConfigService.getConfigValue(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));

        // 1. 模拟场景
        ResScene scene = new ResScene();
        scene.setId(10L);
        scene.setName("顶层现代豪华办公室");
        scene.setScenePrompt("modern luxury office at high floor, large floor-to-ceiling windows, city skyline background, dusk lighting");
        scene.setNegativePrompt("daylight, cluttered desks");
        scene.setLoraName("office_arch.safetensors");
        scene.setLoraWeight(new BigDecimal("0.75"));
        scene.setReferenceImageUrl("http://127.0.0.1:9000/video-assets/scene/office.jpg");

        // 2. 模拟角色与造型
        ResCharacter character = new ResCharacter();
        character.setId(100L);
        character.setName("林晨");
        character.setTriggerWords("linchen, 1man");
        character.setAppearancePrompt("handsome, sharp jawline, short black hair, amber eyes");
        character.setNegativePrompt("ugly, extra arms");
        character.setLoraName("linchen_face_v2.safetensors");
        character.setLoraWeight(new BigDecimal("0.85"));
        character.setReferenceImageUrl("http://127.0.0.1:9000/video-assets/character/linchen_ref.png");

        ResCharacterOutfit outfit = new ResCharacterOutfit();
        outfit.setId(200L);
        outfit.setCharacterId(100L);
        outfit.setOutfitName("深色战术风衣");
        outfit.setOutfitPrompt("wearing dark long trench coat, leather gloves");
        outfit.setLoraName("tactical_coat.safetensors");
        outfit.setLoraWeight(new BigDecimal("0.60"));
        outfit.setReferenceImageUrl("http://127.0.0.1:9000/video-assets/outfit/linchen_coat.png");

        when(sceneMapper.selectById(10L)).thenReturn(scene);
        when(characterMapper.selectById(100L)).thenReturn(character);
        when(outfitMapper.selectById(200L)).thenReturn(outfit);

        // 3. 构建请求
        PromptAssembleRequestDTO request = new PromptAssembleRequestDTO();
        request.setSceneId(10L);
        request.setStylePreset("cinematic-realism");
        request.setShotPrompt("medium close-up shot of protagonist, intense atmosphere");
        request.setCustomNegativePrompt("cartoony, bad hands");

        // 绑定角色与造型
        CharacterShotRefDTO charParam = new CharacterShotRefDTO();
        charParam.setCharacterId(100L);
        charParam.setLookId(200L);
        charParam.setActionPrompt("holding a crystal whiskey glass");
        request.setCharacterRefs(List.of(charParam));

        // 4. 执行组装
        PromptAssembleResultVO result = promptAssembleService.assemble(request);

        // 5. 验证结果
        assertNotNull(result);
        assertNotNull(result.getPositivePrompt());
        assertNotNull(result.getNegativePrompt());

        // 验证正向词包含关键元素 (纯净剧本场景与角色)
        assertTrue(result.getPositivePrompt().contains("modern luxury office"));
        assertTrue(result.getPositivePrompt().contains("handsome, sharp jawline"));
        assertTrue(result.getPositivePrompt().contains("wearing dark long trench coat"));
        assertTrue(result.getPositivePrompt().contains("holding a crystal whiskey glass"));
        assertTrue(result.getPositivePrompt().contains("medium close-up shot"));
        assertTrue(result.getPositivePrompt().contains("intense atmosphere"));

        // 验证负向词包含场景负向、角色负向与自定义负向
        assertTrue(result.getNegativePrompt().contains("daylight"));
        assertTrue(result.getNegativePrompt().contains("ugly"));
        assertTrue(result.getNegativePrompt().contains("cartoony"));

        // 验证 videoPrompt 双轨生成
        assertNotNull(result.getVideoPrompt());
        assertTrue(result.getVideoPrompt().contains("medium close-up shot"));

        // 验证 LoRA 列表已清空 (全面转向参考图工作流)
        assertEquals(0, result.getLoraList().size());

        // 验证 ControlImages (场景参考图 + 角色单图)
        assertEquals(2, result.getControlImages().size());
        assertTrue(result.getControlImages().stream().anyMatch(c -> "CHARACTER_REF".equals(c.getControlType())));
        assertTrue(result.getControlImages().stream().anyMatch(c -> "SCENE_REF".equals(c.getControlType())));
    }

    @Test
    void testAssembleWithoutMechanicalStateTokens() {
        lenient().when(sysConfigService.getConfigValue(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));

        PromptAssembleRequestDTO request = new PromptAssembleRequestDTO();
        request.setShotPrompt("a man walking down a quiet street");

        PromptAssembleResultVO result = promptAssembleService.assemble(request);
        assertNotNull(result);
        assertTrue(result.getPositivePrompt().contains("a man walking down a quiet street"));
    }
}
