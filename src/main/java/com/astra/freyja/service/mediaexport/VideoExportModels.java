package com.astra.freyja.service.mediaexport;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

public final class VideoExportModels {
    private VideoExportModels() {}
    @Data public static class Scope { private String type; private Long id; }
    @Data public static class Selection { private List<Scope> include; private List<Long> excludeShotIds; }
    @Data public static class Request {
        private Long dramaId;
        private String name;
        private List<Long> shotIds;
        private Selection selection;
        private String missingVideoPolicy = "SKIP";
        private String clientRequestId;
        private String expectedPreviewHash;
    }
    @Data public static class Node {
        private String key, type, label;
        private Long id;
        private int shotCount, videoCount;
        private List<Node> children = new ArrayList<>();
    }
    /** Internal immutable input; never exposed by REST or included in a downloadable manifest. */
    @Data public static class Snapshot {
        private Long shotId, dramaId, episodeId, sceneId, shotGroupId, takeId;
        private Integer takeNo;
        private String dramaTitle, name, episodeName, sceneName, groupName, root, zipEntryPath;
        private String videoUrl, bucket, objectKey, versionId, etag, extension;
        private Long sourceSize;
        private BigDecimal duration;
        private int executionOrder;
        private String status, errorMessage;
    }
    @Data public static class Item {
        private Long id, taskId, shotId, episodeId, sceneId, shotGroupId, takeId;
        private Integer takeNo;
        private String name, episodeName, sceneName, groupName, zipEntryPath, status, errorMessage, checksum;
        private BigDecimal duration;
        private int executionOrder;
        private Long size, sourceSize;
        @JsonIgnore private Snapshot snapshot;
    }
    @Data public static class Preview {
        private List<Long> shotIds;
        private List<Item> items;
        private int accepted, missing, blocked;
        private long knownBytes;
        private String previewHash;
    }
    @Data public static class Task {
        private Long id, dramaId, retryOfTaskId;
        private String name, dramaTitle, status, currentStage, errorMessage, filename;
        private int revision, total, processed, succeeded, failed, skipped, progress;
        private long bytesRead;
        private Long size;
        private LocalDateTime createdAt, startedAt, finishedAt, expiresAt;
        private boolean downloadAvailable;
        @JsonIgnore private String requestId, requestHash, attemptToken, objectKey;
    }
    public record Page<T>(List<T> records, long total, int current, int size) {}
    public record DownloadLink(String url, String filename, LocalDateTime expiresAt) {}
    public record QueueChanged(boolean remote) { public QueueChanged() { this(false); } }
}
