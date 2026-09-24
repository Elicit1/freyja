package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 核心道具资产表 res_prop。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_prop")
public class ResProp extends BaseEntity {

    /** 归属短剧ID，0为公共资源库 */
    private Long dramaId;

    /** 道具中文名称 (如: 繁复小座钟、黑色手提箱) */
    private String name;

    /** 道具类型: KEY_PROP(核心叙事道具)/WEAPON(武器)/COSTUME_ACCESSORY(服饰配饰)/DAILY(日常杂物) */
    private String propType;

    /** 道具中文背景与特征描述 */
    private String description;

    /** 英文生图/参考图Prompt (如: ornate antique brass desk clock with intricate engravings) */
    private String propPrompt;

    /** 道具参考图/设计图URL */
    private String coverUrl;

    /** 显示排序 */
    private Integer sortOrder;

    /** 状态 0-停用 1-启用 */
    private Integer status;
}
