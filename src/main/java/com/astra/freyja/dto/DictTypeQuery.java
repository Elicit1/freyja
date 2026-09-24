package com.astra.freyja.dto;

import lombok.Data;

/**
 * 字典类型分页查询参数。
 */
@Data
public class DictTypeQuery {

    /** 页码，从 1 开始 */
    private Integer pageNum = 1;

    /** 每页条数 */
    private Integer pageSize = 10;

    /** 字典类型编码（模糊） */
    private String dictType;

    /** 字典类型名称（模糊） */
    private String dictName;

    /** 状态 0-停用 1-启用 */
    private Integer status;
}
