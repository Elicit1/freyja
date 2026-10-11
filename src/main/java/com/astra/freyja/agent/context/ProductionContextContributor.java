package com.astra.freyja.agent.context;

import com.astra.freyja.agent.model.AgentProtocol.Snapshot;
import com.astra.freyja.agent.production.*;
import com.astra.freyja.entity.AgentSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class ProductionContextContributor implements ContextContributor {
    private final ProductionJobs jobs;
    public boolean supports(String code) { return ProductionJobs.PROFILE.equals(code); }
    public String contribute(AgentSession s, Snapshot snapshot) {
        var job = s.getActiveRunId() == null ? null : jobs.find(s.getActiveRunId());
        return job == null ? "当前是制作会话的后续咨询。查询真实项目事实，用户明确要求镜头修改时再写入。"
                : "当前制作任务检查点：status=" + job.getStatus() + ", dramaId=" + job.getDramaId() + ", decompositionTaskId=" + job.getDecompositionTaskId()
                + ", targetShotCount=" + jobs.targets(job).size() + "。原文已持久化，start_script_decomposition使用已保存原文，不要请求用户重复粘贴。";
    }
}
