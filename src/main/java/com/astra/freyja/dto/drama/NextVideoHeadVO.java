package com.astra.freyja.dto.drama;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NextVideoHeadVO {
    private Long currentShotId;
    private Long sourceShotId;
    private Integer sourceShotNo;
    private String sourceShotName;
    private String sourceVideoUrl;
    private Long sourceVideoTakeId;
    private String headFrameUrl;
    private Boolean reused;
    private Boolean applied;
}
