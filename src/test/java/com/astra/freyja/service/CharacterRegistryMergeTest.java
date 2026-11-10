package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.res.CharacterMergeDTO;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.impl.CharacterRegistryServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CharacterRegistryMergeTest {
    @Test void invalidShotReferencesAbortMergeBeforeSourceIsDisabled() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "merge-test");
        for (var type : List.of(ResCharacterAlias.class, ResCharacterEvidence.class, ResCharacterLook.class, DramaShot.class))
            TableInfoHelper.initTableInfo(assistant, type);
        var characters = mock(ResCharacterMapper.class);
        var shots = mock(DramaShotMapper.class);
        var service = new CharacterRegistryServiceImpl(characters, mock(ResCharacterAliasMapper.class),
                mock(ResCharacterEvidenceMapper.class), mock(ResCharacterResolutionMapper.class),
                mock(ResCharacterLookMapper.class), shots, new ObjectMapper());
        var source = new ResCharacter(); source.setId(1L);
        var target = new ResCharacter(); target.setId(2L);
        when(characters.selectById(1L)).thenReturn(source);
        when(characters.selectById(2L)).thenReturn(target);
        var shot = new DramaShot(); shot.setId(3L); shot.setCharacterRefsJson("[{\"characterId\":1");
        when(shots.selectList(any())).thenReturn(List.of(shot));
        var request = new CharacterMergeDTO(); request.setSourceCharacterId(1L); request.setTargetCharacterId(2L);
        assertTrue(assertThrows(BizException.class, () -> service.mergeCharacter(request)).getMessage().contains("合并已回滚"));
        verify(characters, never()).updateById(any(ResCharacter.class));
        assertNull(source.getMergedToId());
    }
}
