package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_session")
public class AgentSession extends BaseEntity {
    private String agentCode;
    private Long dramaId;
    private String title;
    private String sessionType;
    private String testRequestId;
    private String summary;
    private Integer coveredThroughSeq;
    private Integer summaryVersion;
    private Integer nextSeq;
    private Long activeRunId;
}
