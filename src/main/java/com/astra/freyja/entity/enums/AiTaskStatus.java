package com.astra.freyja.entity.enums;

/**
 * AI 任务执行状态枚举。
 */
public enum AiTaskStatus {
    PENDING("排队中"),
    RUNNING("执行中"),
    SUCCESS("已成功"),
    PARTIAL_SUCCESS("部分成功(待补救)"),
    FAILED("已失败"),
    RETRYING("重试中"),
    CANCELLED("已取消");

    private final String description;

    AiTaskStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
