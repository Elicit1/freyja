package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_message")
public class AgentMessage extends BaseEntity {
    private Long sessionId;
    private Long runId;
    private Integer seq;
    private String messageType;
    private String modelRole;
    private String contentJson;
    private String toolCallId;
    private String clientRequestId;
    private String eventType;
    private java.time.LocalDateTime readAt;
}
