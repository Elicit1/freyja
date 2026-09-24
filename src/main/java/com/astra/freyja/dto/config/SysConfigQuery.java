package com.astra.freyja.dto.config;

import lombok.Data;

import java.io.Serializable;

/**
 * 系统参数配置分页查询参数。
 */
@Data
public class SysConfigQuery implements Serializable {

    private Integer pageNum = 1;

    private Integer pageSize = 10;

    /** 配置名称 (模糊匹配) */
    private String configName;

    /** 配置键名 (模糊匹配) */
    private String configKey;

    /** 配置类型 (精确匹配) */
    private String configType;

    /** 是否内置 (0-否 1-是) */
    private Integer isBuiltin;

    /** 状态 (0-停用 1-启用) */
    private Integer status;
}
