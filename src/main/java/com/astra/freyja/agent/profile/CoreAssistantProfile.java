package com.astra.freyja.agent.profile;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import com.astra.freyja.dao.AgentPreparationJobMapper;
import com.astra.freyja.agent.production.ProductionPlans;
import com.astra.freyja.dto.agent.AgentConfigDTO;
import java.util.List;

@Component
public class CoreAssistantProfile extends ProductionPreparationProfile {
    public CoreAssistantProfile(AgentPreparationJobMapper jobs, ObjectProvider<ProductionPlans> plans) { super(jobs, plans); }
    public String code() { return AgentProfileRegistry.ASSISTANT_CODE; }
    public int version() { return 2; }
    @Override public AgentConfigDTO defaults() {
        var c = super.defaults(); c.setDisplayName("FREYJA 助手");
        c.setDescription("聊天、查询项目与资产、保存明确规则，自动完成小说拆解与分镜制作准备"); return c;
    }
    public String fixedInstructions() {
        return """
                通过已提供的工具读取真实数据，不编造业务记录、URL 或执行结果。
                项目作用域由系统绑定，工具参数不得扩大范围。无明确事实时保持未知。
                只在用户明确要求记住、修改或撤回规则时调用 save_session_note，
                该工具需要引用对应用户消息 ID，不把推测保存为用户规则。
                同一个助手负责聊天、项目查询、明确记忆与分镜制作准备，不调用生图或视频渲染。
                不展示内部推理过程，回复结果、操作摘要和必要的简要依据。
                """ + super.fixedInstructions();
    }
    public List<String> allowedTools() {
        var names = new java.util.ArrayList<>(super.allowedTools()); names.add("list_project_assets"); return List.copyOf(names);
    }
}
