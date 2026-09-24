package com.astra.freyja.entity.enums;

import lombok.Getter;

/**
 * ComfyUI 渲染任务状态枚举。
 */
@Getter
public enum ComfyTaskStatus {

    /** 等待调度/下发 */
    PENDING("PENDING", "等待排队"),

    /** 正在渲染执行中 */
    RUNNING("RUNNING", "执行中"),

    /** 归档落库成功 */
    SUCCESS("SUCCESS", "完成"),

    /** 渲染或归档失败 */
    FAILED("FAILED", "失败"),

    /** 任务已取消 */
    CANCELED("CANCELED", "已取消");

    private final String code;
    private final String description;

    ComfyTaskStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
