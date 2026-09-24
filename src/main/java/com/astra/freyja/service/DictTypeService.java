package com.astra.freyja.service;

import com.astra.freyja.dto.DictTypeDTO;
import com.astra.freyja.dto.DictTypeQuery;
import com.astra.freyja.entity.SysDictType;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 字典类型管理服务。
 */
public interface DictTypeService {

    Page<SysDictType> page(DictTypeQuery query);

    SysDictType getById(Long id);

    void create(DictTypeDTO dto);

    void update(DictTypeDTO dto);

    void delete(Long id);
}
