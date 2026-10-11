package com.astra.freyja.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = true) @TableName("agent_preparation_job")
public class AgentPreparationJob extends BaseEntity {
    private Long runId;
    private Long sessionId;
    private Long dramaId;
    private Long decompositionTaskId;
    private String status;
    private String sourceText;
    private String requestJson;
    private String shotIdsJson;
    private String assetsJson;
    private String reportJson;
}
