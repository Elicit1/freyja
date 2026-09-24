package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 章节拆解任务历史记录 VO (ChapterDecomposeHistoryVO)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChapterDecomposeHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务 ID */
    private Long taskId;

    /** 归属短剧 ID */
    private Long dramaId;

    /** 归属短剧名称 */
    private String dramaTitle;

    /** 归属剧集 ID (可选) */
    private Long episodeId;

    /** 剧集编号 (如 1) */
    private Integer episodeNo;

    /** 章节/分集标题 */
    private String chapterTitle;

    /** 任务执行状态 (RUNNING, SUCCESS, PARTIAL_SUCCESS, FAILED) */
    private String status;

    /** 总分段数 (Planner 分段总数) */
    private Integer totalSegments;

    /** 成功分段数 */
    private Integer successSegments;

    /** 失败分段数 */
    private Integer failedSegments;

    /** 失败分段 ID 列表 (如 ["SEG002"]) */
    private List<String> failedSegmentIds;

    /** 产出镜头总数 */
    private Integer totalShots;

    /** 镜头平均时长 */
    private Double averageDuration;

    /** 消耗 Token 数 */
    private Integer consumedTokens;

    /** 执行 AI 模型代码 */
    private String modelCode;

    /** 错误摘要信息 */
    private String errorMessage;

    /** 耗时 (秒) */
    private Double durationSeconds;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /** 完成时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishedAt;
}
