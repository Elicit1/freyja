package com.astra.freyja.agent.tool;

import com.astra.freyja.agent.model.UserQuestions;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.common.BizException;
import org.springframework.stereotype.Component;

@Component
public class RequestUserInputTool implements AgentTool<UserQuestions> {
    public String name() { return "request_user_input"; }
    public String description() {
        return "遇到会影响结果的重要不确定项时向用户提问，并暂停本任务等待回答。一次 1–3 题，"
                + "每题提供唯一 id、简短 header、完整 question 和 options。选择题提供 2–3 个互斥选项，"
                + "每项含 label、description、recommended（最多一个为 true）；自由回答题 options=[]。"
                + "界面始终允许自定义回答，不要添加‘其他’选项。不重复询问用户已经明确的要求。";
    }
    public Class<UserQuestions> inputType() { return UserQuestions.class; }
    public RecoveryMode recoveryMode() { return RecoveryMode.TRANSACTIONAL; }
    public ToolResult execute(UserQuestions input, ToolContext context) {
        try { return ToolResult.needUser(input.validated()); }
        catch (BizException invalid) { return ToolResult.error("INVALID_QUESTIONS", invalid.getMessage()); }
    }
}
