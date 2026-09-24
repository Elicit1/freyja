package com.astra.freyja.dto;

import lombok.Data;

/**
 * 字典类型新增/修改请求体。
 */
@Data
public class DictTypeDTO {

    /** 主键，修改时必传 */
    private Long id;

    /** 字典类型编码，唯一 */
    private String dictType;

    /** 字典类型名称 */
    private String dictName;

    /** 状态 0-停用 1-启用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
