package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 角色别名与提及映射表 res_character_alias。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_character_alias")
public class ResCharacterAlias extends BaseEntity {

    /** 归属短剧ID */
    private Long dramaId;

    /** 归属角色ID */
    private Long characterId;

    /** 别名/提及称谓 (如: 女人, 她, 林小姐, 林总) */
    private String alias;

    /** 别名类型: NAME, PRONOUN, DESCRIPTION, TITLE, NICKNAME, ROLE, OTHER */
    private String aliasType;

    /** 首次识别到的剧集ID */
    private Long sourceEpisodeId;

    /** 置信度 0.00 ~ 1.00 */
    private BigDecimal confidence;

    /** 状态 0-停用 1-启用 */
    private Integer status;
}
