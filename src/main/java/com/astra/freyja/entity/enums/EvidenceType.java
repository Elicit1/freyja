package com.astra.freyja.entity.enums;

import lombok.Getter;

/**
 * 角色身份依据/证据类型枚举。
 */
@Getter
public enum EvidenceType {

    /** 首次出场提及 */
    FIRST_APPEARANCE("首次出场"),

    /** 明确自我介绍 (如 “我叫林婉清”) */
    SELF_INTRODUCTION("自我介绍"),

    /** 剧本明确指名 (如 “林婉清走进房间”) */
    EXPLICIT_NAME("剧本指名"),

    /** 别名与历史称谓匹配 */
    ALIAS_MATCH("别名匹配"),

    /** 他人明确称呼 (如 “林小姐，你来了”) */
    EXPLICIT_REFERENCE("他人称呼"),

    /** 人物剧情关系连续性 (如 救助的小女孩/亲属关系) */
    RELATIONSHIP("剧情关系"),

    /** 外貌视觉特征匹配 (如 泪痣/银发/伤疤) */
    APPEARANCE_MATCH("外貌特征"),

    /** 上下文语义推理 */
    CONTEXT_MATCH("上下文推理"),

    /** 场景内剧情连续性 (前句“女人进门”，后句“她坐下”) */
    SCENE_CONTINUITY("场景连续性"),

    /** 其他证据 */
    OTHER("其他");

    private final String description;

    EvidenceType(String description) {
        this.description = description;
    }
}
