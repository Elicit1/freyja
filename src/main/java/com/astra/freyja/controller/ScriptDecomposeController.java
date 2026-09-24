package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.script.*;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.service.AiTaskService;
import com.astra.freyja.service.ScriptDecomposeService;
import com.astra.freyja.service.ScriptDecomposeDraftStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 剧本智能拆解与 AI 流水线任务调度控制器。
 */
@RestController
@RequestMapping("/script")
@RequiredArgsConstructor
public class ScriptDecomposeController {

    private final ScriptDecomposeService scriptDecomposeService;
    private final AiTaskService aiTaskService;
    @Autowired(required = false)
    private ScriptDecomposeDraftStore draftStore;

    /**
     * 一键多阶段智能剧本拆解 (调用大模型流水线提取情节、角色消歧、分镜组与分镜连续性分析)
     */
    @PostMapping("/decompose")
    public R<ScriptDecomposeResultVO> decompose(@RequestBody ScriptDecomposeRequestDTO request) {
        return R.ok(scriptDecomposeService.decompose(request));
    }

    /**
     * 流式 (SSE) 一键智能剧本拆解 (实时推送 Stage 进展与片元，并在完成时推送完整结构化对象)
     */
    @PostMapping(value = "/decompose/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter decomposeStream(@RequestBody ScriptDecomposeRequestDTO request) {
        return scriptDecomposeService.decomposeStream(request);
    }

    @PostMapping("/decompose/task")
    public R<String> startDecomposeTask(@RequestBody ScriptDecomposeRequestDTO request) {
        return R.ok(scriptDecomposeService.startDecomposeTask(request));
    }

    /**
     * 确认并一键将拆解结果持久化入库 (短剧、剧集、场次、连续镜头组、分镜、角色与场景资产)
     */
    @PostMapping("/commit")
    public R<Long> commit(@RequestBody ScriptDecomposeCommitDTO commitDTO) {
        return R.ok(scriptDecomposeService.commit(commitDTO));
    }

    /**
     * 单集/单场深度细化拆解
     */
    @PostMapping("/decompose/episode")
    public R<DecomposedEpisodeVO> decomposeEpisode(@RequestBody ScriptDecomposeRequestDTO request) {
        return R.ok(scriptDecomposeService.decomposeEpisode(request));
    }

    /**
     * 查询 AI 拆解任务列表
     */
    @GetMapping("/task/list")
    public R<List<AiTaskDTO>> listTasks(AiTaskQueryDTO query) {
        return R.ok(aiTaskService.listTasks(query));
    }

    /**
     * 获取单个 AI 任务详情
     */
    @GetMapping("/task/{taskId}")
    public R<AiTaskDTO> getTaskById(@PathVariable("taskId") Long taskId) {
        return R.ok(aiTaskService.getTaskById(taskId));
    }

    /**
     * 针对失败的单任务触发局部重新执行
     */
    @PostMapping("/task/{taskId}/retry")
    public R<AiTask> retryTask(@PathVariable("taskId") Long taskId) {
        return R.ok(aiTaskService.retryTask(taskId));
    }

    /**
     * 查询章节拆解历史记录列表 (支持短剧与剧集强隔离)
     */
    @GetMapping("/task/history")
    public R<List<ChapterDecomposeHistoryVO>> listHistory(@RequestParam(required = false) Long dramaId,
                                                         @RequestParam(required = false) Long episodeId) {
        return R.ok(aiTaskService.listChapterHistory(dramaId, episodeId));
    }

    /**
     * 获取指定拆解任务已落库的完整结构化预览结果 (用于历史记录一键载入恢复)
     */
    @GetMapping("/task/{taskId}/preview")
    public R<ScriptDecomposeResultVO> getTaskPreview(@PathVariable("taskId") Long taskId) {
        // Redis 中的任务已创建但流水线尚未产出最终结果时，前端应继续订阅日志。
        // 预览是可选的最终产物，运行中返回空值不是业务错误。
        if (draftStore != null && draftStore.getTask(taskId) != null && draftStore.getResult(taskId) == null) {
            return R.ok(null);
        }
        return R.ok(aiTaskService.getDecomposePreview(taskId));
    }

    @GetMapping("/task/{taskId}/draft")
    public R<ScriptDecomposeRequestDTO> getTaskDraft(@PathVariable("taskId") Long taskId) {
        if (draftStore == null || draftStore.getTask(taskId) == null) {
            throw new com.astra.freyja.common.BizException(404, "拆解草稿不存在或已过期");
        }
        return R.ok(draftStore.getRequest(taskId));
    }

    /**
     * 针对失败的单 Worker 分段进行局部提示词微调并重新执行合并
     */
    @PostMapping("/task/{taskId}/retry-worker")
    public R<ScriptDecomposeResultVO> retryWorker(@PathVariable("taskId") Long taskId,
                                                  @RequestBody WorkerRetryDTO retryDTO) {
        return R.ok(scriptDecomposeService.retryWorker(taskId, retryDTO));
    }

    /**
     * 删除指定的拆解任务及关联的子任务记录
     */
    @DeleteMapping("/task/{taskId}")
    public R<Boolean> deleteTask(@PathVariable("taskId") Long taskId) {
        aiTaskService.deleteTask(taskId);
        return R.ok(true);
    }
}
