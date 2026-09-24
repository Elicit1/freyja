package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 单个 Segment 分镜生成结果与执行状态包装 (SegmentShotResult)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SegmentShotResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 分段 ID (如 SEG001) */
    private String segmentId;

    /** 分段序号 (1, 2, 3...) */
    private Integer sequence;

    /** 状态 (PENDING, RUNNING, SUCCESS, FAILED, RETRYING) */
    private String status;

    /** 当前重试次数 */
    @Builder.Default
    private Integer retryCount = 0;

    /** Worker AI 输出的结构化分镜结果 */
    private WorkerShotResult shotResult;

    /** 执行耗时 (毫秒) */
    private Long durationMs;

    /** 错误信息摘要 */
    private String errorMessage;
}
