package com.astra.freyja.service.impl;

import com.astra.freyja.dto.script.WorkerShotResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParallelShotGenerationServiceImplTest {

    @Test
    void generatedWorkerCameraFieldsAreAlwaysAuto() {
        ParallelShotGenerationServiceImpl service = new ParallelShotGenerationServiceImpl(
                null, null, null, null, null);
        WorkerShotResult.WorkerShotVO designedCamera = WorkerShotResult.WorkerShotVO.builder()
                .localId("S001")
                .camera(WorkerShotResult.WorkerCameraVO.builder()
                        .shotSize("CLOSE_UP")
                        .movement("PAN_LEFT")
                        .build())
                .build();
        WorkerShotResult.WorkerShotVO missingCamera = WorkerShotResult.WorkerShotVO.builder()
                .localId("S002")
                .build();
        WorkerShotResult result = WorkerShotResult.builder()
                .segmentId("SEG001")
                .scenes(List.of(WorkerShotResult.WorkerSceneVO.builder()
                        .shots(List.of(designedCamera, missingCamera))
                        .build()))
                .build();

        service.enforceAutoCameraPlaceholders(result);

        for (WorkerShotResult.WorkerShotVO shot : result.getScenes().getFirst().getShots()) {
            assertEquals("AUTO", shot.getCamera().getShotSize());
            assertEquals("AUTO", shot.getCamera().getMovement());
        }
    }
}
