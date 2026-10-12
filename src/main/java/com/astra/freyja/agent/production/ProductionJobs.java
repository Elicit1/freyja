package com.astra.freyja.agent.production;

import com.astra.freyja.agent.model.AgentJson;
import com.astra.freyja.agent.model.AgentProtocol.*;
import com.astra.freyja.agent.runtime.AgentStore;
import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.agent.AgentRequests.*;
import com.astra.freyja.dto.agent.ProductionPreparationDTO.*;
import com.astra.freyja.dto.script.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class ProductionJobs {
    public static final String PROFILE = com.astra.freyja.agent.profile.AgentProfileRegistry.ASSISTANT_CODE;
    private final AgentPreparationJobMapper jobs;
    private final DramaMapper dramas;
    private final DramaShotMapper shots;
    private final org.springframework.beans.factory.ObjectProvider<AgentStore> stores;
    private AgentStore store() { return stores.getObject(); }
    private final AgentJson json;
    private final ScriptDecomposeService decompose;
    private final AiTaskService tasks;
    private final ProductionAssets assets;
    private final AiTaskMapper taskMapper;
    private final AgentConfigMapper configs;
    private final DramaEpisodeMapper episodes;
    private final AgentMessageMapper messages;

    private void validate(Start input) {
        if (input == null || !ProductionAssets.text(input.getClientRequestId()) || input.getClientRequestId().length() > 100)
            throw new BizException("需要合法的 clientRequestId");
        if (!ProductionAssets.text(input.getSourceText()) || input.getSourceText().length() > 100000)
            throw new BizException("请输入小说或剧本，最长 100000 字");
        if (input.getAspectRatio() == null || !Set.of("9:16", "16:9", "1:1", "4:3").contains(input.getAspectRatio())) throw new BizException("画幅不合法");
        if (input.getPacingPreset() == null || !Set.of("AUTO", "FAST_PACED", "COMPACT", "STANDARD", "CINEMATIC_LONG").contains(input.getPacingPreset())) throw new BizException("节奏不合法");
        if (input.getWorkflow() == null || !Set.of("SCRIPT_DECOMPOSITION", "VIDEO_PREPARATION").contains(input.getWorkflow())) throw new BizException("请选择剧本拆解或完整视频准备流程");
        if (input.getDramaId() != null && dramas.selectById(input.getDramaId()) == null) throw new BizException("项目不存在");
    }
    @Transactional public Submission start(Start input) {
        validate(input);
        if (configs.lockByCode(PROFILE) == null) throw new BizException("请先配置并启用 FREYJA 助手");
        var request = new CreateSession(); request.setAgentCode(PROFILE); request.setDramaId(input.getDramaId());
        request.setTitle(ProductionAssets.text(input.getDramaTitle()) ? input.getDramaTitle() : "小说转视频准备");
        var session = store().createSession(request, "NORMAL", "production:" + input.getClientRequestId());
        var old = jobs.selectOne(new LambdaQueryWrapper<AgentPreparationJob>().eq(AgentPreparationJob::getSessionId, session.getId()).orderByAsc(AgentPreparationJob::getId).last("LIMIT 1"));
        if (old != null) {
            if (!Objects.equals(json.write(options(old)), json.write(input))) throw new BizException(409, "同一请求标识不能提交不同原文");
            return new Submission(session.getId(), store().run(old.getRunId()).getObjectiveMessageId(), old.getRunId());
        }
        var message = new SendMessage(); message.setClientRequestId(input.getClientRequestId());
        message.setContent("把已提交的小说/剧本完成视频渲染准备。自动拆解并入库，整理文字资产，为所有新分镜选择模式与参考素材，保存交付报告。\n"
                + "制作要求：" + Objects.toString(input.getInstruction(), "遵循原文") + "\n画幅：" + input.getAspectRatio());
        var submitted = store().submit(session.getId(), message, null);
        var job = new AgentPreparationJob(); job.setRunId(submitted.runId()); job.setSessionId(session.getId());
        job.setDramaId(input.getDramaId()); job.setStatus("NEW"); job.setSourceText(input.getSourceText());
        job.setRequestJson(json.write(input)); job.setShotIdsJson("[]"); job.setAssetsJson("[]"); jobs.insert(job);
        return submitted;
    }
    /** Runs inside the existing fenced tool transaction, including retries after a restart. */
    public ToolResult prepare(ConversationStart input, ToolContext ctx) {
        if ("CONFIG_TEST".equals(ctx.snapshot().runType())) return ToolResult.error("READ_ONLY_TEST", "测试对话不能启动剧本拆解");
        var session = store().session(ctx.sessionId());
        if (!Objects.equals(session.getDramaId(), ctx.dramaId())) throw new BizException("项目作用域已改变");
        if (input.getSourceMessageId() != null && ProductionAssets.text(input.getSourceText()))
            return ToolResult.error("INVALID_SOURCE", "sourceMessageId 与 sourceText 只需提供一个");
        var options = new Start(); org.springframework.beans.BeanUtils.copyProperties(input, options);
        options.setClientRequestId("chat:" + ctx.runId()); options.setDramaId(session.getDramaId());
        if (input.getSourceMessageId() != null) {
            var source = messages.selectById(input.getSourceMessageId());
            if (source == null || !"USER".equals(source.getMessageType()) || !Objects.equals(source.getSessionId(), ctx.sessionId()))
                return ToolResult.error("INVALID_SOURCE", "原文来源必须是当前会话的真实用户消息");
            options.setSourceText(json.read(source.getContentJson(), MessageBody.class).content());
        }
        if (!ProductionAssets.text(options.getSourceText()))
            return ToolResult.needUser("请把需要拆解的小说或剧本原文发给我；有章节范围或拆解要求也可以一起告诉我。");
        validate(options);
        var old = jobs.lockByRunId(ctx.runId());
        if (old != null) {
            require(ctx, true);
            // Creating the project at commit changes the session scope, not the original request.
            options.setDramaId(options(old).getDramaId());
            if (!Objects.equals(json.write(options(old)), json.write(options)))
                throw new BizException(409, "当前任务已有原文和拆解配置，请继续原任务；新原文请作为新的聊天任务提交");
            return prepared(old);
        }
        var job = new AgentPreparationJob(); job.setRunId(ctx.runId()); job.setSessionId(ctx.sessionId());
        job.setDramaId(session.getDramaId()); job.setStatus("NEW"); job.setSourceText(options.getSourceText());
        job.setRequestJson(json.write(options)); job.setShotIdsJson("[]"); job.setAssetsJson("[]"); jobs.insert(job);
        return prepared(job);
    }
    private ToolResult prepared(AgentPreparationJob job) {
        return ToolResult.success(Map.of("status", job.getStatus(), "workflow", options(job).getWorkflow(),
                "next", "start_script_decomposition", "message", "原文已保存，继续当前会话的拆解任务，无需重新提交。"));
    }
    public AgentPreparationJob find(Long run) {
        return jobs.selectOne(new LambdaQueryWrapper<AgentPreparationJob>().eq(AgentPreparationJob::getRunId, run));
    }
    public AgentPreparationJob require(ToolContext ctx, boolean lock) {
        var job = lock ? jobs.lockByRunId(ctx.runId()) : find(ctx.runId());
        if (job == null) throw new BizException("尚未保存拆解原文。已提供原文时先调用 prepare_script_decomposition；缺少必要信息时调用 request_user_input 向用户提问，然后继续当前任务。");
        if (!Objects.equals(job.getSessionId(), ctx.sessionId())) throw new BizException("任务作用域错误");
        if (job.getDramaId() != null && !Objects.equals(job.getDramaId(), store().session(ctx.sessionId()).getDramaId()))
            throw new BizException("项目作用域已改变");
        return job;
    }
    public List<Long> targets(AgentPreparationJob job) { return Arrays.asList(json.read(job.getShotIdsJson(), Long[].class)); }
    public Start options(AgentPreparationJob job) { return json.read(job.getRequestJson(), Start.class); }
    public ToolResult decomposition(ToolContext ctx, WaitHandle handle) {
        var job = require(ctx, true);
        if (!targets(job).isEmpty()) return ToolResult.success(Map.of("status", "COMMITTED", "dramaId", job.getDramaId(), "shotIds", targets(job)));
        var r = decomposeRequest(job, ctx);
        if (job.getDecompositionTaskId() == null) {
            job.setDecompositionTaskId(Long.valueOf(decompose.startDecomposeTask(r))); job.setStatus("DECOMPOSING"); jobs.updateById(job);
            var checkpoint = new AiTask(); org.springframework.beans.BeanUtils.copyProperties(tasks.getTaskById(job.getDecompositionTaskId()), checkpoint);
            checkpoint.setInputPayload(json.write(r)); taskMapper.insert(checkpoint);
        }
        var task = tasks.getTaskById(job.getDecompositionTaskId());
        if (task == null) throw new BizException("拆解子任务不存在");
        var preview = Set.of("SUCCESS", "PARTIAL_SUCCESS").contains(task.getStatus()) ? tasks.getDecomposePreview(task.getId()) : null;
        taskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiTask>().eq(AiTask::getId, task.getId())
                .set(AiTask::getStatus, task.getStatus()).set(AiTask::getOutputPayload, preview == null ? null : json.write(preview)).set(AiTask::getErrorMessage, task.getErrorMessage()));
        if ("FAILED".equals(task.getStatus()) || "CANCELLED".equals(task.getStatus()))
            return ToolResult.error("DECOMPOSITION_FAILED", Objects.toString(task.getErrorMessage(), task.getStatus()) + "；已保留子任务，请重试失败任务");
        if ("PARTIAL_SUCCESS".equals(task.getStatus())) {
            var partial = tasks.getDecomposePreview(task.getId());
            return new ToolResult("IMMEDIATE", partial == null ? null : partial.getSegmentResults(), "PARTIAL_DECOMPOSITION", "请使用 retry_decomposition_segment 重试失败段落，然后继续等待完整拆解。", null);
        }
        if (!"SUCCESS".equals(task.getStatus())) {
            if (handle != null && !AiTaskExecutionRegistry.isActive(task.getId())) decompose.resumeDecomposeTask(task.getId(), r);
            return ToolResult.deferred(waitFor(ctx, "production-decomposition", task.getId(), handle));
        }
        if (preview == null || !"SUCCESS".equals(preview.getStatus())) return ToolResult.error("PARTIAL_DECOMPOSITION", "拆解尚不完整，不能入库。请在任务中心重试失败段落后继续。");
        job.setStatus("DECOMPOSED"); jobs.updateById(job);
        return ToolResult.success(Map.of("status", "DECOMPOSED", "taskId", task.getId(), "title", Objects.toString(preview.getDramaTitle(), ""),
                "episodeCount", preview.getEpisodes() == null ? 0 : preview.getEpisodes().size(), "next", "commit_decomposition"));
    }
    public static WaitHandle waitFor(ToolContext ctx, String adapter, Long id, WaitHandle old) {
        return old != null ? old : new WaitHandle(adapter, id.toString(), System.currentTimeMillis() + ctx.snapshot().config().getRuntime().getExternalWaitTimeoutSeconds() * 1000L);
    }
    private ScriptDecomposeRequestDTO decomposeRequest(AgentPreparationJob job, ToolContext ctx) {
        var o = options(job); var r = new ScriptDecomposeRequestDTO(); r.setDramaId(job.getDramaId()); r.setRawText(job.getSourceText());
        r.setProviderId(ctx.snapshot().config().getModel().getProviderId()); r.setModelCode(ctx.snapshot().config().getModel().getModelCode());
        r.setAspectRatio(o.getAspectRatio()); r.setStylePreset(o.getStylePreset()); r.setStyleTone(o.getStyleTone()); r.setChapterTitle(o.getChapterTitle()); r.setPacingPreset(o.getPacingPreset()); return r;
    }
    public ToolResult commit(ToolContext ctx) {
        var job = require(ctx, true);
        if (!targets(job).isEmpty()) return committed(job);
        if (job.getDecompositionTaskId() == null) throw new BizException("请先启动并等待拆解完成");
        var task = tasks.getTaskById(job.getDecompositionTaskId());
        var p = tasks.getDecomposePreview(job.getDecompositionTaskId());
        if (task == null || !"SUCCESS".equals(task.getStatus()) || p == null || !"SUCCESS".equals(p.getStatus()) || p.getEpisodes() == null || p.getEpisodes().isEmpty())
            throw new BizException("完整拆解结果尚未就绪");
        Long drama = job.getDramaId();
        if (drama != null && dramas.lockById(drama) == null) throw new BizException("项目已删除");
        Set<Long> beforeShots = drama == null ? Set.of() : new HashSet<>(shots.selectActiveByDramaId(drama).stream().map(DramaShot::getId).toList());
        Set<Long> beforeAssets = drama == null ? Set.of() : new HashSet<>(assets.catalog(drama, false).stream().map(ProductionAssets.Material::id).toList());
        var r = new ScriptDecomposeCommitDTO(); r.setTaskId(task.getId()); r.setDramaId(drama); r.setCommitMode("NEW_EPISODE");
        if (drama != null) r.setTargetEpisodeNo(episodes.selectMaxAnyEpisodeNoByDramaId(drama) + 1);
        var options = options(job);
        r.setDramaTitle(ProductionAssets.text(options.getDramaTitle()) ? options.getDramaTitle() : p.getDramaTitle());
        r.setGenre(p.getGenre()); r.setSynopsis(p.getSynopsis()); r.setAspectRatio(options.getAspectRatio());
        r.setStylePreset(ProductionAssets.text(options.getStylePreset()) ? options.getStylePreset() : p.getStylePreset());
        r.setStyleTone(ProductionAssets.text(options.getStyleTone()) ? options.getStyleTone() : p.getStyleTone());
        r.setCharacters(p.getCharacters()); r.setScenes(p.getScenes()); r.setProps(p.getProps()); r.setEpisodes(p.getEpisodes());
        drama = decompose.commit(r); store().bindProject(ctx.sessionId(), drama); job.setDramaId(drama);
        taskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiTask>().eq(AiTask::getId, task.getId()).set(AiTask::getDramaId, drama));
        List<Long> ids = shots.selectActiveByDramaId(drama).stream().map(DramaShot::getId).filter(id -> !beforeShots.contains(id)).toList();
        if (ids.isEmpty()) throw new BizException("入库未产生分镜，任务不能交付");
        job.setShotIdsJson(json.write(ids)); job.setStatus("COMMITTED");
        List<Map<String, Object>> registry = new ArrayList<>();
        for (var m : assets.catalog(drama, false)) {
            var entry = new LinkedHashMap<>(assets.candidate(m)); entry.put("key", "decomposition:" + m.type() + ":" + m.id());
            entry.put("created", !beforeAssets.contains(m.id())); registry.add(entry);
        }
        job.setAssetsJson(json.write(registry)); jobs.updateById(job); return committed(job);
    }
    private ToolResult committed(AgentPreparationJob job) {
        return ToolResult.success(Map.of("dramaId", job.getDramaId(), "shotIds", targets(job), "status", "COMMITTED", "next",
                "SCRIPT_DECOMPOSITION".equals(options(job).getWorkflow()) ? "剧本拆解已入库，向用户交付项目和分镜结果" : "list_preparation_shots 和 get_reference_candidates"));
    }
    /** Explicit scoped edits create their own delivery scope; conversational queries don't create a job. */
    public AgentPreparationJob editJob(ToolContext ctx, List<Long> ids) {
        var job = find(ctx.runId());
        if (job == null) {
            if (ctx.dramaId() == null) throw new BizException("需要绑定项目");
            job = new AgentPreparationJob(); job.setRunId(ctx.runId()); job.setSessionId(ctx.sessionId()); job.setDramaId(ctx.dramaId());
            job.setStatus("COMMITTED"); job.setRequestJson("{}"); job.setAssetsJson("[]"); job.setShotIdsJson(json.write(ids)); jobs.insert(job);
        } else if (job.getSourceText() == null) {
            var targets = new LinkedHashSet<>(targets(job)); targets.addAll(ids); job.setShotIdsJson(json.write(targets)); jobs.updateById(job);
        }
        return require(ctx, true);
    }
    @SuppressWarnings("unchecked") public List<Map<String, Object>> registry(AgentPreparationJob job) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map entry : json.read(job.getAssetsJson(), Map[].class)) entries.add(entry);
        return entries;
    }
    public ToolResult ensure(Assets input, ToolContext ctx) {
        var job = require(ctx, true);
        if (job.getDramaId() == null || targets(job).isEmpty()) throw new BizException("资产创建必须在拆解入库后执行");
        if (input.assets() == null || input.assets().isEmpty() || input.assets().size() > 20) throw new BizException("每次创建或复用 1–20 个资产");
        List<Map<String, Object>> registry = registry(job); List<Map<String, Object>> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Asset a : input.assets()) {
            if (a == null || !ProductionAssets.text(a.getKey()) || !keys.add(a.getKey())) throw new BizException("资产 key 必填且批次内唯一");
            if (a.getShotId() != null && !targets(job).contains(a.getShotId())) throw new BizException("关键图分镜不属于本次任务");
            Map<String, Object> found = registry.stream().filter(m -> Objects.equals(m.get("key"), a.getKey())).findFirst().orElse(null);
            if (found != null) {
                if (!(found.get("request") instanceof String savedRequest) || !Objects.equals(json.write(json.read(savedRequest, Asset.class)), json.write(a)))
                    throw new BizException(409, "资产 key 已使用，请复用已返回 ID 或使用新 key");
                result.add(found); continue;
            }
            Long id = a.getExistingId() != null ? a.getExistingId() : assets.create(a, job.getDramaId(), options(job).getAspectRatio());
            if (a.isUpdateText()) {
                if (a.getExistingId() == null) throw new BizException("补齐文字需要 existingId");
                assets.updateText(a, job.getDramaId());
            }
            var m = assets.resolve(a.getType(), id, job.getDramaId());
            Map<String, Object> entry = new LinkedHashMap<>(assets.candidate(m));
            entry.put("key", a.getKey()); entry.put("request", json.write(a)); entry.put("created", a.getExistingId() == null);
            registry.add(entry); result.add(entry);
        }
        job.setAssetsJson(json.write(registry)); job.setReportJson(null);
        jobs.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentPreparationJob>().eq(AgentPreparationJob::getId, job.getId())
                .set(AgentPreparationJob::getAssetsJson, job.getAssetsJson()).set(AgentPreparationJob::getReportJson, null));
        return ToolResult.success(result);
    }
    public Object report(Long run) {
        store().run(run); var job = find(run); return job == null || job.getReportJson() == null ? null : json.read(job.getReportJson(), Map.class);
    }
}
