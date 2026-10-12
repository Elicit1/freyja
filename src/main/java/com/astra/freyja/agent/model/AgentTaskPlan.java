package com.astra.freyja.agent.model;

import com.astra.freyja.common.BizException;
import java.util.HashSet;
import java.util.List;

/** Public milestones explicitly supplied by the model, independent of business shot plans. */
public record AgentTaskPlan(String explanation, List<Item> steps) {
    public enum Status { PENDING, IN_PROGRESS, COMPLETED, SKIPPED }
    public record Item(String id, String title, Status status) {}

    public AgentTaskPlan validated() {
        if (steps == null || steps.isEmpty() || steps.size() > 20)
            throw new BizException("任务计划需要 1–20 个步骤");
        if (explanation != null && explanation.length() > 1000)
            throw new BizException("计划说明不能超过 1000 字");
        var ids = new HashSet<String>();
        int active = 0;
        for (Item step : steps) {
            if (step == null || step.id() == null || step.id().isBlank() || step.id().length() > 64 || !ids.add(step.id()))
                throw new BizException("计划步骤 ID 必须非空、唯一且不超过 64 字");
            if (step.title() == null || step.title().isBlank() || step.title().length() > 200 || step.status() == null)
                throw new BizException("计划步骤需要状态与不超过 200 字的标题");
            if (step.status() == Status.IN_PROGRESS) active++;
        }
        if (active > 1) throw new BizException("任务计划同时只能有一个当前步骤");
        return new AgentTaskPlan(explanation, List.copyOf(steps));
    }
}
