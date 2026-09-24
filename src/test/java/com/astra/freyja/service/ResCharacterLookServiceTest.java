package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.ResCharacterLookMapper;
import com.astra.freyja.dao.ResCharacterMapper;
import com.astra.freyja.dto.res.ResCharacterLookDTO;
import com.astra.freyja.entity.ResCharacterLook;
import com.astra.freyja.service.impl.ResCharacterLookServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResCharacterLookServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ResCharacterLook.class);
    }

    @Mock
    private ResCharacterLookMapper lookMapper;

    @Mock
    private ResCharacterMapper characterMapper;

    @Mock
    private DramaMapper dramaMapper;

    @Mock
    private AiModelFactory aiModelFactory;

    @Mock
    private SysConfigService sysConfigService;

    private ResCharacterLookService lookService;

    @BeforeEach
    void setUp() {
        lookService = new ResCharacterLookServiceImpl(lookMapper, characterMapper, dramaMapper, aiModelFactory, sysConfigService);
    }

    @Test
    @DisplayName("创建第一个造型时，若未显式指定默认则自动设为默认造型 (isDefault=1)")
    void testCreate_firstLook_autoSetDefault() {
        ResCharacterLookDTO dto = new ResCharacterLookDTO();
        dto.setCharacterId(100L);
        dto.setLookName("日常便服");

        when(lookMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        lookService.create(dto);

        ArgumentCaptor<ResCharacterLook> captor = ArgumentCaptor.forClass(ResCharacterLook.class);
        verify(lookMapper).insert(captor.capture());
        ResCharacterLook inserted = captor.getValue();
        assertEquals(1, inserted.getIsDefault());
        assertEquals("日常便服", inserted.getLookName());
    }

    @Test
    @DisplayName("删除默认造型时，自动将下一个启用的可用造型提升为默认造型")
    void testDelete_defaultLook_promotesNextDefault() {
        ResCharacterLook currentDefault = new ResCharacterLook();
        currentDefault.setId(10L);
        currentDefault.setCharacterId(100L);
        currentDefault.setIsDefault(1);

        ResCharacterLook nextAvailable = new ResCharacterLook();
        nextAvailable.setId(20L);
        nextAvailable.setCharacterId(100L);
        nextAvailable.setLookName("备用晚礼服");
        nextAvailable.setIsDefault(0);
        nextAvailable.setStatus(1);

        when(lookMapper.selectById(10L)).thenReturn(currentDefault);
        when(lookMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(nextAvailable));

        lookService.delete(10L);

        verify(lookMapper).deleteById(10L);
        // 验证剩余造型被更新为默认
        ArgumentCaptor<ResCharacterLook> updateCaptor = ArgumentCaptor.forClass(ResCharacterLook.class);
        verify(lookMapper).updateById(updateCaptor.capture());
        assertEquals(1, updateCaptor.getValue().getIsDefault());
        assertEquals(20L, updateCaptor.getValue().getId());
    }

    @Test
    @DisplayName("更新造型时禁止篡改绑定的角色ID，防跨人物串绑")
    void testUpdate_mismatchedCharacterId_throws400() {
        ResCharacterLook exist = new ResCharacterLook();
        exist.setId(10L);
        exist.setCharacterId(100L);

        when(lookMapper.selectById(10L)).thenReturn(exist);

        ResCharacterLookDTO dto = new ResCharacterLookDTO();
        dto.setId(10L);
        dto.setCharacterId(200L); // 试图串绑给角色 200

        BizException ex = assertThrows(BizException.class, () -> lookService.update(dto));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不允许把其他人物的造型绑定到当前人物"));
    }
}
