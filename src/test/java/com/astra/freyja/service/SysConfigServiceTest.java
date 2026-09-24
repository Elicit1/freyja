package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.SysConfigMapper;
import com.astra.freyja.dto.config.SysConfigDTO;
import com.astra.freyja.dto.config.SysConfigQuery;
import com.astra.freyja.entity.SysConfig;
import com.astra.freyja.service.impl.SysConfigServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SysConfigServiceTest {

    @Mock
    private SysConfigMapper sysConfigMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private SysConfigServiceImpl sysConfigService;

    @Test
    void testGetConfigValueFromRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("freyja:config:test.key")).thenReturn("cached_value");

        String val = sysConfigService.getConfigValue("test.key", "default");
        assertEquals("cached_value", val);
        verify(sysConfigMapper, never()).selectOne(any());
    }

    @Test
    void testGetConfigValueFromDbAndCacheIt() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("freyja:config:test.key")).thenReturn(null);

        SysConfig config = new SysConfig();
        config.setConfigKey("test.key");
        config.setConfigValue("db_value");
        config.setStatus(1);

        when(sysConfigMapper.selectOne(any())).thenReturn(config);

        String val = sysConfigService.getConfigValue("test.key", "default");
        assertEquals("db_value", val);
        verify(valueOperations, times(1)).set(eq("freyja:config:test.key"), eq("db_value"));
    }

    @Test
    void testGetConfigValueNotFoundReturnsDefault() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("freyja:config:non.exist")).thenReturn(null);
        when(sysConfigMapper.selectOne(any())).thenReturn(null);

        String val = sysConfigService.getConfigValue("non.exist", "fallback_default");
        assertEquals("fallback_default", val);
    }

    @Test
    void testGetConfigIntAndBool() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("freyja:config:timeout")).thenReturn("300");
        when(valueOperations.get("freyja:config:enable_feature")).thenReturn("true");

        assertEquals(300, sysConfigService.getConfigInt("timeout", 100));
        assertTrue(sysConfigService.getConfigBool("enable_feature", false));
    }

    @Test
    void testCreateConfigSuccess() {
        SysConfigDTO dto = new SysConfigDTO();
        dto.setConfigName("测试配置");
        dto.setConfigKey("custom.test.key");
        dto.setConfigValue("custom_val");
        dto.setConfigType("STRING");
        dto.setStatus(1);

        when(sysConfigMapper.selectCount(any())).thenReturn(0L);
        when(sysConfigMapper.insert(any(SysConfig.class))).thenReturn(1);

        assertDoesNotThrow(() -> sysConfigService.create(dto));
        verify(sysConfigMapper, times(1)).insert(any(SysConfig.class));
    }

    @Test
    void testCreateDuplicateKeyThrows() {
        SysConfigDTO dto = new SysConfigDTO();
        dto.setConfigName("测试配置");
        dto.setConfigKey("custom.test.key");
        dto.setConfigValue("custom_val");

        when(sysConfigMapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class, () -> sysConfigService.create(dto));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    void testDeleteBuiltinThrows() {
        SysConfig config = new SysConfig();
        config.setId(1L);
        config.setConfigKey("builtin.key");
        config.setIsBuiltin(1);

        when(sysConfigMapper.selectById(1L)).thenReturn(config);

        BizException ex = assertThrows(BizException.class, () -> sysConfigService.delete(1L));
        assertTrue(ex.getMessage().contains("系统内置参数配置禁止删除"));
        verify(sysConfigMapper, never()).deleteById(anyLong());
    }

    @Test
    void testDeleteNonBuiltinSuccess() {
        SysConfig config = new SysConfig();
        config.setId(2L);
        config.setConfigKey("user.key");
        config.setIsBuiltin(0);

        when(sysConfigMapper.selectById(2L)).thenReturn(config);
        when(sysConfigMapper.deleteById(2L)).thenReturn(1);

        assertDoesNotThrow(() -> sysConfigService.delete(2L));
        verify(sysConfigMapper, times(1)).deleteById(2L);
    }
}
