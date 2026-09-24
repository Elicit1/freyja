package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.SysDictTypeMapper;
import com.astra.freyja.dto.DictTypeDTO;
import com.astra.freyja.dto.DictTypeQuery;
import com.astra.freyja.entity.SysDictType;
import com.astra.freyja.service.DictTypeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 字典类型管理实现。
 */
@Service
@RequiredArgsConstructor
public class DictTypeServiceImpl implements DictTypeService {

    private final SysDictTypeMapper dictTypeMapper;

    @Override
    public Page<SysDictType> page(DictTypeQuery query) {
        LambdaQueryWrapper<SysDictType> wrapper = new LambdaQueryWrapper<SysDictType>()
                .like(StringUtils.isNotBlank(query.getDictType()), SysDictType::getDictType, query.getDictType())
                .like(StringUtils.isNotBlank(query.getDictName()), SysDictType::getDictName, query.getDictName())
                .eq(query.getStatus() != null, SysDictType::getStatus, query.getStatus())
                .orderByDesc(SysDictType::getCreateTime);
        return dictTypeMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
    }

    @Override
    public SysDictType getById(Long id) {
        SysDictType entity = dictTypeMapper.selectById(id);
        if (entity == null) {
            throw new BizException("字典类型不存在");
        }
        return entity;
    }

    @Override
    public void create(DictTypeDTO dto) {
        if (StringUtils.isBlank(dto.getDictType())) {
            throw new BizException("字典类型编码不能为空");
        }
        if (StringUtils.isBlank(dto.getDictName())) {
            throw new BizException("字典类型名称不能为空");
        }
        if (dto.getStatus() == null) {
            throw new BizException("状态不能为空");
        }
        Long count = dictTypeMapper.selectCount(new LambdaQueryWrapper<SysDictType>()
                .eq(SysDictType::getDictType, dto.getDictType()));
        if (count != null && count > 0) {
            throw new BizException("字典类型编码已存在");
        }
        SysDictType entity = new SysDictType();
        entity.setDictType(dto.getDictType());
        entity.setDictName(dto.getDictName());
        entity.setStatus(dto.getStatus());
        entity.setRemark(dto.getRemark());
        dictTypeMapper.insert(entity);
    }

    @Override
    public void update(DictTypeDTO dto) {
        if (dto.getId() == null) {
            throw new BizException("主键不能为空");
        }
        SysDictType existing = getById(dto.getId());
        if (StringUtils.isBlank(dto.getDictType())) {
            throw new BizException("字典类型编码不能为空");
        }
        if (StringUtils.isBlank(dto.getDictName())) {
            throw new BizException("字典类型名称不能为空");
        }
        if (dto.getStatus() == null) {
            throw new BizException("状态不能为空");
        }
        if (!existing.getDictType().equals(dto.getDictType())) {
            Long count = dictTypeMapper.selectCount(new LambdaQueryWrapper<SysDictType>()
                    .eq(SysDictType::getDictType, dto.getDictType())
                    .ne(SysDictType::getId, dto.getId()));
            if (count != null && count > 0) {
                throw new BizException("字典类型编码已存在");
            }
        }
        existing.setDictType(dto.getDictType());
        existing.setDictName(dto.getDictName());
        existing.setStatus(dto.getStatus());
        existing.setRemark(dto.getRemark());
        dictTypeMapper.updateById(existing);
    }

    @Override
    public void delete(Long id) {
        dictTypeMapper.deleteById(id);
    }
}
