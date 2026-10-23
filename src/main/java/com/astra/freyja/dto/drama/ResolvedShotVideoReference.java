package com.astra.freyja.dto.drama;

import com.astra.freyja.dto.video.VideoProbeInfoVO;
import lombok.Data;
import java.math.BigDecimal;

/** Immutable, prepared input. URLs are regenerated from object keys before dispatch. */
@Data
public class ResolvedShotVideoReference {
    private String id;
    private String name;
    private Integer ordinal;
    private String sourceType;
    private Long sourceShotId;
    private Long sourceVideoTakeId;
    private String sourceUrl;
    private String sourceObjectPath;
    private String sourceSha256;
    private VideoProbeInfoVO sourceMetadata;
    private BigDecimal startSeconds;
    private BigDecimal endSeconds;
    private String objectPath;
    private String contentSha256;
    private VideoProbeInfoVO metadata;
    private Integer frameCount;
    private String preprocessingVersion = "H264_CFR_FIT_V1";
    private Boolean includeAudio;
    private String referenceDescription;
}
