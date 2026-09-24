package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 章节解析异步任务状态模型 (ChapterParseTaskVO)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChapterParseTaskVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务 ID */
    private String taskId;

    /** 短剧 ID */
    private Long dramaId;

    /** 全局任务状态 (PLANNING, GENERATING_SHOTS, MERGING, VALIDATING, COMPLETED, FAILED) */
    private String status;

    /** Planner 状态 (PENDING, RUNNING, SUCCESS, FAILED) */
    private String plannerStatus;

    /** 规划出的 Segment 总数 */
    @Builder.Default
    private Integer segmentCount = 0;

    /** 已完成的 Segment 数量 */
    @Builder.Default
    private Integer completedSegments = 0;

    /** 失败的 Segment 数量 */
    @Builder.Default
    private Integer failedSegments = 0;

    /** Merge 状态 (PENDING, RUNNING, SUCCESS, FAILED) */
    private String mergeStatus;

    /** 各 Segment 独立运行进度列表 */
    @Builder.Default
    private List<SegmentShotResult> segmentProgressList = new ArrayList<>();

    /** 镜头碎片率统计指标 */
    private FragmentationStatsVO fragmentationStats;

    /** 最终结构化解析结果 (完成时提供) */
    private ScriptDecomposeResultVO finalResult;

    /** 错误信息 */
    private String errorMessage;

    /** 任务开始时间 */
    private LocalDateTime startedAt;

    /** 任务完成时间 */
    private LocalDateTime finishedAt;
}
