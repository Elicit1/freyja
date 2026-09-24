package com.astra.freyja.entity.enums;

import lombok.Getter;

/**
 * 角色别名/称谓类型枚举。
 */
@Getter
public enum AliasType {

    /** 正式名或化名 (如: 林婉清, 苏晚) */
    NAME("正式名/化名"),

    /** 代词 (如: 她, 他) */
    PRONOUN("代词"),

    /** 外貌特征描述 (如: 年轻女人, 黑衣人, 银发少女) */
    DESCRIPTION("外貌描述"),

    /** 头衔/尊称 (如: 林小姐, 王总, 夫人) */
    TITLE("头衔/尊称"),

    /** 昵称/小名 (如: 婉清, 小林) */
    NICKNAME("昵称"),

    /** 职业/社会身份 (如: 医生, 警察, 律师) */
    ROLE("职业身份"),

    /** 其他 */
    OTHER("其他");

    private final String description;

    AliasType(String description) {
        this.description = description;
    }
}
