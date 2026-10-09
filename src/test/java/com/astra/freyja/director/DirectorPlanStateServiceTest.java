package com.astra.freyja.director;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.director.model.*;
import com.astra.freyja.director.service.*;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.DramaShot;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DirectorPlanStateServiceTest {
    DramaShotMapper mapper;
    DirectorPlanStateService service;
    DramaShot shot;
    @BeforeEach void setup() {
        mapper = mock(DramaShotMapper.class);
        service = new DirectorPlanStateService(mapper, new DirectorPlanValidator());
        shot = new DramaShot(); shot.setId(7L); shot.setDuration(new BigDecimal("4"));
        when(mapper.selectById(7L)).thenReturn(shot);
    }
    DirectorPlan plan() {
        return DirectorPlan.builder().duration(new BigDecimal("4")).shotSize("MEDIUM_SHOT").cameraAngle("EYE_LEVEL")
                .cameraBeats(new ArrayList<>(List.of(CameraBeat.builder().startSec(BigDecimal.ZERO).endSec(new BigDecimal("4"))
                        .movement("STATIC").speed("NONE").build()))).narrativeIntent("original").build();
    }
    @Test void defaultAutoAndGuardedColumnOnlyCompletion() {
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder().shotId(7L).build();
        service.freeze(dto);
        assertEquals("AUTO", dto.getDirectorPlanMode()); assertNull(dto.getDirectorPlan());
        assertEquals(4.0, dto.getDuration());
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenAnswer(inv -> {
            UpdateWrapper<?> w = inv.getArgument(1);
            assertTrue(w.getSqlSegment().contains("director_plan_mode"));
            assertTrue(w.getSqlSegment().contains("director_plan_revision"));
            assertFalse(w.getSqlSet().contains("script_content"));
            return 0; // revision changed while AI was running
        });
        assertFalse(service.completeAuto(dto, plan()));
        verify(mapper, never()).updateById(any(DramaShot.class));
    }
    @Test void storedManualReopensAsDeepCopiedSnapshotAndSurvivesLaterEdits() {
        shot.setDirectorPlanMode("MANUAL"); shot.setDirectorPlanRevision(2); shot.setDirectorPlanJson(service.encode(plan()));
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder().shotId(7L).build();
        service.freeze(dto);
        shot.setDirectorPlanRevision(3); DirectorPlan later = plan(); later.setNarrativeIntent("later"); shot.setDirectorPlanJson(service.encode(later));
        service.freeze(dto);
        assertEquals(2, dto.getDirectorPlanRevision()); assertEquals("original", dto.getDirectorPlan().getNarrativeIntent());
        assertFalse(service.completeAuto(dto, plan())); verify(mapper, never()).update(isNull(), any(UpdateWrapper.class));
    }
    @Test void explicitLatestContentWinsAndIsSavedAtSubmission() {
        shot.setDirectorPlanMode("MANUAL"); shot.setDirectorPlanJson(service.encode(plan()));
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        DirectorPlan latest = plan(); latest.setNarrativeIntent("creator edit");
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder().shotId(7L).directorPlanRevision(0).directorPlan(latest).build();
        service.freeze(dto); latest.setNarrativeIntent("mutated after submit");
        assertEquals("creator edit", dto.getDirectorPlan().getNarrativeIntent()); assertEquals(1, dto.getDirectorPlanRevision());
        verify(mapper).update(isNull(), any(UpdateWrapper.class));
    }
    @Test void staleEditAndStaleSubmissionFailInsteadOfOverwriting() {
        shot.setDirectorPlanRevision(2);
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder().shotId(7L).directorPlanRevision(1).directorPlan(plan()).build();
        assertEquals(409, assertThrows(BizException.class, () -> service.freeze(dto)).getCode());
        DirectorPlanEditDTO edit = new DirectorPlanEditDTO(); edit.setDirectorPlanMode("MANUAL"); edit.setDirectorPlanRevision(1); edit.setDirectorPlan(plan());
        assertEquals(409, assertThrows(BizException.class, () -> service.save(7L, edit)).getCode());
    }
    @Test void restoreAutoIsExplicitAndClearsPlanWithVersionCheck() {
        shot.setDirectorPlanMode("MANUAL"); shot.setDirectorPlanJson(service.encode(plan()));
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder().shotId(7L).directorPlanMode("AUTO").build();
        assertThrows(BizException.class, () -> service.freeze(dto));
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        DirectorPlanEditDTO edit = new DirectorPlanEditDTO(); edit.setDirectorPlanMode("AUTO"); edit.setDirectorPlanRevision(0);
        assertEquals(1, service.save(7L, edit).getDirectorPlanRevision()); assertNull(edit.getDirectorPlan());
    }
    @Test void invalidManualNeverRepairsOrWrites() {
        DirectorPlan invalid = plan(); invalid.getCameraBeats().getFirst().setStartSec(new BigDecimal("0.01"));
        String original = service.encode(invalid);
        ShotPromptDeriveDTO dto = ShotPromptDeriveDTO.builder().shotId(7L).directorPlanRevision(0).directorPlan(invalid).build();
        assertEquals(400, assertThrows(BizException.class, () -> service.freeze(dto)).getCode());
        assertEquals(original, service.encode(invalid)); verify(mapper, never()).update(isNull(), any(UpdateWrapper.class));
    }
    @Test void strictPhysicalAndLockChecks() {
        var validator = new DirectorPlanValidator();
        DirectorPlan p = plan();
        assertThrows(BizException.class, () -> validator.validateManual(p, BigDecimal.TEN, null, null, false, false));
        assertThrows(BizException.class, () -> validator.validateManual(p, new BigDecimal("4"), "CLOSE_UP", null, true, false));
        assertThrows(BizException.class, () -> validator.validateManual(p, new BigDecimal("4"), null, "PUSH_IN", false, true));
        p.getCameraBeats().getFirst().setDirection("LEFT");
        assertThrows(BizException.class, () -> validator.validateManual(p, new BigDecimal("4"), null, null, false, false));
    }
    @Test void requestCannotBypassFreezeWithClientFlag() throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        ShotPromptDeriveDTO dto = json.readValue("{\"directorPlanFrozen\":true}", ShotPromptDeriveDTO.class);
        assertFalse(dto.isDirectorPlanFrozen());
    }

    @Test void savingEditedDurationAtomicallyPersistsMatchingPhysicalContext() {
        DirectorPlan p = plan(); p.setDuration(new BigDecimal("6")); p.getCameraBeats().getFirst().setEndSec(new BigDecimal("6"));
        DirectorPlanEditDTO edit = new DirectorPlanEditDTO(); edit.setDirectorPlanMode("MANUAL"); edit.setDirectorPlanRevision(0);
        edit.setDirectorPlan(p); edit.setDuration(6.0); edit.setShotTypeLocked(false); edit.setCameraMovementLocked(false);
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenAnswer(inv -> {
            UpdateWrapper<?> w = inv.getArgument(1);
            assertTrue(w.getSqlSet().contains("duration="));
            assertTrue(w.getSqlSet().contains("shot_type_locked="));
            assertTrue(w.getSqlSet().contains("director_plan_json="));
            return 1;
        });
        assertEquals(1, service.save(7L, edit).getDirectorPlanRevision());
    }
}
