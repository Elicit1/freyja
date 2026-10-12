package com.astra.freyja.agent.context;

import com.astra.freyja.agent.model.AgentProtocol.Snapshot;
import com.astra.freyja.agent.production.*;
import com.astra.freyja.entity.AgentSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class ProductionContextContributor implements ContextContributor {
    private final ProductionJobs jobs;
    public boolean supports(String code) { return ProductionJobs.PROFILE.equals(com.astra.freyja.agent.profile.AgentProfileRegistry.canonicalCode(code)); }
    public String contribute(AgentSession s, Snapshot snapshot) {
        var job = s.getActiveRunId() == null ? null : jobs.find(s.getActiveRunId());
        return job == null ? "当前尚无制作任务检查点。普通聊天、创作与查询正常处理；用户明确要求拆解剧本或转视频准备时，缺少必要信息必须调用 request_user_input 直接提问，信息齐全则调用 prepare_script_decomposition 在当前任务建立检查点，然后继续拆解。不要把前置条件当作拒绝理由或要求用户打开额外表单。"
                : "当前制作任务检查点：status=" + job.getStatus() + ", dramaId=" + job.getDramaId() + ", decompositionTaskId=" + job.getDecompositionTaskId()
                + ", workflow=" + jobs.options(job).getWorkflow() + ", targetShotCount=" + jobs.targets(job).size()
                + "。原文已持久化，start_script_decomposition使用已保存原文，不要请求用户重复粘贴。SCRIPT_DECOMPOSITION 仅拆解入库后交付；VIDEO_PREPARATION 继续制作规划与报告。";
    }
}
