package com.astra.freyja.service;

import com.astra.freyja.dao.*;
import com.astra.freyja.dto.dashboard.DashboardStatsVO;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.service.impl.DashboardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Drama.class);
    }

    @Mock
    private DramaMapper dramaMapper;
    @Mock
    private DramaEpisodeMapper episodeMapper;
    @Mock
    private DramaSceneMapper sceneMapper;
    @Mock
    private DramaShotMapper shotMapper;
    @Mock
    private ResCharacterMapper characterMapper;
    @Mock
    private ResSceneMapper resSceneMapper;
    @Mock
    private ResPropMapper resPropMapper;
    @Mock
    private AiProviderMapper aiProviderMapper;

    @InjectMocks
    private DashboardServiceImpl dashboardService;

    @Test
    @DisplayName("测试获取工作台首页统计数据")
    void testGetDashboardStats() {
        when(dramaMapper.selectCount(isNull())).thenReturn(3L);
        when(episodeMapper.selectCount(isNull())).thenReturn(10L);
        when(sceneMapper.selectCount(isNull())).thenReturn(25L);
        when(shotMapper.selectCount(isNull())).thenReturn(120L);
        when(characterMapper.selectCount(isNull())).thenReturn(8L);
        when(resSceneMapper.selectCount(isNull())).thenReturn(5L);
        when(resPropMapper.selectCount(isNull())).thenReturn(12L);
        when(aiProviderMapper.selectCount(any())).thenReturn(2L);

        Drama d1 = new Drama();
        d1.setId(1L);
        d1.setTitle("豪门总裁归来");
        d1.setGenre("DOMINANT_CEO");
        d1.setStatus("IN_PROGRESS");
        d1.setUpdateTime(LocalDateTime.now());

        when(dramaMapper.selectList(any())).thenReturn(List.of(d1));
        when(episodeMapper.selectCount(isNotNull())).thenReturn(2L);
        when(shotMapper.selectCount(isNotNull())).thenReturn(15L);

        DashboardStatsVO stats = dashboardService.getDashboardStats();

        assertNotNull(stats);
        assertEquals(3L, stats.getDramaCount());
        assertEquals(10L, stats.getEpisodeCount());
        assertEquals(25L, stats.getSceneCount());
        assertEquals(120L, stats.getShotCount());
        assertEquals(8L, stats.getCharacterCount());
        assertEquals(5L, stats.getSceneAssetCount());
        assertEquals(12L, stats.getPropCount());
        assertEquals(25L, stats.getTotalAssetCount()); // 8 + 5 + 12
        assertEquals(2L, stats.getAiProviderCount());
        assertTrue(stats.getAiServiceReady());
        assertNotNull(stats.getRecentDramas());
        assertEquals(1, stats.getRecentDramas().size());
        assertEquals("豪门总裁归来", stats.getRecentDramas().get(0).getTitle());
    }
}
