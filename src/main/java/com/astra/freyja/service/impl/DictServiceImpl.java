package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.SysDictDataMapper;
import com.astra.freyja.dto.DictDataDTO;
import com.astra.freyja.dto.DictDataQuery;
import com.astra.freyja.entity.SysDictData;
import com.astra.freyja.service.DictService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DictServiceImpl implements DictService {

    private static final String DICT_CACHE_PREFIX = "freyja:dict:";

    private final SysDictDataMapper dictDataMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public List<SysDictData> listByType(String dictType) {
        String key = DICT_CACHE_PREFIX + dictType;
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return castList(cached);
        }
        List<SysDictData> list = dictDataMapper.selectList(new LambdaQueryWrapper<SysDictData>()
                .eq(SysDictData::getDictType, dictType)
                .eq(SysDictData::getStatus, 1)
                .orderByAsc(SysDictData::getSortOrder));
        if (list == null) {
            list = Collections.emptyList();
        }
        redisTemplate.opsForValue().set(key, list);
        return list;
    }

    @Override
    public Page<SysDictData> page(DictDataQuery query) {
        LambdaQueryWrapper<SysDictData> wrapper = new LambdaQueryWrapper<SysDictData>()
                .eq(StringUtils.isNotBlank(query.getDictType()), SysDictData::getDictType, query.getDictType())
                .like(StringUtils.isNotBlank(query.getDictLabel()), SysDictData::getDictLabel, query.getDictLabel())
                .eq(query.getStatus() != null, SysDictData::getStatus, query.getStatus())
                .orderByAsc(SysDictData::getDictType)
                .orderByAsc(SysDictData::getSortOrder);
        return dictDataMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
    }

    @Override
    public SysDictData getById(Long id) {
        SysDictData entity = dictDataMapper.selectById(id);
        if (entity == null) {
            throw new BizException("字典数据项不存在");
        }
        return entity;
    }

    @Override
    public void create(DictDataDTO dto) {
        validate(dto);
        Long count = dictDataMapper.selectCount(new LambdaQueryWrapper<SysDictData>()
                .eq(SysDictData::getDictType, dto.getDictType())
                .eq(SysDictData::getDictValue, dto.getDictValue()));
        if (count != null && count > 0) {
            throw new BizException("该类型下字典键值已存在");
        }
        SysDictData entity = new SysDictData();
        entity.setDictType(dto.getDictType());
        entity.setDictLabel(dto.getDictLabel());
        entity.setDictValue(dto.getDictValue());
        entity.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        entity.setStatus(dto.getStatus());
        entity.setRemark(dto.getRemark());
        dictDataMapper.insert(entity);
        evict(dto.getDictType());
    }

    @Override
    public void update(DictDataDTO dto) {
        if (dto.getId() == null) {
            throw new BizException("主键不能为空");
        }
        validate(dto);
        SysDictData existing = getById(dto.getId());
        if (!existing.getDictValue().equals(dto.getDictValue())
                || !existing.getDictType().equals(dto.getDictType())) {
            Long count = dictDataMapper.selectCount(new LambdaQueryWrapper<SysDictData>()
                    .eq(SysDictData::getDictType, dto.getDictType())
                    .eq(SysDictData::getDictValue, dto.getDictValue())
                    .ne(SysDictData::getId, dto.getId()));
            if (count != null && count > 0) {
                throw new BizException("该类型下字典键值已存在");
            }
        }
        existing.setDictType(dto.getDictType());
        existing.setDictLabel(dto.getDictLabel());
        existing.setDictValue(dto.getDictValue());
        existing.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        existing.setStatus(dto.getStatus());
        existing.setRemark(dto.getRemark());
        dictDataMapper.updateById(existing);
        evict(dto.getDictType());
    }

    @Override
    public void delete(Long id) {
        SysDictData existing = getById(id);
        dictDataMapper.deleteById(id);
        evict(existing.getDictType());
    }

    @Override
    public void evict(String dictType) {
        redisTemplate.delete(DICT_CACHE_PREFIX + dictType);
    }

    private void validate(DictDataDTO dto) {
        if (StringUtils.isBlank(dto.getDictType())) {
            throw new BizException("字典类型编码不能为空");
        }
        if (StringUtils.isBlank(dto.getDictLabel())) {
            throw new BizException("字典标签不能为空");
        }
        if (StringUtils.isBlank(dto.getDictValue())) {
            throw new BizException("字典键值不能为空");
        }
        if (dto.getStatus() == null) {
            throw new BizException("状态不能为空");
        }
    }

    @SuppressWarnings("unchecked")
    private List<SysDictData> castList(Object cached) {
        if (cached instanceof List<?>) {
            return (List<SysDictData>) cached;
        }
        throw new BizException("字典缓存类型异常");
    }
}