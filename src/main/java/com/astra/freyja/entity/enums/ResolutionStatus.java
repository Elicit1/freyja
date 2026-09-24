package com.astra.freyja.entity.enums;

import lombok.Getter;

/**
 * 角色消歧状态枚举。
 */
@Getter
public enum ResolutionStatus {

    /** 未决待确认（存在歧义候选，需后续剧情或人工确认） */
    UNRESOLVED("待消歧决议"),

    /** 已决议（已明确绑定或合并至特定角色） */
    RESOLVED("已决议绑定"),

    /** 确认是全新角色 */
    NEW_CHARACTER("全新角色"),

    /** 已忽略（确认为独立新角色或忽略） */
    IGNORED("已忽略");

    private final String description;

    ResolutionStatus(String description) {
        this.description = description;
    }
}
