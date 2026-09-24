package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.ResCharacterLookMapper;
import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dto.res.ResolvedCharacterVisual;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.entity.ResCharacterLook;
import com.astra.freyja.service.impl.CharacterVisualAssetResolverImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterVisualAssetResolverTest {

    @Mock
    private ResCharacterMapper characterMapper;

    @Mock
    private ResCharacterLookMapper lookMapper;

    private CharacterVisualAssetResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CharacterVisualAssetResolverImpl(characterMapper, lookMapper);
    }

    @Test
    @DisplayName("测试造型有参考图时，决策为 COMBINED 且提取专属图文")
    void testResolve_outfitHasImage_returnsCombined() {
        ResCharacter character = new ResCharacter();
        character.setId(1L);
        character.setName("叶辰");
        character.setAppearancePrompt("handsome man, black eyes");
        character.setNegativePrompt("bad anatomy");

        ResCharacterLook look = new ResCharacterLook();
        look.setId(10L);
        look.setCharacterId(1L);
        look.setLookName("战术装");
        look.setOutfitPrompt("wearing black tactical vest");
        look.setReferenceImageUrl("http://minio/outfit/tactical.png");
        look.setNegativePrompt("colorful clothes");
        look.setStatus(1);

        when(characterMapper.selectById(1L)).thenReturn(character);
        when(lookMapper.selectById(10L)).thenReturn(look);

        ResolvedCharacterVisual visual = resolver.resolve(1L, 10L);

        assertNotNull(visual);
        assertEquals("COMBINED", visual.getReferenceRole());
        assertEquals("http://minio/outfit/tactical.png", visual.getReferenceImageUrl());
        assertTrue(visual.getPositivePromptSegments().contains("handsome man, black eyes"));
        assertTrue(visual.getPositivePromptSegments().contains("wearing black tactical vest"));
        assertTrue(visual.getNegativePromptSegments().contains("bad anatomy"));
        assertTrue(visual.getNegativePromptSegments().contains("colorful clothes"));
    }

    @Test
    @DisplayName("测试指定造型无参考图时，绝不回退借图，参考图必须为 null")
    void testResolve_outfitLacksImage_neverFallback() {
        ResCharacter character = new ResCharacter();
        character.setId(1L);
        character.setName("叶辰");
        character.setAppearancePrompt("handsome man, black eyes");

        ResCharacterLook look = new ResCharacterLook();
        look.setId(10L);
        look.setCharacterId(1L);
        look.setLookName("便服");
        look.setOutfitPrompt("white hoodie");
        look.setReferenceImageUrl(null);
        look.setStatus(1);

        when(characterMapper.selectById(1L)).thenReturn(character);
        when(lookMapper.selectById(10L)).thenReturn(look);

        ResolvedCharacterVisual visual = resolver.resolve(1L, 10L);

        assertNotNull(visual);
        assertNull(visual.getReferenceRole());
        assertNull(visual.getReferenceImageUrl());
        assertTrue(visual.getPositivePromptSegments().contains("white hoodie"));
        assertTrue(visual.getPositivePromptSegments().contains("handsome man, black eyes"));
    }

    @Test
    @DisplayName("测试造型属于其他人物时，抛出400业务异常严防串色")
    void testResolve_outfitBelongsToOtherCharacter_throws400() {
        ResCharacter character = new ResCharacter();
        character.setId(1L);
        character.setName("叶辰");

        ResCharacterLook otherLook = new ResCharacterLook();
        otherLook.setId(99L);
        otherLook.setCharacterId(2L); // 属于角色 2
        otherLook.setStatus(1);

        when(characterMapper.selectById(1L)).thenReturn(character);
        when(lookMapper.selectById(99L)).thenReturn(otherLook);

        BizException ex = assertThrows(BizException.class, () -> resolver.resolve(1L, 99L));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("所选造型不属于指定人物"));
    }
}
