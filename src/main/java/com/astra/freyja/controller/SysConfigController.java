package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.config.SysConfigDTO;
import com.astra.freyja.dto.config.SysConfigQuery;
import com.astra.freyja.entity.SysConfig;
import com.astra.freyja.service.SysConfigService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 系统参数配置 Controller。
 */
@RestController
@RequestMapping("/system/config")
@RequiredArgsConstructor
public class SysConfigController {

    private final SysConfigService sysConfigService;

    /**
     * 分页查询配置列表。
     */
    @GetMapping("/page")
    public R<Page<SysConfig>> page(SysConfigQuery query) {
        return R.ok(sysConfigService.page(query));
    }

    /**
     * 根据 ID 获取配置详情。
     */
    @GetMapping("/{id}")
    public R<SysConfig> getById(@PathVariable Long id) {
        return R.ok(sysConfigService.getById(id));
    }

    /**
     * 根据 Key 获取配置实体。
     */
    @GetMapping("/key/{configKey}")
    public R<SysConfig> getByKey(@PathVariable String configKey) {
        return R.ok(sysConfigService.getByKey(configKey));
    }

    /**
     * 根据 Key 获取配置键值字符串。
     */
    @GetMapping("/value/{configKey}")
    public R<String> getValueByKey(@PathVariable String configKey,
                                   @RequestParam(required = false) String defaultValue) {
        return R.ok(sysConfigService.getConfigValue(configKey, defaultValue));
    }

    /**
     * 新增配置。
     */
    @PostMapping
    public R<Void> create(@RequestBody SysConfigDTO dto) {
        sysConfigService.create(dto);
        return R.ok();
    }

    /**
     * 修改配置。
     */
    @PutMapping
    public R<Void> update(@RequestBody SysConfigDTO dto) {
        sysConfigService.update(dto);
        return R.ok();
    }

    /**
     * 删除配置（系统内置配置禁止删除）。
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        sysConfigService.delete(id);
        return R.ok();
    }

    /**
     * 刷新并预热全部配置缓存。
     */
    @PostMapping("/refresh-cache")
    public R<Void> refreshCache() {
        sysConfigService.refreshCache();
        return R.ok();
    }
}
