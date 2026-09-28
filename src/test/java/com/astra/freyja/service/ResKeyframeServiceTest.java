package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.ResKeyframeMapper;
import com.astra.freyja.dto.res.ResKeyframeDTO;
import com.astra.freyja.dto.res.ResKeyframeQuery;
import com.astra.freyja.dto.res.ResKeyframeVO;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.ResKeyframe;
import com.astra.freyja.service.impl.ResKeyframeServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResKeyframeServiceTest {

    @Mock
    private ResKeyframeMapper resKeyframeMapper;

    @Mock
    private DramaMapper dramaMapper;

    @Mock
    private DramaShotMapper dramaShotMapper;

    @InjectMocks
    private ResKeyframeServiceImpl resKeyframeService;

    @Test
    void testCreate_Success() {
        ResKeyframeDTO dto = new ResKeyframeDTO();
        dto.setName("雨夜决战首帧");
        dto.setFrameUrl("http://127.0.0.1:9000/assets/keyframe1.png");
        dto.setDramaId(100L);
        dto.setShotId(200L);
        dto.setFrameType("FIRST_FRAME");

        DramaShot shot = new DramaShot();
        shot.setId(200L);
        shot.setDramaId(100L);
        when(dramaShotMapper.selectById(200L)).thenReturn(shot);

        doAnswer(invocation -> {
            ResKeyframe entity = invocation.getArgument(0);
            entity.setId(1001L);
            return 1;
        }).when(resKeyframeMapper).insert(any(ResKeyframe.class));

        Long id = resKeyframeService.create(dto);
        assertNotNull(id);
        assertEquals(1001L, id);
        verify(resKeyframeMapper, times(1)).insert(any(ResKeyframe.class));
    }

    @Test
    void testCreate_ShotNotFound() {
        ResKeyframeDTO dto = new ResKeyframeDTO();
        dto.setName("测试帧");
        dto.setFrameUrl("http://127.0.0.1:9000/assets/kf.png");
        dto.setShotId(999L);

        when(dramaShotMapper.selectById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> resKeyframeService.create(dto));
        assertTrue(ex.getMessage().contains("关联的分镜镜头不存在"));
    }

    @Test
    void testCreate_ValidationFailure() {
        ResKeyframeDTO dto = new ResKeyframeDTO();
        dto.setName("");
        dto.setFrameUrl("http://127.0.0.1:9000/assets/kf.png");

        assertThrows(BizException.class, () -> resKeyframeService.create(dto));

        dto.setName("有效名称");
        dto.setFrameUrl("");
        assertThrows(BizException.class, () -> resKeyframeService.create(dto));
    }

    @Test
    void testGetById_Success() {
        ResKeyframe entity = new ResKeyframe();
        entity.setId(1001L);
        entity.setName("特写关键帧");
        entity.setDramaId(100L);
        entity.setShotId(200L);
        entity.setFrameUrl("http://127.0.0.1:9000/assets/keyframe1.png");

        when(resKeyframeMapper.selectById(1001L)).thenReturn(entity);

        Drama drama = new Drama();
        drama.setId(100L);
        drama.setTitle("都市重生之战神");
        when(dramaMapper.selectById(100L)).thenReturn(drama);

        DramaShot shot = new DramaShot();
        shot.setId(200L);
        shot.setShotNo(1);
        shot.setShotName("S01-01");
        when(dramaShotMapper.selectById(200L)).thenReturn(shot);

        ResKeyframeVO vo = resKeyframeService.getById(1001L);
        assertNotNull(vo);
        assertEquals("特写关键帧", vo.getName());
        assertEquals("都市重生之战神", vo.getDramaTitle());
        assertEquals("S01-01", vo.getShotName());
        assertEquals(1, vo.getShotNo());
    }

    @Test
    void testPage_Success() {
        ResKeyframeQuery query = new ResKeyframeQuery();
        query.setCurrent(1);
        query.setSize(10);
        query.setDramaId(100L);

        ResKeyframe entity = new ResKeyframe();
        entity.setId(1001L);
        entity.setDramaId(100L);
        entity.setShotId(200L);
        entity.setName("测试帧");

        Page<ResKeyframe> pageResult = new Page<>(1, 10, 1);
        pageResult.setRecords(List.of(entity));

        when(resKeyframeMapper.selectPage(any(), any())).thenReturn(pageResult);

        Drama drama = new Drama();
        drama.setId(100L);
        drama.setTitle("都市短剧");
        when(dramaMapper.selectBatchIds(any())).thenReturn(List.of(drama));

        DramaShot shot = new DramaShot();
        shot.setId(200L);
        shot.setShotNo(3);
        shot.setShotName("S01-03");
        when(dramaShotMapper.selectBatchIds(any())).thenReturn(List.of(shot));

        Page<ResKeyframeVO> result = resKeyframeService.page(query);
        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        assertEquals("都市短剧", result.getRecords().get(0).getDramaTitle());
        assertEquals("S01-03", result.getRecords().get(0).getShotName());
    }

    @Test
    void testUpdate_Success() {
        ResKeyframeDTO dto = new ResKeyframeDTO();
        dto.setId(1001L);
        dto.setName("更新后的帧名称");
        dto.setFrameUrl("http://127.0.0.1:9000/assets/updated.png");
        dto.setDramaId(100L);

        ResKeyframe existing = new ResKeyframe();
        existing.setId(1001L);
        existing.setName("旧名称");
        when(resKeyframeMapper.selectById(1001L)).thenReturn(existing);

        resKeyframeService.update(dto);
        verify(resKeyframeMapper, times(1)).updateById(any(ResKeyframe.class));
    }

    @Test
    void testDelete_Success() {
        ResKeyframe existing = new ResKeyframe();
        existing.setId(1001L);
        when(resKeyframeMapper.selectById(1001L)).thenReturn(existing);

        resKeyframeService.delete(1001L);
        verify(resKeyframeMapper, times(1)).deleteById(1001L);
    }

    @Test
    void testChangeStatus_Success() {
        ResKeyframe existing = new ResKeyframe();
        existing.setId(1001L);
        existing.setStatus(1);
        when(resKeyframeMapper.selectById(1001L)).thenReturn(existing);

        resKeyframeService.changeStatus(1001L, 0);
        assertEquals(0, existing.getStatus());
        verify(resKeyframeMapper, times(1)).updateById(existing);
    }
}
