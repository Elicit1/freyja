package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dao.ResCharacterOutfitMapper;
import com.astra.freyja.dto.res.ResCharacterDTO;
import com.astra.freyja.dto.res.ResCharacterOptionVO;
import com.astra.freyja.dto.res.ResCharacterOutfitVO;
import com.astra.freyja.dto.res.ResCharacterVO;
import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.entity.ResCharacterOutfit;
import com.astra.freyja.service.impl.ResCharacterServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
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
class ResCharacterServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ResCharacter.class);
        TableInfoHelper.initTableInfo(assistant, ResCharacterOutfit.class);
    }

    @Mock
    private ResCharacterMapper characterMapper;

    @Mock
    private ResCharacterOutfitMapper outfitMapper;

    @Mock
    private ResCharacterOutfitService outfitService;

    @Mock
    private CharacterRegistryService characterRegistryService;

    @InjectMocks
    private ResCharacterServiceImpl characterService;

    @Test
    void testCreateCharacterSuccessAndCreatesDefaultOutfit() {
        ResCharacterDTO dto = new ResCharacterDTO();
        dto.setName("林晨");
        dto.setGender("MALE");
        dto.setRoleType("PROTAGONIST");
        dto.setAppearancePrompt("handsome, black hair, 20yo");
        dto.setTriggerWords("linchen");
        dto.setLoraName("linchen_lora_v1.safetensors");
        dto.setLoraWeight(new BigDecimal("0.85"));
        dto.setDefaultOutfitName("日常便服");
        dto.setDefaultOutfitPrompt("wearing white shirt, black pants");

        doAnswer(invocation -> {
            ResCharacter c = invocation.getArgument(0);
            c.setId(1001L);
            return 1;
        }).when(characterMapper).insert(any(ResCharacter.class));

        Long id = characterService.create(dto);

        assertEquals(1001L, id);
        verify(characterMapper, times(1)).insert(any(ResCharacter.class));
        verify(outfitService, never()).create(any());
        verify(characterRegistryService, times(1)).addAlias(any());
        verify(characterRegistryService, times(1)).recordEvidence(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testCreateCharacterNameBlankThrows() {
        ResCharacterDTO dto = new ResCharacterDTO();
        dto.setName("  ");

        BizException ex = assertThrows(BizException.class, () -> characterService.create(dto));
        assertEquals("人物名称不能为空", ex.getMessage());
        verify(characterMapper, never()).insert(any(ResCharacter.class));
    }

    @Test
    void testGetByIdSuccess() {
        ResCharacter character = new ResCharacter();
        character.setId(1001L);
        character.setName("林晨");
        character.setGender("MALE");

        ResCharacterOutfitVO outfitVO = new ResCharacterOutfitVO();
        outfitVO.setId(2001L);
        outfitVO.setCharacterId(1001L);
        outfitVO.setOutfitName("日常便服");
        outfitVO.setIsDefault(1);

        when(characterMapper.selectById(1001L)).thenReturn(character);
        when(outfitService.listByCharacterId(1001L)).thenReturn(List.of(outfitVO));

        ResCharacterVO vo = characterService.getById(1001L);
        assertNotNull(vo);
        assertEquals("林晨", vo.getName());
        assertEquals(1, vo.getOutfitCount());
        assertNotNull(vo.getDefaultOutfit());
        assertEquals("日常便服", vo.getDefaultOutfit().getOutfitName());
    }

    @Test
    void testDeleteCascade() {
        ResCharacter character = new ResCharacter();
        character.setId(1001L);

        when(characterMapper.selectById(1001L)).thenReturn(character);

        characterService.delete(1001L);

        verify(characterMapper, times(1)).deleteById(1001L);
        verify(outfitMapper, times(1)).delete(any());
    }

    @Test
    void testOptions() {
        ResCharacter character = new ResCharacter();
        character.setId(1001L);
        character.setName("林晨");
        character.setRoleType("PROTAGONIST");

        ResCharacterOutfit outfit = new ResCharacterOutfit();
        outfit.setId(2001L);
        outfit.setCharacterId(1001L);
        outfit.setOutfitName("日常便服");
        outfit.setIsDefault(1);
        outfit.setStatus(1);

        when(characterMapper.selectList(any())).thenReturn(List.of(character));
        when(outfitMapper.selectList(any())).thenReturn(List.of(outfit));

        List<ResCharacterOptionVO> options = characterService.options(1L);
        assertEquals(1, options.size());
        assertEquals("林晨", options.get(0).getName());
        assertEquals(1, options.get(0).getOutfits().size());
    }

}
