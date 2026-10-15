package com.astra.freyja.dto.drama;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class VideoRenderBatchRequest {
    private String clientRequestId;
    private String name;
    private Long dramaId;
    private List<Long> shotIds;
    private Selection selection;
    private String expectedPreviewHash;
    private String existingVideoPolicy = "SKIP";
    private String aspectRatio;
    private Map<String, ModeConfig> modeConfigs;
    private Long retryOfBatchId;
    private String inputSource;

    @Data
    public static class ModeConfig {
        private Long providerId;
        private Long modelId;
        private boolean autoUsePreviousVideoTailAsFirstFrame = true;
        private boolean autoUseNextVideoHeadAsLastFrame;
    }

    @Data
    public static class Selection {
        private List<Scope> include;
        private List<Long> excludeShotIds;
    }

    @Data
    public static class Scope {
        private String type;
        private Long id;
    }
}
