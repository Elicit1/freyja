package com.astra.freyja.dto.config;

import lombok.Data;

import java.io.Serializable;

/**
 * 系统参数配置创建/修改 DTO。
 */
@Data
public class SysConfigDTO implements Serializable {

    private Long id;

    /** 配置名称 */
    private String configName;

    /** 配置键名 (唯一) */
    private String configKey;

    /** 配置键值 */
    private String configValue;

    /** 配置类型 (STRING/TEXT/JSON/NUMBER/BOOLEAN) */
    private String configType;

    /** 是否系统内置 0-否 1-是 */
    private Integer isBuiltin;

    /** 状态 0-停用 1-启用 */
    private Integer status;

    /** 备注说明 */
    private String remark;
}
