package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * AI 流水线任务实体 ai_task。
 * 记录多阶段拆解过程中各细分任务的执行状态、输入输出、Token 预算、重试计数及耗时。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_task")
public class AiTask extends BaseEntity {

    /** 归属短剧ID */
    private Long dramaId;

    /** 归属剧集ID (可选) */
    private Long episodeId;

    /** 任务类型 (SCRIPT_CHUNK, PLOT_EXTRACTION, SHOT_GROUP_EXTRACTION, SHOT_DECOMPOSE, SHOT_ENRICHMENT, CONTINUITY_CHECK, ENTITY_RESOLUTION) */
    private String taskType;

    /** 任务状态 (PENDING, RUNNING, SUCCESS, FAILED, RETRYING, CANCELLED) */
    private String status;

    /** 关联目标业务ID (如 chunkId, plotId, shotGroupId, shotId) */
    private String targetId;

    /** 父任务 ID (支持树形任务分治链路) */
    private Long parentTaskId;

    /** 输入数据载荷 (JSON / 文本片段) */
    private String inputPayload;

    /** 输出数据载荷 (结构化 JSON) */
    private String outputPayload;

    /** 调用模型代码 (如 deepseek-chat, gpt-4o) */
    private String modelCode;

    /** 最大 Token 限制 */
    private Integer maxTokens;

    /** 消耗 Token 数 */
    private Integer consumedTokens;

    /** 重试次数 */
    private Integer retryCount;

    /** 错误信息摘要 */
    private String errorMessage;

    /** 任务开始时间 */
    private LocalDateTime startedAt;

    /** 任务结束时间 */
    private LocalDateTime finishedAt;
}
