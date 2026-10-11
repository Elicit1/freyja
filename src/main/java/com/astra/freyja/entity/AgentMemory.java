package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_memory")
public class AgentMemory extends BaseEntity {
    private String scopeType;
    private Long scopeId;
    private String memoryKey;
    private String content;
    private Long sourceMessageId;
    private Integer revision;
    private Boolean enabled;
}
