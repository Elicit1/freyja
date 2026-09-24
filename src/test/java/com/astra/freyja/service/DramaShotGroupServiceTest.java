package com.astra.freyja.service;

import com.astra.freyja.dao.DramaEpisodeMapper;
import com.astra.freyja.dao.DramaSceneMapper;
import com.astra.freyja.dao.DramaShotGroupMapper;
import com.astra.freyja.dao.DramaShotMapper;

import com.astra.freyja.dto.drama.DramaShotGroupDTO;
import com.astra.freyja.dto.drama.DramaShotGroupMergeDTO;
import com.astra.freyja.dto.drama.DramaShotGroupSplitDTO;
import com.astra.freyja.dto.drama.DramaShotGroupVO;
import com.astra.freyja.dto.drama.DramaShotVO;
import com.astra.freyja.entity.DramaScene;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.DramaShotGroup;
import com.astra.freyja.service.impl.DramaShotGroupServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DramaShotGroupServiceTest {

    @Mock
    private DramaShotGroupMapper shotGroupMapper;

    @Mock
    private DramaShotMapper shotMapper;

    @Mock
    private DramaSceneMapper sceneMapper;

    @Mock
    private DramaEpisodeMapper episodeMapper;

    @Mock
    private DramaShotService shotService;

    @Mock
    private DramaEpisodeService episodeService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DramaShotGroupServiceImpl shotGroupService;

    @Test
    void testCreateShotGroupSuccess() {
        DramaScene scene = new DramaScene();
        scene.setId(10L);
        scene.setDramaId(1L);
        scene.setEpisodeId(5L);
        scene.setSceneNo(1);
        scene.setName("办公室内");

        when(sceneMapper.selectById(10L)).thenReturn(scene);
        when(shotGroupMapper.selectList(any())).thenReturn(List.of());

        DramaShotGroupDTO dto = new DramaShotGroupDTO();
        dto.setSceneId(10L);
        dto.setName("葛明进门与发现");
        dto.setPurpose("建立空间关系并展示葛明表情");

        doAnswer(invocation -> {
            DramaShotGroup g = invocation.getArgument(0);
            g.setId(100L);
            return 1;
        }).when(shotGroupMapper).insert(any(DramaShotGroup.class));

        Long id = shotGroupService.create(dto);
        assertEquals(100L, id);
        verify(shotGroupMapper, times(1)).insert(any(DramaShotGroup.class));
    }

    @Test
    void testSplitGroupSuccess() {
        DramaShotGroup source = new DramaShotGroup();
        source.setId(100L);
        source.setSceneId(10L);
        source.setEpisodeId(5L);
        source.setDramaId(1L);
        source.setGroupNo(1);
        source.setName("连续镜头组 1");

        when(shotGroupMapper.selectById(100L)).thenReturn(source);

        DramaShot s1 = new DramaShot();
        s1.setId(1L);
        s1.setShotGroupId(100L);
        s1.setShotNo(1);

        DramaShot s2 = new DramaShot();
        s2.setId(2L);
        s2.setShotGroupId(100L);
        s2.setShotNo(2);

        DramaShot s3 = new DramaShot();
        s3.setId(3L);
        s3.setShotGroupId(100L);
        s3.setShotNo(3);

        when(shotMapper.selectList(any())).thenReturn(List.of(s1, s2, s3));
        when(shotGroupMapper.selectList(any())).thenReturn(List.of());

        doAnswer(invocation -> {
            DramaShotGroup g = invocation.getArgument(0);
            g.setId(101L);
            return 1;
        }).when(shotGroupMapper).insert(any(DramaShotGroup.class));

        DramaShotGroupSplitDTO splitDTO = new DramaShotGroupSplitDTO();
        splitDTO.setSourceGroupId(100L);
        splitDTO.setSplitAtShotId(2L);
        splitDTO.setNewGroupName("拆分后的新组");

        Long newId = shotGroupService.splitGroup(splitDTO);
        assertEquals(101L, newId);
        // s2, s3 移入新组
        verify(shotMapper, times(2)).updateById(any(DramaShot.class));
    }
}
