package com.astra.freyja.dto.res;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 道具资产详情与分页列表响应 VO。
 */
@Data
public class ResPropVO {

    /** 主键ID */
    private Long id;

    /** 归属短剧ID，0为公共资源库 */
    private Long dramaId;

    /** 道具中文名称 */
    private String name;

    /** 道具类型: KEY_PROP(核心叙事道具)/WEAPON(武器)/COSTUME_ACCESSORY(服饰配饰)/DAILY(日常杂物) */
    private String propType;

    /** 道具中文背景与特征描述 */
    private String description;

    /** 英文生图/分镜组装 Prompt */
    private String propPrompt;

    /** 道具参考图/设计图URL */
    private String coverUrl;

    /** 显示排序 */
    private Integer sortOrder;

    /** 状态 0-停用 1-启用 */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
