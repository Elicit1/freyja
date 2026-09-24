package com.astra.freyja.dto.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** 任务中心历史列表响应。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskCenterHistoryVO implements Serializable {
    private List<TaskCenterItemVO> records;
    private long total;
}
