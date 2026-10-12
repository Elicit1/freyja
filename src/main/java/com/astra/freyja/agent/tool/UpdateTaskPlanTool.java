package com.astra.freyja.agent.tool;

import com.astra.freyja.agent.model.AgentTaskPlan;
import com.astra.freyja.agent.model.AgentProtocol.*;
import org.springframework.stereotype.Component;

@Component
public class UpdateTaskPlanTool implements AgentTool<AgentTaskPlan> {
    public String name() { return "update_task_plan"; }
    public String description() {
        return "创建或更新用户可见的任务计划。steps 为完整计划，每步有稳定 id、简短 title、状态 PENDING/IN_PROGRESS/COMPLETED/SKIPPED。"
                + "多步骤任务执行前先保存计划，推进时更新状态，最多一个 IN_PROGRESS；explanation 简述进展或变更原因。只汇报实际工作，不输出内部推理。";
    }
    public Class<AgentTaskPlan> inputType() { return AgentTaskPlan.class; }
    // Changes only this run's progress metadata, including in read-only configuration tests.
    public RecoveryMode recoveryMode() { return RecoveryMode.TRANSACTIONAL; }
    public ToolResult execute(AgentTaskPlan input, ToolContext context) {
        return ToolResult.success(input.validated());
    }
}
