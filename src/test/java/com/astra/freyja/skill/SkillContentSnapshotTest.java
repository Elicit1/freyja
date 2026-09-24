package com.astra.freyja.skill;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiSkillMapper;
import com.astra.freyja.dao.AiSkillVersionMapper;
import com.astra.freyja.entity.AiSkill;
import com.astra.freyja.entity.AiSkillVersion;
import com.astra.freyja.skill.service.SkillContentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillContentSnapshotTest {
    @Mock private AiSkillMapper skillMapper;
    @Mock private AiSkillVersionMapper versionMapper;
    @InjectMocks private SkillContentServiceImpl contentService;

    @Test
    void enabledNameLookupIsRecheckedAfterSkillIsDisabled() {
        AiSkill enabled = skill(1);
        AiSkill disabled = skill(0);
        when(skillMapper.selectOne(any())).thenReturn(enabled, disabled);
        when(skillMapper.selectById(1L)).thenReturn(enabled);
        when(versionMapper.selectById(11L)).thenReturn(version());

        assertEquals("story-structure", contentService.loadSkill("story-structure").getName());
        assertThrows(BizException.class, () -> contentService.loadSkill("story-structure"));
        verify(versionMapper, times(1)).selectById(11L);
    }

    @Test
    void approvedVersionSnapshotStillLoadsAfterNewTasksAreDisabled() {
        when(skillMapper.selectById(1L)).thenReturn(skill(0));
        when(versionMapper.selectById(11L)).thenReturn(version());
        assertEquals("SKILL RULES", contentService.loadSkillVersion(11L).getContent());
    }

    private AiSkill skill(int enabled) {
        AiSkill skill = new AiSkill();
        skill.setId(1L);
        skill.setName("story-structure");
        skill.setEnabled(enabled);
        skill.setCurrentVersionId(11L);
        return skill;
    }

    private AiSkillVersion version() {
        AiSkillVersion version = new AiSkillVersion();
        version.setSkillId(1L);
        version.setContent("SKILL RULES");
        version.setRevisionNo(1);
        return version;
    }
}
