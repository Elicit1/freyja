package com.astra.freyja.dto;

import lombok.Data;

/**
 * 字典数据项分页查询参数。
 */
@Data
public class DictDataQuery {

    /** 页码，从 1 开始 */
    private Integer pageNum = 1;

    /** 每页条数 */
    private Integer pageSize = 10;

    /** 归属字典类型编码（等值） */
    private String dictType;

    /** 字典标签（模糊） */
    private String dictLabel;

    /** 状态 0-停用 1-启用 */
    private Integer status;
}
