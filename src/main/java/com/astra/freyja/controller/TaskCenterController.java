package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.task.TaskCenterHistoryVO;
import com.astra.freyja.dto.task.TaskCenterItemVO;
import com.astra.freyja.service.TaskCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 全局任务中心接口。 */
@RestController
@RequestMapping("/task-center")
@RequiredArgsConstructor
public class TaskCenterController {

    private final TaskCenterService taskCenterService;

    @GetMapping("/active")
    public R<List<TaskCenterItemVO>> getActiveTasks() {
        return R.ok(taskCenterService.getActiveTasks());
    }

    @GetMapping("/history")
    public R<TaskCenterHistoryVO> getHistoryTasks(
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        return R.ok(taskCenterService.getHistoryTasks(limit));
    }

    @GetMapping("/{sourceType}/{taskId}")
    public R<TaskCenterItemVO> getTask(@PathVariable String sourceType,
                                       @PathVariable String taskId) {
        return R.ok(taskCenterService.getTask(sourceType, taskId));
    }

    @PostMapping("/{sourceType}/{taskId}/cancel")
    public R<Boolean> cancelTask(@PathVariable String sourceType,
                                 @PathVariable String taskId) {
        return R.ok(taskCenterService.cancelTask(sourceType, taskId));
    }
}
