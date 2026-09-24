package com.astra.freyja.entity.enums;

import lombok.Getter;

/**
 * 角色身份状态枚举。
 */
@Getter
public enum IdentityStatus {

    /** 未知身份，刚发现人物，信息极少 */
    UNKNOWN("未知身份"),

    /** 临时称谓/局部信息（已确定是独立人物，但尚未知正式姓名，如“年轻女人”） */
    PARTIAL("临时称谓未定名"),

    /** 已确认正式身份（剧本中已明确出现正式姓名） */
    CONFIRMED("已确认正式名"),

    /** 存在歧义未决项（无法确定是否与已有角色为同一人） */
    UNRESOLVED("待消歧决议"),

    /** 已合并至主角色（保留历史追溯） */
    MERGED("已合并");

    private final String description;

    IdentityStatus(String description) {
        this.description = description;
    }
}
