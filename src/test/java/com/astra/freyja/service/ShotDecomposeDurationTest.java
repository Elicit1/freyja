package com.astra.freyja.service;

import com.astra.freyja.dto.script.*;
import com.astra.freyja.service.impl.ShotMergeServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ShotDecomposeDurationTest {
    @Test
    void mergeAndStatisticsShareFallbackWhilePreservingCompactAndLongEstimates() {
        List<Double> estimates = Arrays.asList(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 2.0, 4.5, 9.0);
        List<WorkerShotResult.WorkerShotVO> rawShots = IntStream.range(0, estimates.size())
                .mapToObj(i -> WorkerShotResult.WorkerShotVO.builder()
                        .localId("S" + i).sequence(i + 1).groupLocalId("G001")
                        .duration(estimates.get(i)).scriptContent("连续动作 " + i).build()).toList();
        WorkerShotResult worker = WorkerShotResult.builder().scenes(List.of(
                WorkerShotResult.WorkerSceneVO.builder().shots(rawShots).build())).build();
        ShotMergeServiceImpl merge = new ShotMergeServiceImpl();
        var result = merge.mergeSegmentResults(List.of(SegmentShotResult.builder().sequence(1).shotResult(worker).build()),
                PlannerDecomposeResultVO.builder().build(), null, message -> {});
        var shots = result.getEpisodes().getFirst().getScenes().getFirst().getShots();
        assertEquals(List.of(3.0, 3.0, 3.0, 3.0, 3.0, 2.0, 4.5, 9.0), shots.stream().map(DecomposedShotVO::getDuration).toList());
        var direct = merge.calculateFragmentationStats(estimates.stream().map(d -> DecomposedShotVO.builder().duration(d).build()).toList());
        var fromPreview = merge.calculateFragmentationStats(shots);
        assertEquals(direct, fromPreview);
        assertEquals(7, direct.getShortShots());
        assertEquals(1, direct.getLongShots());
        assertEquals(3.8, direct.getAverageDuration());
        assertFalse(direct.getIsHighFragmentation());
        assertNull(direct.getWarningMessage());
    }
}
