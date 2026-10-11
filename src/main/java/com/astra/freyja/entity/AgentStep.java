package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_step")
public class AgentStep extends BaseEntity {
    private Long runId;
    private Long parentStepId;
    private String logicalStepKey;
    private Integer stepOrder;
    private String stepType;
    private String toolName;
    private Integer toolVersion;
    private String toolCallId;
    private String providerCallId;
    private String inputJson;
    private String resultJson;
    private String waitHandleJson;
    private String status;
    private Integer attempts;
    private String idempotencyKey;
    private Integer constraintVersion;
}
