package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_config")
public class AgentConfig extends BaseEntity {
    private String agentCode;
    private String displayName;
    private String description;
    private Boolean enabled;
    private Long providerId;
    private String modelCode;
    private String systemPrompt;
    private String modelOptionsJson;
    private String toolPolicyJson;
    private String skillPolicyJson;
    private String memoryPolicyJson;
    private String runtimePolicyJson;
    private String avatarConfigJson;
    private Integer revision;
    private Integer profileVersion;
}
