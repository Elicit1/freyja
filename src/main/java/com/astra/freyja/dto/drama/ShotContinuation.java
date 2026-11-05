package com.astra.freyja.dto.drama;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** Creator-owned draft. References belong to a stable segment, never inherited. */
@Data
public class ShotContinuation {
    private int schemaVersion = 1;
    private boolean enabled;
    private double durationSeconds = 15;
    private int segmentFrames = 124;
    private int overlapFrames = 22;
    private boolean autoSplitReferenceVideo = true;
    /** Resource tray only. These sources never participate without explicit segment bindings. */
    private List<ShotRefVideoDTO> videoSources = new ArrayList<>();
    private boolean contextWindows;
    private boolean lockContext;
    private String reuseFromTaskId;
    private List<Segment> segments = new ArrayList<>();
    @Data public static class Segment {
        private String id;
        private int startFrame;
        private int endFrame;
        private String prompt;
        private boolean locked;
        private String firstFrameImageUrl;
        private String lastFrameImageUrl;
        private List<ShotRefImageDTO> referenceImages = new ArrayList<>();
        private List<ShotRefVideoDTO> referenceVideos = new ArrayList<>();
        private boolean controlNetEnabled;
        private String controlVideoId;
        private String controlType = "CANNY";
        private double controlStrength = 1;
    }
}
