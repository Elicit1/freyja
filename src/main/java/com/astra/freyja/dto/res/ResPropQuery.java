package com.astra.freyja.dto.res;

import lombok.Data;

/**
 * 道具资产分页查询参数。
 */
@Data
public class ResPropQuery {

    /** 当前页，默认 1 */
    private Integer current = 1;

    /** 每页大小，默认 10 */
    private Integer size = 10;

    /** 归属短剧ID，0或null为全部公共库/不限 */
    private Long dramaId;

    /** 道具中文名称模糊匹配 */
    private String name;

    /** 道具类型 KEY_PROP/WEAPON/COSTUME_ACCESSORY/DAILY */
    private String propType;

    /** 状态 0-停用 1-启用 */
    private Integer status;
}
