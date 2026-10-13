package com.astra.freyja.capability.model;

import lombok.Data;

@Data
public class CapabilityRow {
    private Long id;
    private Long revision;
    private Long activeReleaseId;
    private String payloadJson;
    private String contentHash;
    private String note;
    private String createTime;
}
