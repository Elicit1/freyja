package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_run")
public class AgentRun extends BaseEntity {
    private Long sessionId;
    private String runType;
    private String objective;
    private Integer configRevision;
    private String profileSnapshotJson;
    private String status;
    private Integer modelTurns;
    private Integer toolCalls;
    private Integer nextStepOrder;
    private Integer constraintVersion;
    private Long objectiveMessageId;
    private String finalResultJson;
    private String errorMessage;
    private Boolean cancelRequested;
    private String leaseOwner;
    private java.time.LocalDateTime leaseUntil;
    private Integer leaseEpoch;
    private Long executionMillis;
    private Integer promptTokens;
    private Integer completionTokens;
    private Boolean usageUnknown;
    private java.time.LocalDateTime startedAt;
    private java.time.LocalDateTime finishedAt;
    private java.time.LocalDateTime readAt;
}
