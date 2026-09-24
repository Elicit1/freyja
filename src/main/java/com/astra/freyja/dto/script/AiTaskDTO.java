package com.astra.freyja.dto.script;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 任务 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTaskDTO implements Serializable {

    private Long id;
    private Long dramaId;
    private Long episodeId;
    private String taskType;
    private String status;
    private String targetId;
    private Long parentTaskId;
    private String inputPayload;
    private String outputPayload;
    private String modelCode;
    private Integer maxTokens;
    private Integer consumedTokens;
    private Integer retryCount;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createTime;
}
