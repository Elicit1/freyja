package com.astra.freyja.service;

import com.astra.freyja.director.model.CameraBeat;
import com.astra.freyja.director.model.DirectorPlan;
import com.astra.freyja.director.service.DirectorPlanValidator;
import com.astra.freyja.dto.script.DecomposedShotVO;
import com.astra.freyja.dto.script.GlobalStoryContext;
import com.astra.freyja.dto.script.PlannerDecomposeResultVO;
import com.astra.freyja.dto.script.SegmentShotResult;
import com.astra.freyja.dto.script.WorkerShotResult;
import com.astra.freyja.service.impl.ShotMergeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShotMergeCameraMovementTest {

    @Test
    void workerAutoCameraAndPlotFactsPassThroughMerge() throws Exception {
        String sourceScript = "便利店门口，雷姆左手提一个购物袋、右手提一个购物袋，共两个。菜月昴拿着一部手机，两人擦肩而过。原文摄影要求（用户明确指定）：镜头轻微横摇。";
        String workerJson = """
                {
                  "segmentId":"SEG001",
                  "scenes":[{"localId":"SC001","sceneId":"SC001","name":"便利店门口","shots":[
                    {"localId":"S001","sceneId":"SC001","groupLocalId":"G001","sequence":1,"duration":5.0,
                     "scriptContent":"%s","action":"两人擦肩而过","propIds":["PR001","PR002"],
                     "camera":{"shotSize":"AUTO","movement":"AUTO"}}
                  ]}]
                }
                """.formatted(sourceScript);

        WorkerShotResult result = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(workerJson, WorkerShotResult.class);
        SegmentShotResult segment = SegmentShotResult.builder()
                .segmentId("SEG001")
                .sequence(1)
                .shotResult(result)
                .build();

        DecomposedShotVO merged = new ShotMergeServiceImpl()
                .mergeSegmentResults(List.of(segment), PlannerDecomposeResultVO.builder().build(),
                        GlobalStoryContext.builder().build(), message -> {})
                .getEpisodes().getFirst().getScenes().getFirst().getShotGroups().getFirst().getShots().getFirst();

        assertEquals(sourceScript, merged.getScriptContent());
        assertEquals(List.of("PR001", "PR002"), merged.getPropIds());
        assertEquals(5.0, merged.getDuration());
        assertEquals("AUTO", merged.getShotType());
        assertEquals("AUTO", merged.getCameraMovement());
        assertFalse(merged.getShotTypeLocked());
        assertFalse(merged.getCameraMovementLocked());

        String outputSchema = new BeanOutputConverter<>(WorkerShotResult.class).getFormat();
        assertTrue(outputSchema.contains("shotSize"));
        assertTrue(outputSchema.contains("movement"));
        assertTrue(outputSchema.contains("AUTO"));
    }

    @Test
    void historicalWorkerCameraTextNeverFallsBackToStatic() throws Exception {
        String workerJson = """
                {"segmentId":"SEG001","scenes":[{"name":"便利店门口","shots":[
                  {"localId":"S001","duration":4.0,"scriptContent":"两人擦肩而过","action":"擦肩而过",
                   "camera":{"shotSize":"中景","movement":"轻微横摇"}}
                ]}]}
                """;
        WorkerShotResult result = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(workerJson, WorkerShotResult.class);
        DecomposedShotVO merged = new ShotMergeServiceImpl()
                .mergeSegmentResults(List.of(SegmentShotResult.builder()
                                .segmentId("SEG001").sequence(1).shotResult(result).build()),
                        PlannerDecomposeResultVO.builder().build(), GlobalStoryContext.builder().build(), message -> {})
                .getEpisodes().getFirst().getScenes().getFirst().getShotGroups().getFirst().getShots().getFirst();

        assertEquals("AUTO", merged.getShotType());
        assertEquals("AUTO", merged.getCameraMovement());
    }

    @Test
    void historicalStandardCameraEnumsArePassedThrough() throws Exception {
        String workerJson = """
                {"segmentId":"SEG001","scenes":[{"name":"便利店门口","shots":[
                  {"localId":"S001","duration":4.0,"scriptContent":"两人擦肩而过","action":"擦肩而过",
                   "camera":{"shotSize":"CLOSE_UP","movement":"PAN_LEFT"}}
                ]}]}
                """;
        WorkerShotResult result = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(workerJson, WorkerShotResult.class);
        DecomposedShotVO merged = new ShotMergeServiceImpl()
                .mergeSegmentResults(List.of(SegmentShotResult.builder()
                                .segmentId("SEG001").sequence(1).shotResult(result).build()),
                        PlannerDecomposeResultVO.builder().build(), GlobalStoryContext.builder().build(), message -> {})
                .getEpisodes().getFirst().getScenes().getFirst().getShotGroups().getFirst().getShots().getFirst();

        assertEquals("CLOSE_UP", merged.getShotType());
        assertEquals("PAN_LEFT", merged.getCameraMovement());
    }

    @Test
    void promptAiMovementIsKeptAndUserLocksAreAppliedOnlyWhenExplicit() {
        DirectorPlanValidator validator = new DirectorPlanValidator();
        DirectorPlan panPlan = planWithMovement("PAN_LEFT", "轻微横摇，跟随擦肩动作后收住");

        validator.validateAndNormalize(panPlan, new BigDecimal("5.0"), null, "STATIC", false, false, null);

        assertEquals("PAN_LEFT", panPlan.getCameraBeats().getFirst().getMovement());
        assertFalse(panPlan.getLockedFields().contains("cameraMovement"));
        assertEquals("轻微横摇，跟随擦肩动作后收住", panPlan.getCameraBeats().getFirst().getNarrativePurpose());

        DirectorPlan staticPlan = planWithMovement("PAN_RIGHT", "模型初始建议摇摄");
        validator.validateAndNormalize(staticPlan, new BigDecimal("5.0"), null, "STATIC", false, true, null);
        assertEquals("STATIC", staticPlan.getCameraBeats().getFirst().getMovement());
        assertEquals("NONE", staticPlan.getCameraBeats().getFirst().getSpeed());

        DirectorPlan trackingPlan = planWithMovement("STATIC", "模型初始建议固定");
        validator.validateAndNormalize(trackingPlan, new BigDecimal("5.0"), null, "TRACKING", false, true, null);
        assertEquals("TRACKING", trackingPlan.getCameraBeats().getFirst().getMovement());
    }

    private DirectorPlan planWithMovement(String movement, String purpose) {
        return DirectorPlan.builder()
                .duration(new BigDecimal("5.0"))
                .shotSize("MEDIUM_CLOSE_UP")
                .cameraAngle("EYE_LEVEL")
                .cameraBeats(List.of(CameraBeat.builder()
                        .startSec(BigDecimal.ZERO)
                        .endSec(new BigDecimal("5.0"))
                        .movement(movement)
                        .speed("SLOW")
                        .narrativePurpose(purpose)
                        .build()))
                .build();
    }
}
