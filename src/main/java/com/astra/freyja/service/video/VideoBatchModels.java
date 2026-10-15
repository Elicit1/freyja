package com.astra.freyja.service.video;

import com.astra.freyja.dto.drama.DramaShotRenderRequestDTO;
import com.astra.freyja.entity.DramaShot;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class VideoBatchModels {
    private VideoBatchModels() {}

    @Data
    public static class FramePlan {
        private String type = "NONE";
        private String frameType;
        private Long sourceShotId;
        private String sourceTaskId;
        private String videoUrl;
        private Long takeId;
        private String reason;
    }

    @Data
    public static class Snapshot {
        private DramaShot shot;
        private DramaShotRenderRequestDTO request;
        private String modelFingerprint;
        private String aspectRatio;
        private FramePlan firstFramePlan;
        private FramePlan lastFramePlan;
    }

    @Data
    public static class Candidate {
        private Long shotId;
        private String name;
        private String context;
        private String generationMode;
        private String status;
        private String reason;
        private String firstFrameSource;
        private String lastFrameSource;
        private String size;
        private int executionOrder;
        @JsonIgnore private Snapshot snapshot;
    }

    @Data
    public static class Preview {
        private List<Long> shotIds;
        private List<Candidate> items;
        private String previewHash;
        private int accepted;
        private int skipped;
        private int blocked;
        private double duration;
    }

    @Data
    public static class Node {
        private String key;
        private String label;
        private Long id;
        private String type;
        private List<Node> children = new ArrayList<>();
    }

    @Data
    public static class Batch {
        private Long id;
        private Long dramaId;
        private String name;
        private String requestId;
        private String requestHash;
        @JsonIgnore private String optionsJson;
        private com.astra.freyja.dto.drama.VideoRenderBatchRequest options;
        private String status;
        private boolean cancelRequested;
        private Integer revision;
        private LocalDateTime createdAt;
        private LocalDateTime finishedAt;
        private List<Item> items;
        private int total;
        private int processed;
        private int succeeded;
        private int failed;
        private int cancelled;
        private int skipped;
        private int refTotal;
        private int refProcessed;
        private int flTotal;
        private int flProcessed;
        private int progress;
        private String currentPhase;
        private String currentShot;
        private String upstreamStatus;
    }

    @Data
    public static class Item {
        private Long id;
        private Long batchId;
        private Long queueOrder;
        private Long shotId;
        private String taskId;
        private String name;
        private String phase;
        private int executionOrder;
        private String status;
        private String errorMessage;
        private String outputUrl;
        private String upstreamStatus;
        private String failurePhase;
        @JsonIgnore private String recoveryUrl;
        public boolean isArchiveRecoverable(){return recoveryUrl!=null;}
        private LocalDateTime createdAt;
        private LocalDateTime finishedAt;
        @JsonIgnore private String snapshotJson;
        @JsonIgnore private String resolvedJson;
    }
}
