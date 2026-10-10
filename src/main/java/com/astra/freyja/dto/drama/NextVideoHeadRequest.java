package com.astra.freyja.dto.drama;

import lombok.Data;

/** Optional force refresh of the next shot's decoded first frame. */
@Data
public class NextVideoHeadRequest {
    private Boolean forceExtract;
}
