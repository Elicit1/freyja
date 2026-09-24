package com.astra.freyja.dto.script;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collection;

/**
 * AI 任务查询过滤 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTaskQueryDTO implements Serializable {

    private Long dramaId;
    private Long episodeId;
    private String taskType;
    private String status;
    private Collection<String> statuses;
    private String targetId;
}

