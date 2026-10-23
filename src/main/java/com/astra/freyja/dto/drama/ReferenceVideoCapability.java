package com.astra.freyja.dto.drama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReferenceVideoCapability {
    private Integer schemaVersion = 1;
    private String status = "UNSUPPORTED";
    private String adapter;
    private String workflowRevision;
    private Integer maxVideos;
    private BigDecimal minClipSeconds;
    private BigDecimal maxClipSeconds;
    private BigDecimal maxTotalClipSeconds;
    private Integer decodeFps;
    private Integer maxDecodedFrames;
    private Integer maxDecodeWidth;
    private Integer maxDecodeHeight;
    private Integer maxMixedAssets;
    private Boolean supportsVideoAudio = false;
    private Long maxDownloadBytes;
}
