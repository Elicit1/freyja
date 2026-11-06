package com.astra.freyja.dto.drama;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/** Creator-owned draft. References belong to a stable segment, never inherited. */
@Data
@JsonIgnoreProperties("lockContext") // Old drafts are read as native Guide continuation and must be previewed again.
public class ShotContinuation {
    private int schemaVersion = 1;
    private boolean enabled;
    /** Ordinary-shot ControlNet configuration is authored with the shot, never in the render dialog. */
    private ShotControlNetOptions controlNet = new ShotControlNetOptions();
    private double durationSeconds = 15;
    private int segmentFrames = 124;
    private int overlapFrames = 22;
    private boolean autoSplitReferenceVideo = true;
    /** Resource tray only. These sources never participate without explicit segment bindings. */
    private List<ShotRefVideoDTO> videoSources = new ArrayList<>();
    private List<ShotRefImageDTO> imageSources = new ArrayList<>();
    private boolean contextWindows;
    private String reuseFromTaskId;
    private List<Segment> segments = new ArrayList<>();
    @Data public static class Segment {
        private String id;
        private String name;
        private String scriptContent;
        private String actionDescription;
        private String soundEffect;
        private String shotType;
        private String cameraMovement;
        private boolean shotTypeLocked;
        private boolean cameraMovementLocked;
        private Long resSceneId;
        private Long resKeyframeId;
        private String customScenePrompt;
        private List<com.astra.freyja.dto.res.CharacterShotRefDTO> characterRefs = new ArrayList<>();
        private List<PropShotRefDTO> propRefs = new ArrayList<>();
        private com.astra.freyja.director.model.DirectorPlan directorPlan;
        private String firstFramePrompt;
        private String endFramePrompt;
        private String negativePrompt;
        private List<ShotRefAudioDTO> referenceAudios = new ArrayList<>();
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
