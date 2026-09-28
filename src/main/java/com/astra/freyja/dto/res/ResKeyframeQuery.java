package com.astra.freyja.dto.res;

import lombok.Data;

/**
 * 关键帧资产分页检索入参 Query。
 */
@Data
public class ResKeyframeQuery {

    /** 当前页 */
    private Integer current = 1;

    /** 每页条数 */
    private Integer size = 10;

    /** 归属短剧ID (传0为公共资源库，不传查全部) */
    private Long dramaId;

    /** 关联分镜镜头ID */
    private Long shotId;

    /** 关键帧名称模糊搜索 */
    private String name;

    /** 关键帧类型 */
    private String frameType;

    /** 状态 0-停用 1-启用 */
    private Integer status;

    /** 来源类型 */
    private String sourceType;
}
