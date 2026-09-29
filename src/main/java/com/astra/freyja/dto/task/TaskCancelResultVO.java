package com.astra.freyja.dto.task;

import lombok.Builder;
import lombok.Data;

/** Distinguishes confirmed remote cancellation from a locally aborted request. */
@Data
@Builder
public class TaskCancelResultVO {
    private boolean cancelled;
    private String status;
    private String upstreamStatus;
    private String message;
}
