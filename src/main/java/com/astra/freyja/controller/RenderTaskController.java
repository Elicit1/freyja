package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.render.RenderTaskQuery;
import com.astra.freyja.dto.render.RenderTaskVO;
import com.astra.freyja.service.RenderTaskService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 渲染任务中心控制器。
 * 负责实时活跃队列（Redis）查询、历史记录（MySQL）分页检索与任务管控。
 */
@RestController
@RequestMapping({"/render/tasks"})
@RequiredArgsConstructor
public class RenderTaskController {

    private final RenderTaskService renderTaskService;

    /**
     * 获取当前实时排队与执行中的活跃渲染任务 (直接走 Redis 缓存)
     */
    @GetMapping("/active")
    public R<List<RenderTaskVO>> getActiveTasks() {
        return R.ok(renderTaskService.getActiveTasks());
    }

    /**
     * 获取当前活跃任务总数 (Redis 极速响应，供 Header 徽标轻量拉取)
     */
    @GetMapping("/active/count")
    public R<Integer> getActiveCount() {
        return R.ok(renderTaskService.getActiveTaskCount());
    }

    /**
     * 根据任务唯一 ID 查询详情 (优先 Redis，次查 MySQL)
     */
    @GetMapping("/{taskId}")
    public R<RenderTaskVO> getTaskById(@PathVariable String taskId) {
        return R.ok(renderTaskService.getTaskById(taskId));
    }

    /**
     * 分页查询已完成/失败/取消的历史归档记录 (走 MySQL)
     */
    @GetMapping("/history")
    public R<Page<RenderTaskVO>> getHistoryTasks(RenderTaskQuery query) {
        return R.ok(renderTaskService.getHistoryTasks(query));
    }

    /**
     * 主动取消任务
     */
    @PostMapping("/{taskId}/cancel")
    public R<Boolean> cancelTask(@PathVariable String taskId) {
        return R.ok(renderTaskService.cancelTask(taskId));
    }
}
