package com.astra.freyja.dto.drama;

import lombok.Data;

@Data
public class ShotVideoModelOptionVO {
    private Long id;
    private Long providerId;
    private String modelCode;
    private String modelName;
    private Integer maxImages;
    private Integer maxAudios;
    private Integer maxVideos;
    private ReferenceVideoCapability referenceVideo;
    private String nodeId;
    private String workflowRevision;
    private Boolean healthy = false;
    private Boolean renderable = false;
    private String reason;
    private String errorCode = "REFERENCE_VIDEO_UNVERIFIED";
    private String modelFingerprint;
}
