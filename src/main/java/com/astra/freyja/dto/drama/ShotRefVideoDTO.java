package com.astra.freyja.dto.drama;

import lombok.Data;
import java.math.BigDecimal;

/** Creator-authored source selection. Measured metadata is never trusted from this DTO. */
@Data
public class ShotRefVideoDTO {
    private String id;
    private String name;
    private String sourceType;
    private String sourceVideoUrl;
    private Long sourceShotId;
    private Long sourceVideoTakeId;
    private BigDecimal startSeconds;
    private BigDecimal endSeconds;
    private Boolean includeAudio = false;
    private String referenceDescription;
}
