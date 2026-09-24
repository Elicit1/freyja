package com.astra.freyja.dto.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务中心统一任务摘要。列表只返回导航和状态信息，具体输入输出载荷按任务详情再读取。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskCenterItemVO implements Serializable {

    /** 任务来源：AI_TASK / RENDER_TASK */
    private String sourceType;
    /** 业务任务 ID，始终按字符串向前端传输 */
    private String taskId;
    private String category;
    private String taskType;
    private String title;
    private String status;
    private Integer progress;
    private String currentStage;
    private String modelCode;
    private String errorMessage;

    private String dramaId;
    private String dramaTitle;
    private String episodeId;
    private String episodeName;
    private String sceneId;
    private String sceneName;
    private String shotId;
    private Integer shotNo;
    private String shotName;
    private String targetType;
    private String targetId;
    /** 前端恢复动作枚举，例如 SHOT_PROMPT_DERIVE / SHOT_RENDER / SCRIPT_DECOMPOSE */
    private String resumeAction;

    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Boolean unread;
}
