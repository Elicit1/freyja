package com.astra.freyja.service;

import com.astra.freyja.dto.config.SysConfigDTO;
import com.astra.freyja.dto.config.SysConfigQuery;
import com.astra.freyja.entity.SysConfig;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * 系统参数配置服务接口。
 */
public interface SysConfigService {

    /**
     * 根据配置键名获取配置值（支持默认值，自动走 Redis 缓存）。
     *
     * @param configKey    配置键名
     * @param defaultValue 默认值
     * @return 配置值
     */
    String getConfigValue(String configKey, String defaultValue);

    /**
     * 根据配置键名获取 Integer 类型配置值。
     *
     * @param configKey    配置键名
     * @param defaultValue 默认值
     * @return Integer 配置值
     */
    Integer getConfigInt(String configKey, Integer defaultValue);

    /**
     * 根据配置键名获取 Boolean 类型配置值。
     *
     * @param configKey    配置键名
     * @param defaultValue 默认值
     * @return Boolean 配置值
     */
    Boolean getConfigBool(String configKey, Boolean defaultValue);

    /**
     * 根据 ID 获取配置详情。
     */
    SysConfig getById(Long id);

    /**
     * 根据 Key 获取配置实体。
     */
    SysConfig getByKey(String configKey);

    /**
     * 分页查询配置列表。
     */
    Page<SysConfig> page(SysConfigQuery query);

    /**
     * 新增系统配置。
     */
    void create(SysConfigDTO dto);

    /**
     * 修改系统配置。
     */
    void update(SysConfigDTO dto);

    /**
     * 删除系统配置（系统内置配置禁止删除）。
     */
    void delete(Long id);

    /**
     * 清理指定键名的 Redis 缓存。
     */
    void evict(String configKey);

    /**
     * 刷新并预热全部配置缓存。
     */
    void refreshCache();
}
