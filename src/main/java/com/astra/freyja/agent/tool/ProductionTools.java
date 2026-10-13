package com.astra.freyja.agent.tool;

import com.astra.freyja.agent.production.*;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.dto.agent.ProductionPreparationDTO.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration @RequiredArgsConstructor
public class ProductionTools {
    public record Empty() {}
    public record Candidates(boolean includePublic) {}
    private final ProductionJobs jobs;
    private final ProductionPlans plans;
    private final ProductionAssets assets;
    private final ProductionTaskRecovery recovery;
    private abstract static class Tool<I> implements AgentTool<I> {
        private final String name, description;
        private final Class<I> input;
        private final boolean write, project;
        Tool(String name, String description, Class<I> input, boolean write, boolean project) {
            this.name = name; this.description = description; this.input = input; this.write = write; this.project = project;
        }
        public String name() { return name; }
        public String description() { return description; }
        public Class<I> inputType() { return input; }
        public Effect effect() { return write ? Effect.WRITE : Effect.READ; }
        public RecoveryMode recoveryMode() { return write ? RecoveryMode.TRANSACTIONAL : RecoveryMode.REPLAY_SAFE; }
        public boolean projectRequired() { return project; }
    }
    @Bean AgentTool<ConversationStart> prepareScriptDecomposition() {
        return new Tool<>("prepare_script_decomposition", "在当前聊天任务保存用户已提供的拆解原文和要求，不新建会话。缺原文时先用 request_user_input 提问。sourceMessageId 可引用当前会话中整条原文用户消息，避免复写长文；消息包含额外指令时用 sourceText 提供准确原文，两者只选一个。自动沿用绑定项目，无绑定时须确认新建项目；不能切换项目。workflow=SCRIPT_DECOMPOSITION 仅拆解入库（默认），用户明确要求完整视频准备时才用 VIDEO_PREPARATION。画幅先明确选择启用比例，已有项目继承其比例；用户明确委托AI选择时优先启用16:9。节奏默认AUTO；其余字段可省略，已知要求无需重复询问。保存后调用 start_script_decomposition。", ConversationStart.class, true, false) {
            public ToolResult execute(ConversationStart i, ToolContext c) { return jobs.prepare(i, c); }
        };
    }
    @Bean AgentTool<Empty> startScriptDecomposition() {
        return new Tool<>("start_script_decomposition", "使用用户已提交的原文和配置启动拆解。无参数。自动等待既有任务，恢复时不会重复拆解；完整成功后调用 commit_decomposition。", Empty.class, true, false) {
            public ToolResult execute(Empty i, ToolContext c) { return jobs.decomposition(c, null); }
            public ToolResult reconcile(Empty i, ToolContext c, WaitHandle w) { return jobs.decomposition(c, w); }
        };
    }
    @Bean AgentTool<Empty> commitDecomposition() {
        return new Tool<>("commit_decomposition", "将完整拆解自动入库，返回绑定项目和目标分镜 ID。仅追加新剧集，重复调用返回同一结果。", Empty.class, true, false) {
            public ToolResult execute(Empty i, ToolContext c) { return jobs.commit(c); }
        };
    }
    @Bean AgentTool<ProductionTaskRecovery.SegmentRetry> retryDecompositionSegment() {
        return new Tool<>("retry_decomposition_segment", "对部分失败拆解的一个FAILED段落重试；segmentId取自拆解结果。instruction可补充已有要求。不重拆已成功段落，不入库，不重复创建子任务。", ProductionTaskRecovery.SegmentRetry.class, true, false) {
            public ToolResult execute(ProductionTaskRecovery.SegmentRetry i, ToolContext c) { return recovery.retry(i, c, null); }
            public ToolResult reconcile(ProductionTaskRecovery.SegmentRetry i, ToolContext c, WaitHandle w) { return recovery.retry(i, c, w); }
        };
    }
    @Bean AgentTool<ShotQuery> listPreparationShots() {
        return new Tool<>("list_preparation_shots", "分页读取本任务分镜、已有绑定、规划、锁和并发指纹。limit≤12，续页使用 nextAfterShotId，必须遍历所有目标分镜。", ShotQuery.class, false, true) {
            public ToolResult execute(ShotQuery i, ToolContext c) { return ToolResult.success(plans.list(i, c)); }
        };
    }
    @Bean AgentTool<Candidates> referenceCandidates() {
        return new Tool<>("get_reference_candidates", "获取当前项目的真实人物/造型、场景、道具、关键图候选，包括文字设定、真实 ID 和图片状态。includePublic 显式读取公共库。每类最多200条。图片缺失需要保留槽位，不能编造 URL。", Candidates.class, false, true) {
            public ToolResult execute(Candidates i, ToolContext c) { return ToolResult.success(assets.catalog(c.dramaId(), i.includePublic()).stream().map(assets::candidate).toList()); }
        };
    }
    @Bean AgentTool<Assets> ensureTextAssets() {
        return new Tool<>("ensure_text_assets", "批量创建或明确复用文字资产。type=CHARACTER/CHARACTER_LOOK/SCENE/PROP/KEYFRAME。创建需要唯一key、name、中文description、英文prompt；existingId指定复用。已有文字不完整时，existingId+updateText=true+description+prompt补齐本项目原资产，不创建副本，公共库只读。造型需要characterId、lookType、outfitPrompt；关键图需要frameType七种用途之一，可绑定本任务shotId；不生成图片。", Assets.class, true, true) {
            public ToolResult execute(Assets i, ToolContext c) { return jobs.ensure(i, c); }
        };
    }
    @Bean AgentTool<Plans> saveProductionPlans() {
        return new Tool<>("save_shot_production_plans", "批量保存并应用1–10个分镜规划。必须使用查询返回的expectedRevision和expectedFingerprint。模式FIRST_LAST_FRAME/REFERENCE_MODE；人物需明确characterId/lookId和SUBJECT/NON_SUBJECT。可见人物必须有必需的CHARACTER_LOOK参考(sourceId=lookId, characterId,lookId,usageRole=SUBJECT,referenceRole=COMBINED等)。SCENE槽位usageRole=SCENE_LAYOUT；PROP=PROP_APPEARANCE；KEYFRAME=七种用途。各槽位需description、required。首尾帧模式必须先创建FIRST_FRAME关键图并设firstFrameRequirement(required=true,keyframeId,description)，尾帧按需要。主视觉只能选一个场景或关键图。不覆盖用户锁或并发编辑。缺图是正常待补状态。", Plans.class, true, true) {
            public ToolResult execute(Plans i, ToolContext c) { return plans.save(i, c); }
        };
    }
    @Bean AgentTool<ShotInput> prepareShotPrompts() {
        return new Tool<>("prepare_shot_prompts", "为素材齐全的分镜调用既有导演/提示词AI并保存当前模式提示词，shotId和expectedFingerprint取自最新查询。缺必需图片时直接返回待补图，无需生成。自动等待异步任务，拒绝旧结果覆盖新编辑。", ShotInput.class, true, true) {
            public ToolResult execute(ShotInput i, ToolContext c) { return plans.prompt(i, c, null); }
            public ToolResult reconcile(ShotInput i, ToolContext c, WaitHandle w) { return plans.prompt(i, c, w); }
        };
    }
    @Bean AgentTool<Empty> inspectPreparation() {
        return new Tool<>("inspect_preparation", "检查所有目标分镜覆盖率、素材和提示词状态，保存交付报告。incompleteShotIds非空时继续处理；缺图允许完成，会去重汇总需用户生成图片的资产和受影响分镜。完成前必须成功调用。", Empty.class, true, true) {
            public ToolResult execute(Empty i, ToolContext c) { return plans.inspect(c); }
        };
    }
    @Bean AgentTool<Empty> getPreparationReport() {
        return new Tool<>("get_preparation_report", "读取当前制作任务已保存的交付报告。", Empty.class, false, false) {
            public ToolResult execute(Empty i, ToolContext c) { return ToolResult.success(jobs.report(c.runId())); }
        };
    }
}
