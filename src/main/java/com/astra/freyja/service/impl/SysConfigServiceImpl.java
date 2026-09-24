package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.SysConfigMapper;
import com.astra.freyja.dto.config.SysConfigDTO;
import com.astra.freyja.dto.config.SysConfigQuery;
import com.astra.freyja.entity.SysConfig;
import com.astra.freyja.service.SysConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统参数配置服务实现类。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysConfigServiceImpl implements SysConfigService {

    private static final String CONFIG_CACHE_PREFIX = "freyja:config:";

    private final SysConfigMapper sysConfigMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public String getConfigValue(String configKey, String defaultValue) {
        if (StringUtils.isBlank(configKey)) {
            return defaultValue;
        }
        String cacheKey = CONFIG_CACHE_PREFIX + configKey.trim();
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return cached.toString();
            }
        } catch (Exception e) {
            log.warn("[SysConfig] 读取 Redis 缓存失败: {}", e.getMessage());
        }

        SysConfig entity = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, configKey.trim())
                .eq(SysConfig::getStatus, 1)
                .last("LIMIT 1"));

        if (entity == null || StringUtils.isBlank(entity.getConfigValue())) {
            return defaultValue;
        }

        try {
            redisTemplate.opsForValue().set(cacheKey, entity.getConfigValue());
        } catch (Exception e) {
            log.warn("[SysConfig] 写入 Redis 缓存失败: {}", e.getMessage());
        }

        return entity.getConfigValue();
    }

    @Override
    public Integer getConfigInt(String configKey, Integer defaultValue) {
        String val = getConfigValue(configKey, null);
        if (StringUtils.isBlank(val)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            log.warn("[SysConfig] 配置项 {} 值 [{}] 转为 Integer 失败，使用默认值: {}", configKey, val, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public Boolean getConfigBool(String configKey, Boolean defaultValue) {
        String val = getConfigValue(configKey, null);
        if (StringUtils.isBlank(val)) {
            return defaultValue;
        }
        String clean = val.trim().toLowerCase();
        if ("true".equals(clean) || "1".equals(clean) || "yes".equals(clean)) {
            return true;
        }
        if ("false".equals(clean) || "0".equals(clean) || "no".equals(clean)) {
            return false;
        }
        return defaultValue;
    }

    @Override
    public SysConfig getById(Long id) {
        if (id == null) {
            throw new BizException("配置 ID 不能为空");
        }
        SysConfig entity = sysConfigMapper.selectById(id);
        if (entity == null) {
            throw new BizException("系统配置不存在");
        }
        return entity;
    }

    @Override
    public SysConfig getByKey(String configKey) {
        if (StringUtils.isBlank(configKey)) {
            throw new BizException("配置键名不能为空");
        }
        SysConfig entity = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, configKey.trim())
                .last("LIMIT 1"));
        if (entity == null) {
            throw new BizException("系统配置不存在: " + configKey);
        }
        return entity;
    }

    @Override
    public Page<SysConfig> page(SysConfigQuery query) {
        if (query == null) {
            query = new SysConfigQuery();
        }
        LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<SysConfig>()
                .like(StringUtils.isNotBlank(query.getConfigName()), SysConfig::getConfigName, query.getConfigName())
                .like(StringUtils.isNotBlank(query.getConfigKey()), SysConfig::getConfigKey, query.getConfigKey())
                .eq(StringUtils.isNotBlank(query.getConfigType()), SysConfig::getConfigType, query.getConfigType())
                .eq(query.getIsBuiltin() != null, SysConfig::getIsBuiltin, query.getIsBuiltin())
                .eq(query.getStatus() != null, SysConfig::getStatus, query.getStatus())
                .orderByDesc(SysConfig::getIsBuiltin)
                .orderByAsc(SysConfig::getId);

        return sysConfigMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
    }

    @Override
    public void create(SysConfigDTO dto) {
        validate(dto);

        Long count = sysConfigMapper.selectCount(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, dto.getConfigKey().trim()));
        if (count != null && count > 0) {
            throw new BizException("配置键名已存在: " + dto.getConfigKey());
        }

        SysConfig entity = new SysConfig();
        entity.setConfigName(dto.getConfigName().trim());
        entity.setConfigKey(dto.getConfigKey().trim());
        entity.setConfigValue(dto.getConfigValue());
        entity.setConfigType(StringUtils.defaultIfBlank(dto.getConfigType(), "STRING"));
        entity.setIsBuiltin(dto.getIsBuiltin() != null ? dto.getIsBuiltin() : 0);
        entity.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);
        entity.setRemark(dto.getRemark());

        sysConfigMapper.insert(entity);
        evict(dto.getConfigKey());
    }

    @Override
    public void update(SysConfigDTO dto) {
        if (dto.getId() == null) {
            throw new BizException("主键 ID 不能为空");
        }
        validate(dto);

        SysConfig existing = getById(dto.getId());
        if (!existing.getConfigKey().equals(dto.getConfigKey().trim())) {
            Long count = sysConfigMapper.selectCount(new LambdaQueryWrapper<SysConfig>()
                    .eq(SysConfig::getConfigKey, dto.getConfigKey().trim())
                    .ne(SysConfig::getId, dto.getId()));
            if (count != null && count > 0) {
                throw new BizException("配置键名已存在: " + dto.getConfigKey());
            }
            evict(existing.getConfigKey());
        }

        existing.setConfigName(dto.getConfigName().trim());
        existing.setConfigKey(dto.getConfigKey().trim());
        existing.setConfigValue(dto.getConfigValue());
        existing.setConfigType(StringUtils.defaultIfBlank(dto.getConfigType(), "STRING"));
        if (dto.getIsBuiltin() != null) {
            existing.setIsBuiltin(dto.getIsBuiltin());
        }
        existing.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);
        existing.setRemark(dto.getRemark());

        sysConfigMapper.updateById(existing);
        evict(dto.getConfigKey());
    }

    @Override
    public void delete(Long id) {
        SysConfig existing = getById(id);
        if (existing.getIsBuiltin() != null && existing.getIsBuiltin() == 1) {
            throw new BizException("系统内置参数配置禁止删除");
        }
        sysConfigMapper.deleteById(id);
        evict(existing.getConfigKey());
    }

    @Override
    public void evict(String configKey) {
        if (StringUtils.isNotBlank(configKey)) {
            try {
                redisTemplate.delete(CONFIG_CACHE_PREFIX + configKey.trim());
            } catch (Exception e) {
                log.warn("[SysConfig] 清理 Redis 缓存失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public void refreshCache() {
        List<SysConfig> list = sysConfigMapper.selectList(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getStatus, 1));
        if (list != null) {
            for (SysConfig config : list) {
                try {
                    redisTemplate.opsForValue().set(CONFIG_CACHE_PREFIX + config.getConfigKey(), config.getConfigValue());
                } catch (Exception e) {
                    log.warn("[SysConfig] 刷新 Redis 缓存失败: {}", e.getMessage());
                }
            }
        }
    }

    private void validate(SysConfigDTO dto) {
        if (dto == null) {
            throw new BizException("配置信息不能为空");
        }
        if (StringUtils.isBlank(dto.getConfigName())) {
            throw new BizException("配置名称不能为空");
        }
        if (StringUtils.isBlank(dto.getConfigKey())) {
            throw new BizException("配置键名不能为空");
        }
        if (dto.getConfigValue() == null) {
            throw new BizException("配置键值不能为空");
        }
    }
}
