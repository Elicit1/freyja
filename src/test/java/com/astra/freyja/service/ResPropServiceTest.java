package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.ResPropMapper;
import com.astra.freyja.dto.res.ResPropDTO;
import com.astra.freyja.dto.res.ResPropOptionVO;
import com.astra.freyja.dto.res.ResPropQuery;
import com.astra.freyja.dto.res.ResPropVO;
import com.astra.freyja.entity.ResProp;
import com.astra.freyja.service.impl.ResPropServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResPropServiceTest {

    @Mock
    private ResPropMapper resPropMapper;

    @InjectMocks
    private ResPropServiceImpl propService;

    @Test
    void testCreatePropSuccess() {
        ResPropDTO dto = new ResPropDTO();
        dto.setName("玄铁重剑");
        dto.setPropType("WEAPON");
        dto.setPropPrompt("heavy ancient dark iron broadsword with silver runes");
        dto.setDescription("主角佩剑，重逾百斤");

        doAnswer(invocation -> {
            ResProp p = invocation.getArgument(0);
            p.setId(3001L);
            return 1;
        }).when(resPropMapper).insert(any(ResProp.class));

        Long id = propService.create(dto);
        assertEquals(3001L, id);
        verify(resPropMapper, times(1)).insert((ResProp) argThat(p ->
                "玄铁重剑".equals(((ResProp) p).getName()) &&
                "WEAPON".equals(((ResProp) p).getPropType()) &&
                Long.valueOf(0L).equals(((ResProp) p).getDramaId()) &&
                Integer.valueOf(1).equals(((ResProp) p).getStatus())
        ));
    }

    @Test
    void testCreatePropNameBlankThrows() {
        ResPropDTO dto = new ResPropDTO();
        dto.setName("   ");
        dto.setPropPrompt("valid prompt");

        BizException ex = assertThrows(BizException.class, () -> propService.create(dto));
        assertEquals("道具名称不能为空", ex.getMessage());
        verify(resPropMapper, never()).insert(any(ResProp.class));
    }

    @Test
    void testCreatePropPromptBlankAllowed() {
        ResPropDTO dto = new ResPropDTO();
        dto.setName("精致怀表");
        dto.setPropPrompt("  ");

        doAnswer(invocation -> {
            ResProp p = invocation.getArgument(0);
            p.setId(3002L);
            return 1;
        }).when(resPropMapper).insert(any(ResProp.class));

        Long id = propService.create(dto);
        assertEquals(3002L, id);
        verify(resPropMapper, times(1)).insert(any(ResProp.class));
    }

    @Test
    void testGetByIdSuccess() {
        ResProp prop = new ResProp();
        prop.setId(3001L);
        prop.setName("繁复小座钟");
        prop.setPropType("KEY_PROP");
        prop.setPropPrompt("ornate antique brass desk clock");

        when(resPropMapper.selectById(3001L)).thenReturn(prop);

        ResPropVO vo = propService.getById(3001L);
        assertNotNull(vo);
        assertEquals("繁复小座钟", vo.getName());
        assertEquals("KEY_PROP", vo.getPropType());
    }

    @Test
    void testGetByIdNotFoundThrows() {
        when(resPropMapper.selectById(9999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> propService.getById(9999L));
        assertEquals("道具资产不存在", ex.getMessage());
    }

    @Test
    void testUpdateSuccess() {
        ResProp exist = new ResProp();
        exist.setId(3001L);
        when(resPropMapper.selectById(3001L)).thenReturn(exist);

        ResPropDTO dto = new ResPropDTO();
        dto.setId(3001L);
        dto.setName("改良版玄铁重剑");
        dto.setPropPrompt("updated prompt");

        propService.update(dto);
        verify(resPropMapper, times(1)).updateById(any(ResProp.class));
    }

    @Test
    void testDeleteSuccess() {
        propService.delete(3001L);
        verify(resPropMapper, times(1)).deleteById(3001L);
    }

    @Test
    void testOptions() {
        ResProp prop1 = new ResProp();
        prop1.setId(1L);
        prop1.setDramaId(0L);
        prop1.setName("公用道具");
        prop1.setPropType("DAILY");

        ResProp prop2 = new ResProp();
        prop2.setId(2L);
        prop2.setDramaId(10L);
        prop2.setName("专属武器");
        prop2.setPropType("WEAPON");

        when(resPropMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(prop1, prop2));

        List<ResPropOptionVO> options = propService.options(10L);
        assertEquals(2, options.size());
        assertEquals("公用道具", options.get(0).getName());
        assertEquals("专属武器", options.get(1).getName());
    }

    @Test
    void testPage() {
        ResPropQuery query = new ResPropQuery();
        query.setCurrent(1);
        query.setSize(10);
        query.setName("剑");

        ResProp entity = new ResProp();
        entity.setId(101L);
        entity.setName("青釭剑");
        entity.setPropType("WEAPON");

        Page<ResProp> entityPage = new Page<>(1, 10, 1);
        entityPage.setRecords(List.of(entity));

        when(resPropMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(entityPage);

        Page<ResPropVO> result = propService.page(query);
        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals("青釭剑", result.getRecords().get(0).getName());
    }
}
