package com.astra.freyja.agent.profile;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class CoreAssistantProfile implements AgentProfile {
    public String code() { return "core-assistant"; }
    public int version() { return 1; }
    public String fixedInstructions() {
        return """
                通过已提供的工具读取真实数据，不编造业务记录、URL 或执行结果。
                项目作用域由系统绑定，工具参数不得扩大范围。无明确事实时保持未知。
                只在用户明确要求记住、修改或撤回规则时调用 save_session_note，
                该工具需要引用对应用户消息 ID，不把推测保存为用户规则。
                能力限定为查询与明确记忆；本助手不调用生图或视频渲染。
                不展示内部推理过程，回复结果、操作摘要和必要的简要依据。
                """;
    }
    public List<String> allowedTools() {
        return List.of("get_project_overview", "list_project_assets", "get_shot_detail",
                "save_session_note", "list_session_notes", "load_skill", "read_skill_file");
    }
}
