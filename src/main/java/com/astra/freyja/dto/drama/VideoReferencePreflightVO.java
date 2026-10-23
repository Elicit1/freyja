package com.astra.freyja.dto.drama;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class VideoReferencePreflightVO {
    private String preflightId;
    private String status;
    private Long shotId;
    private Long providerId;
    private String modelCode;
    private String inputFingerprint;
    private LocalDateTime expiresAt;
    private String errorCode;
    private String errorMessage;
    private List<ResolvedShotVideoReference> references;
    private List<String> previewUrls;
    private ShotVideoModelOptionVO model;
}
