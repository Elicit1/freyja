package com.astra.freyja.service;

import com.astra.freyja.dto.script.AiTaskDTO;
import com.astra.freyja.dto.script.AiTaskQueryDTO;
import com.astra.freyja.dto.script.ChapterDecomposeHistoryVO;
import com.astra.freyja.dto.script.ScriptDecomposeResultVO;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.entity.enums.AiTaskType;

import java.util.List;

/**
 * AI 流水线任务状态机与局部重试管理服务。
 */
public interface AiTaskService {

    /**
     * 创建并初始化一个 AI Task (初始状态 PENDING)。
     */
    AiTask createTask(Long dramaId, Long episodeId, AiTaskType taskType, String targetId,
                      Long parentTaskId, String inputPayload, String modelCode, Integer maxTokens);

    /**
     * 标记任务进入 RUNNING 状态。
     */
    void markRunning(Long taskId);

    /**
     * 标记任务执行成功并记录输出载荷与耗时。
     */
    void markSuccess(Long taskId, String outputPayload, Integer consumedTokens);

    /**
     * 标记任务执行失败并记录错误信息。
     */
    void markFailed(Long taskId, String errorMessage);

    /**
     * 对单个失败任务触发局部重新执行。
     *
     * @param taskId 失败任务 ID
     * @return 重新触发后的任务实例
     */
    AiTask retryTask(Long taskId);

    /**
     * 根据条件查询 AI 任务列表。
     */
    List<AiTaskDTO> listTasks(AiTaskQueryDTO query);

    /**
     * 根据 ID 获取任务详情。
     */
    AiTaskDTO getTaskById(Long taskId);

    /**
     * 标记任务进入 PARTIAL_SUCCESS 状态 (部分成功，待局部补救重试)。
     */
    void markPartialSuccess(Long taskId, String outputPayload, String errorMessage);

    /**
     * 更新任务载荷与状态。
     */
    void updateTaskPayload(Long taskId, String status, String outputPayload, String errorMessage);

    /**
     * 查询章节拆解任务历史列表 (支持剧集/短剧强隔离)。
     */
    List<ChapterDecomposeHistoryVO> listChapterHistory(Long dramaId, Long episodeId);

    /**
     * 获取指定拆解任务已落库的完整结构化预览结果。
     */
    ScriptDecomposeResultVO getDecomposePreview(Long taskId);

    /**
     * 将指定任务及其子任务关联绑定到具体短剧 ID (用于新建短剧落库后的历史回填)。
     */
    void bindDrama(Long taskId, Long dramaId);

    /**
     * 删除拆解任务及关联的子任务。
     */
    void deleteTask(Long taskId);
}
