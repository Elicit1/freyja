package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaEpisodeMapper;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.DramaSceneMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.ResSceneMapper;
import com.astra.freyja.dto.drama.DramaDTO;
import com.astra.freyja.dto.drama.DramaOptionVO;
import com.astra.freyja.dto.drama.DramaQuery;
import com.astra.freyja.dto.drama.DramaStatsVO;
import com.astra.freyja.dto.drama.DramaTreeVO;
import com.astra.freyja.dto.drama.DramaVO;
import com.astra.freyja.dto.drama.EpisodeTreeVO;
import com.astra.freyja.dto.drama.SceneTreeVO;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.entity.DramaEpisode;
import com.astra.freyja.entity.DramaScene;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.service.impl.DramaServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
class DramaServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Drama.class);
        TableInfoHelper.initTableInfo(assistant, DramaEpisode.class);
        TableInfoHelper.initTableInfo(assistant, DramaScene.class);
        TableInfoHelper.initTableInfo(assistant, DramaShot.class);
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
    private com.astra.freyja.dao.DramaShotGroupMapper shotGroupMapper;

    @Mock
    private ResSceneMapper resSceneMapper;

    @InjectMocks
    private DramaServiceImpl dramaService;

    @Test
    void testCreateDramaSuccess() {
        DramaDTO dto = new DramaDTO();
        dto.setTitle("战神归来");
        dto.setGenre("WAR_GOD");
        dto.setTargetEpisodes(100);

        doAnswer(invocation -> {
            Drama d = invocation.getArgument(0);
            d.setId(101L);
            return 1;
        }).when(dramaMapper).insert(any(Drama.class));

        Long id = dramaService.create(dto);
        assertEquals(101L, id);
        verify(dramaMapper, times(1)).insert(any(Drama.class));
    }

    @Test
    void testCreateDramaTitleBlankThrows() {
        DramaDTO dto = new DramaDTO();
        dto.setTitle("  ");

        BizException ex = assertThrows(BizException.class, () -> dramaService.create(dto));
        assertEquals("短剧名称不能为空", ex.getMessage());
    }

    @Test
    void testGetDramaTree() {
        Drama drama = new Drama();
        drama.setId(1L);
        drama.setTitle("霸总甜宠");
        drama.setGenre("DOMINANT_CEO");

        DramaEpisode episode = new DramaEpisode();
        episode.setId(10L);
        episode.setDramaId(1L);
        episode.setEpisodeNo(1);
        episode.setTitle("第1集");

        DramaScene scene = new DramaScene();
        scene.setId(100L);
        scene.setDramaId(1L);
        scene.setEpisodeId(10L);
        scene.setSceneNo(1);
        scene.setName("酒店大堂");

        DramaShot shot1 = new DramaShot();
        shot1.setId(1000L);
        shot1.setDramaId(1L);
        shot1.setEpisodeId(10L);
        shot1.setSceneId(100L);
        shot1.setShotNo(1);
        shot1.setDuration(new BigDecimal("3.50"));
        shot1.setRenderStatus("SUCCESS");

        DramaShot shot2 = new DramaShot();
        shot2.setId(1001L);
        shot2.setDramaId(1L);
        shot2.setEpisodeId(10L);
        shot2.setSceneId(100L);
        shot2.setShotNo(2);
        shot2.setDuration(new BigDecimal("2.50"));
        shot2.setRenderStatus("INIT");

        when(dramaMapper.selectById(1L)).thenReturn(drama);
        when(episodeMapper.selectList(any())).thenReturn(List.of(episode));
        when(sceneMapper.selectList(any())).thenReturn(List.of(scene));
        when(shotMapper.selectList(any())).thenReturn(List.of(shot1, shot2));

        DramaTreeVO tree = dramaService.getDramaTree(1L);
        assertNotNull(tree);
        assertEquals(1L, tree.getId());
        assertEquals("霸总甜宠", tree.getTitle());
        assertEquals(1, tree.getTotalEpisodes());
        assertEquals(1, tree.getTotalScenes());
        assertEquals(2, tree.getTotalShots());
        assertEquals(1, tree.getRenderedShots());
        assertEquals(50, tree.getProgressPercentage());

        assertEquals(1, tree.getEpisodes().size());
        EpisodeTreeVO epNode = tree.getEpisodes().get(0);
        assertEquals(1, epNode.getScenes().size());
        SceneTreeVO scNode = epNode.getScenes().get(0);
        assertEquals(2, scNode.getShots().size());
    }

    @Test
    void testGetStats() {
        Drama drama = new Drama();
        drama.setId(1L);
        drama.setTitle("霸总甜宠");
        drama.setTargetEpisodes(80);

        DramaShot shot1 = new DramaShot();
        shot1.setDuration(new BigDecimal("3.00"));
        shot1.setRenderStatus("SUCCESS");

        DramaShot shot2 = new DramaShot();
        shot2.setDuration(new BigDecimal("4.00"));
        shot2.setRenderStatus("RENDERING");

        when(dramaMapper.selectById(1L)).thenReturn(drama);
        when(episodeMapper.selectCount(any())).thenReturn(5L);
        when(sceneMapper.selectCount(any())).thenReturn(10L);
        when(shotMapper.selectList(any())).thenReturn(List.of(shot1, shot2));

        DramaStatsVO stats = dramaService.getStats(1L);
        assertNotNull(stats);
        assertEquals(5, stats.getActualEpisodes());
        assertEquals(10, stats.getTotalScenes());
        assertEquals(2, stats.getTotalShots());
        assertEquals(1, stats.getRenderedShots());
        assertEquals(1, stats.getRenderingShots());
        assertEquals(new BigDecimal("7.00"), stats.getTotalEstimatedDuration());
    }

    @Test
    void testOptions() {
        Drama drama = new Drama();
        drama.setId(1L);
        drama.setTitle("都市神豪");
        drama.setGenre("URBAN_ABILITY");

        when(dramaMapper.selectList(any())).thenReturn(List.of(drama));

        List<DramaOptionVO> options = dramaService.options();
        assertEquals(1, options.size());
        assertEquals("都市神豪", options.get(0).getTitle());
    }
}
