package com.astra.freyja.dto;

import lombok.Data;

/**
 * 字典数据项新增/修改请求体。
 */
@Data
public class DictDataDTO {

    /** 主键，修改时必传 */
    private Long id;

    /** 归属字典类型编码 */
    private String dictType;

    /** 字典标签（展示用） */
    private String dictLabel;

    /** 字典键值（存储用） */
    private String dictValue;

    /** 显示排序 */
    private Integer sortOrder;

    /** 状态 0-停用 1-启用 */
    private Integer status;

    /** 备注 */
    private String remark;
}

