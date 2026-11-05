package com.astra.freyja.dto.drama;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class ShotContinuationPlan {
    private ShotContinuation draft;
    private int outputFrames;
    private int renderFrames;
    private int segmentCount;
    private String planHash;
    private String capabilityRevision;
    private String nodeId;
    private boolean renderable;
    private List<String> issues = new ArrayList<>();
    private List<Range> ranges = new ArrayList<>();
    @Data public static class Range {
        private String segmentId;
        private int startFrame;
        private int endFrame;
        private int overlapFrames;
        private int keepStartFrame;
        private int keepEndFrame;
    }
}
