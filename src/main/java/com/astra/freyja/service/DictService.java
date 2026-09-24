package com.astra.freyja.service;

import com.astra.freyja.dto.DictDataDTO;
import com.astra.freyja.dto.DictDataQuery;
import com.astra.freyja.entity.SysDictData;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

public interface DictService {

    /**
     * 按字典类型查询启用的数据项列表，优先读 Redis 缓存，未命中则查库并回填。
     */
    List<SysDictData> listByType(String dictType);

    /**
     * 字典数据项分页查询。
     */
    Page<SysDictData> page(DictDataQuery query);

    /**
     * 字典数据项详情。
     */
    SysDictData getById(Long id);

    /**
     * 新增字典数据项。
     */
    void create(DictDataDTO dto);

    /**
     * 修改字典数据项。
     */
    void update(DictDataDTO dto);

    /**
     * 删除字典数据项。
     */
    void delete(Long id);

    /**
     * 清除指定字典类型的缓存。
     */
    void evict(String dictType);
}