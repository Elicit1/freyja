package com.astra.freyja.agent.profile;

import com.astra.freyja.agent.production.*;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.dto.agent.AgentConfigDTO;
import lombok.RequiredArgsConstructor;
import java.util.*;

/** Shared production capabilities; only CoreAssistantProfile is registered as an assistant. */
@RequiredArgsConstructor
public class ProductionPreparationProfile implements AgentProfile {
    private final com.astra.freyja.dao.AgentPreparationJobMapper jobs;
    private final org.springframework.beans.factory.ObjectProvider<ProductionPlans> plans;
    public String code() { return ProductionJobs.PROFILE; }
    public int version() { return 1; }
    public List<String> allowedTools() {
        return List.of("prepare_script_decomposition", "start_script_decomposition", "retry_decomposition_segment", "commit_decomposition", "list_preparation_shots", "get_reference_candidates",
                "ensure_text_assets", "save_shot_production_plans", "prepare_shot_prompts", "inspect_preparation", "get_preparation_report",
                "get_project_overview", "get_shot_detail", "save_session_note", "list_session_notes", "load_skill", "read_skill_file");
    }
    public List<String> requiredTools() { return List.of("prepare_script_decomposition", "start_script_decomposition", "commit_decomposition", "list_preparation_shots", "get_reference_candidates", "ensure_text_assets", "save_shot_production_plans", "prepare_shot_prompts", "inspect_preparation"); }
    public AgentConfigDTO defaults() {
        var c = AgentProfile.super.defaults(); c.setDisplayName("分镜制作准备"); c.setDescription("自动拆解小说、整理文字资产、选择分镜模式与参考素材，交付缺图清单");
        c.getRuntime().setMaxModelTurns(60); c.getRuntime().setMaxToolCalls(150); c.getRuntime().setExecutionTimeoutSeconds(1800);
        c.getMemory().setContextTokens(262144); c.getMemory().setRecentMessages(10);
        c.getModel().setMaxTokens(65536); c.getModel().setTokenMode("MODEL"); return c;
    }
    public String fixedInstructions() {
        return """
                你具备 FREYJA 剧本拆解与分镜制作准备能力，执行范围以用户明确请求和任务 workflow 为准。
                用户明确要求拆解或转视频准备但尚无制作检查点时，缺少必要信息先调用 request_user_input 提问，信息齐全后调用 prepare_script_decomposition 在当前 Run 保存原文和配置，再执行 start_script_decomposition → commit_decomposition。无需打开额外表单，不把“尚无任务”当作拒绝理由。
                workflow=SCRIPT_DECOMPOSITION 时，完整拆解入库后直接交付结果，不继续扩展制作规划。workflow=VIDEO_PREPARATION 时，在入库后继续 get_reference_candidates / list_preparation_shots → ensure_text_assets（仅缺少的资产）→ save_shot_production_plans → prepare_shot_prompts（仅必需图片齐全的镜头）→ inspect_preparation。普通聊天、小说创作与查询直接回答或读取事实，不启动拆解。
                对所有目标镜头逐批规划，继续分页直至覆盖完整。结合镜头叙事选择 FIRST_LAST_FRAME 或 REFERENCE_MODE，保存简要依据。
                文字资产每次处理1–4项，详细分镜规划每次保存1–3镜，避免大型工具参数超过输出预算。遇到参数截断时缩小批次；不要重发同样的大批参数。
                decisionReason必须解释本镜头的叙事、构图或连续性需要。不要把临时校验、参数试探或接口调试写成制作依据；调用inspect_preparation前复核每镜已经保存的是最终业务方案。
                优先复用拆解已经入库的人物、造型、场景和道具。缺少起止构图关键图时先创建完整文字关键图，再引用工具返回ID。资产未明确的剧情事实保持未知。
                人物身份和服装造型分开保存。造型图sourceId=lookId，characterId/ lookId单独填写。SUBJECT表示可见，画外人物用NON_SUBJECT；参考用途与是否出镜独立。
                visualAnchor={sourceType:SCENE或KEYFRAME,sourceId:真实ID}，场景与关键图互斥。每个referenceSlot需slotKey、sourceType、sourceId、usageRole、required、description。人物还需characterId、lookId、referenceRole。
                FIRST_LAST_FRAME需firstFrameRequirement={required:true,keyframeId:已创建FIRST_FRAME关键图ID,description:具体构图}；lastFrameRequirement按叙事需要指定。缺图片保存文字资产和计划即可。
                图片缺失/STALE是正常交付结果，不需要问用户、不阻塞完整规划，也不能编造图片URL、Picture编号或声称素材已就绪。素材齐全的镜头需要完成prepare_shot_prompts，才算READY。
                工具返回冲突时重新查询最新指纹再规划，不覆盖用户锁定。用户补充要求后以最新要求为准。不要自行切换项目或扩大修改范围。
                所有保存必须实际调用工具。仅拆解任务在commit_decomposition返回COMMITTED后说明项目和分镜结果；完整视频准备任务在inspect_preparation返回complete=true后说明项目、分镜数量、模式选择和缺图清单。任务不调用生图、视频渲染或配音。
                后续会话查询先读取数据库事实；用户明确指定镜头修改时只修改指定镜头。只在用户要求记忆时保存用户规则。
                """;
    }
    @Override public CompletionDecision checkCompletion(ModelReply reply, Snapshot snapshot, Long runId) {
        var base = AgentProfile.super.checkCompletion(reply, snapshot);
        if (!base.complete()) return base;
        var job = jobs.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.astra.freyja.entity.AgentPreparationJob>()
                .eq(com.astra.freyja.entity.AgentPreparationJob::getRunId, runId));
        if (job == null) return base; // Ordinary follow-up queries remain conversational.
        var json = new com.astra.freyja.agent.model.AgentJson();
        var options = json.read(job.getRequestJson(), com.astra.freyja.dto.agent.ProductionPreparationDTO.Start.class);
        if ("SCRIPT_DECOMPOSITION".equals(options.getWorkflow())) {
            boolean committed = "COMMITTED".equals(job.getStatus()) && job.getDramaId() != null
                    && json.read(job.getShotIdsJson(), Long[].class).length > 0;
            return committed ? CompletionDecision.done() : new CompletionDecision(false,
                    "剧本拆解尚未完成入库，请继续 start_script_decomposition 并等待完整结果，再调用 commit_decomposition；缺少必要信息时用 request_user_input 提问。", false);
        }
        return plans.getObject().isDeliveryCurrent(job) ? CompletionDecision.done()
                : new CompletionDecision(false, "制作任务尚未交付。请完成所有目标分镜的制作规划；素材齐全时生成提示词，缺图时保留文字需求；最后调用 inspect_preparation 保存完整报告后再回复。", false);
    }
}
