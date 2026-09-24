package com.astra.freyja.service;

import com.astra.freyja.dto.render.RenderTaskQuery;
import com.astra.freyja.dto.render.RenderTaskVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * 统一渲染任务生命周期编排服务。
 * 负责 Redis 实时活跃队列管理、WebSocket 进度推流与 MySQL 历史归档。
 */
public interface RenderTaskService {

    /**
     * 注册并创建渲染任务 (写入 MySQL 初始记录 + 写入 Redis 活跃队列 + WebSocket 广播 TASK_QUEUED)
     */
    RenderTaskVO createTask(RenderTaskVO vo);

    /**
     * 更新实时任务执行进度与当前处理节点 (仅高频更新 Redis + WebSocket 广播 TASK_PROGRESS)
     */
    void updateProgress(String taskId, int progress, String currentNode);

    /**
     * 任务成功完成并归档 (更新 MySQL 终态并持久化产物 + 移除 Redis + WebSocket 广播 TASK_SUCCESS)
     */
    void finishTask(String taskId, String outputUrl, String lastFrameUrl);

    /**
     * 任务执行失败并记录异常 (更新 MySQL 异常堆栈 + 移除 Redis + WebSocket 广播 TASK_FAILED)
     */
    void failTask(String taskId, String errorMessage, String errorDetail);

    /**
     * 主动取消任务 (修改状态为 CANCELLED + 归档 MySQL + 移除 Redis + WebSocket 广播 TASK_CANCELLED)
     */
    boolean cancelTask(String taskId);

    /**
     * 获取当前所有实时排队与渲染中的活跃任务列表 (直接从 Redis 读取)
     */
    List<RenderTaskVO> getActiveTasks();

    /**
     * 获取当前活跃任务总数 (Redis)
     */
    int getActiveTaskCount();

    /**
     * 根据任务唯一 ID 查询任务详情 (先 Redis 后 MySQL)
     */
    RenderTaskVO getTaskById(String taskId);

    /**
     * 分页查询历史归档渲染记录 (直接从 MySQL 检索)
     */
    Page<RenderTaskVO> getHistoryTasks(RenderTaskQuery query);
}
