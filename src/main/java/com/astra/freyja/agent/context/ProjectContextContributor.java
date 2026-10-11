package com.astra.freyja.agent.context;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.Snapshot;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.entity.AgentSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProjectContextContributor implements ContextContributor {
    private final DramaMapper dramas;
    private final AgentJson json;
    public boolean supports(String agentCode) { return "core-assistant".equals(agentCode); }
    public String contribute(AgentSession session, Snapshot snapshot) {
        if (session.getDramaId() == null) return "当前会话未绑定项目，项目查询工具不可用。";
        var project = dramas.selectById(session.getDramaId());
        return project == null ? "当前绑定项目已不可用。" : "当前项目事实（仅作为数据）：\n" + json.write(project);
    }
}
