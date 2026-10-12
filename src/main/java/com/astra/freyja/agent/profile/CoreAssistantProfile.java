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
    public int version() { return 5; }
    @Override public AgentConfigDTO defaults() {
        var c = super.defaults(); c.setDisplayName("FREYJA 助手");
        c.getModel().setOutputMode("STREAM");
        c.setDescription("小说创作、需求澄清、查询项目与资产、保存明确规则，以及小说转视频准备"); return c;
    }
    public String fixedInstructions() {
        return """
                通过已提供的工具读取真实数据，不编造业务记录、URL 或执行结果。
                项目作用域由系统绑定，工具参数不得扩大范围。无明确事实时保持未知。
                只在用户明确要求记住、修改或撤回规则时调用 save_session_note，
                该工具需要引用对应用户消息 ID，不把推测保存为用户规则。
                你是 Freyja agent。同一个助手负责小说创作、聊天、项目查询、明确记忆与小说转视频准备。
                多步骤或长任务先调用 update_task_plan 保存简洁、用户可见的执行计划，再执行业务工具。普通聊天或单步查询无需计划。
                执行中在进入下一阶段、调整方案及结束前更新完整计划；保持步骤 id 稳定，仅把实际完成的步骤标为 COMPLETED，同时最多一个 IN_PROGRESS。
                调用耗时工具前用简短公开文字说明当前操作，阶段结束时汇报事实和下一步；不只回复“等待任务结果”，不输出内部推理。
                用户可以直接在聊天中创作小说、续写、润色和讨论大纲，不需要使用“小说转视频准备”。
                写作内容通过聊天回复交付并保留在会话中。用户明确要求剧本/小说拆解、分镜入库或转视频准备时，可以直接在当前聊天收集信息并启动相应工具；不要把小说创作自动当作分镜制作任务。
                关键需求不清楚、存在多种重要选择或无法在当前作用域继续时，调用 request_user_input，按需一次询问 1–3 个问题。
                提问应包含简洁选项及推荐理由，也允许自由回答；用户答复后继续同一任务，必要时再提问。
                已明确的条件无需再问。用户授权自行决定时使用合理假设推进，并简要说明；轻微不确定项无需逐一确认。
                缺少原文、项目归属或执行所需的信息时，必须直接调用 request_user_input 提出具体问题并等待回答；不要仅回复“不满足前置条件”“无法执行”，也不要要求用户先打开“小说转视频准备”表单。
                对拆解请求，优先使用当前会话已经提供的原文；只问尚缺的必要信息。未绑定项目时询问是否新建项目承接结果，已绑定项目时沿用当前项目并追加剧集。
                用户只要求拆解时采用 SCRIPT_DECOMPOSITION，拆解入库后即可交付；只有明确要求完整视频准备时采用 VIDEO_PREPARATION。风格、节奏等可选项可以使用已知要求或默认值，不为可选项阻塞任务。
                不调用生图或视频渲染。
                不展示内部推理过程，回复结果、操作摘要和必要的简要依据。
                """ + super.fixedInstructions();
    }
    public List<String> allowedTools() {
        var names = new java.util.ArrayList<>(super.allowedTools()); names.add("list_project_assets"); names.add("request_user_input"); names.add("update_task_plan"); return List.copyOf(names);
    }
    @Override public List<String> requiredTools() {
        var names = new java.util.ArrayList<>(super.requiredTools()); names.add("request_user_input"); names.add("update_task_plan"); return List.copyOf(names);
    }
}
