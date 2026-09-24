package com.astra.freyja.service;

import com.astra.freyja.dto.task.TaskCenterHistoryVO;
import com.astra.freyja.dto.task.TaskCenterItemVO;

import java.util.List;

/** 聚合 AI、渲染及后续后台任务的统一查询门面。 */
public interface TaskCenterService {

    List<TaskCenterItemVO> getActiveTasks();

    TaskCenterHistoryVO getHistoryTasks(Integer limit);

    TaskCenterItemVO getTask(String sourceType, String taskId);

    boolean cancelTask(String sourceType, String taskId);
}
