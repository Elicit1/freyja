package com.astra.freyja.dto.drama;

import lombok.Data;

/**
 * 镜头组新增/修改请求 DTO。
 */
@Data
public class DramaShotGroupDTO {

    private Long id;

    /** 归属短剧 ID */
    private Long dramaId;

    /** 归属剧集 ID */
    private Long episodeId;

    /** 归属场次 ID */
    private Long sceneId;

    /** 镜头组序号 */
    private Integer groupNo;

    /** 镜头组名称/连续动作概述 */
    private String name;

    /** 导演意图/叙事目的 */
    private String purpose;

    /** 显示排序 */
    private Integer sortOrder;

    /** 备注 */
    private String remark;
}
