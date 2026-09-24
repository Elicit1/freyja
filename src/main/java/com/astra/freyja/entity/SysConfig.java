package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统参数配置实体类。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_config")
public class SysConfig extends BaseEntity {

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
}
