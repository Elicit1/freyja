package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.ResKeyframeMapper;
import com.astra.freyja.dto.res.ResKeyframeDTO;
import com.astra.freyja.dto.res.ResKeyframeOptionVO;
import com.astra.freyja.dto.res.ResKeyframeQuery;
import com.astra.freyja.dto.res.ResKeyframeVO;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.ResKeyframe;
import com.astra.freyja.service.ResKeyframeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 关键帧资产管理服务实现类。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResKeyframeServiceImpl implements ResKeyframeService {

    private final ResKeyframeMapper resKeyframeMapper;
    private final DramaMapper dramaMapper;
    private final DramaShotMapper dramaShotMapper;

    @Override
    public Page<ResKeyframeVO> page(ResKeyframeQuery query) {
        Page<ResKeyframe> pageParam = new Page<>(
                query.getCurrent() != null ? query.getCurrent() : 1,
                query.getSize() != null ? query.getSize() : 10
        );

        LambdaQueryWrapper<ResKeyframe> wrapper = new LambdaQueryWrapper<ResKeyframe>()
                .eq(query.getDramaId() != null, ResKeyframe::getDramaId, query.getDramaId())
                .eq(query.getShotId() != null, ResKeyframe::getShotId, query.getShotId())
                .like(StringUtils.isNotBlank(query.getName()), ResKeyframe::getName, query.getName())
                .eq(StringUtils.isNotBlank(query.getFrameType()), ResKeyframe::getFrameType, query.getFrameType())
                .eq(StringUtils.isNotBlank(query.getSourceType()), ResKeyframe::getSourceType, query.getSourceType())
                .eq(query.getStatus() != null, ResKeyframe::getStatus, query.getStatus())
                .orderByAsc(ResKeyframe::getSortOrder)
                .orderByDesc(ResKeyframe::getId);

        Page<ResKeyframe> entityPage = resKeyframeMapper.selectPage(pageParam, wrapper);

        Page<ResKeyframeVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        if (entityPage.getRecords().isEmpty()) {
            voPage.setRecords(Collections.emptyList());
            return voPage;
        }

        // 收集所有关联的 dramaId 与 shotId 批量回填
        Set<Long> dramaIds = entityPage.getRecords().stream()
                .map(ResKeyframe::getDramaId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());

        Set<Long> shotIds = entityPage.getRecords().stream()
                .map(ResKeyframe::getShotId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> dramaTitleMap = dramaIds.isEmpty() ? Collections.emptyMap() :
                dramaMapper.selectBatchIds(dramaIds).stream()
                        .collect(Collectors.toMap(Drama::getId, Drama::getTitle, (a, b) -> a));

        Map<Long, DramaShot> shotMap = shotIds.isEmpty() ? Collections.emptyMap() :
                dramaShotMapper.selectBatchIds(shotIds).stream()
                        .collect(Collectors.toMap(DramaShot::getId, s -> s, (a, b) -> a));

        List<ResKeyframeVO> voList = entityPage.getRecords().stream().map(entity -> {
            ResKeyframeVO vo = toVO(entity);
            if (entity.getDramaId() != null && entity.getDramaId() > 0) {
                vo.setDramaTitle(dramaTitleMap.getOrDefault(entity.getDramaId(), "未知短剧"));
            } else {
                vo.setDramaTitle("全局公共库");
            }

            if (entity.getShotId() != null && shotMap.containsKey(entity.getShotId())) {
                DramaShot shot = shotMap.get(entity.getShotId());
                vo.setShotName(shot.getShotName());
                vo.setShotNo(shot.getShotNo());
            }
            return vo;
        }).collect(Collectors.toList());

        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public ResKeyframeVO getById(Long id) {
        ResKeyframe entity = resKeyframeMapper.selectById(id);
        if (entity == null) {
            throw new BizException("关键帧资产不存在");
        }
        ResKeyframeVO vo = toVO(entity);
        if (entity.getDramaId() != null && entity.getDramaId() > 0) {
            Drama drama = dramaMapper.selectById(entity.getDramaId());
            vo.setDramaTitle(drama != null ? drama.getTitle() : "未知短剧");
        } else {
            vo.setDramaTitle("全局公共库");
        }

        if (entity.getShotId() != null) {
            DramaShot shot = dramaShotMapper.selectById(entity.getShotId());
            if (shot != null) {
                vo.setShotName(shot.getShotName());
                vo.setShotNo(shot.getShotNo());
            }
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ResKeyframeDTO dto) {
        if (StringUtils.isBlank(dto.getName())) {
            throw new BizException("关键帧名称不能为空");
        }
        if (StringUtils.isBlank(dto.getFrameUrl())) {
            throw new BizException("关键帧图片URL不能为空");
        }

        Long dramaId = dto.getDramaId() != null ? dto.getDramaId() : 0L;
        Long shotId = dto.getShotId();

        // 校验关联分镜
        if (shotId != null && shotId > 0) {
            DramaShot shot = dramaShotMapper.selectById(shotId);
            if (shot == null) {
                throw new BizException("关联的分镜镜头不存在");
            }
            // 若分镜隶属于某短剧，自动校准一致性
            if (shot.getDramaId() != null && shot.getDramaId() > 0) {
                dramaId = shot.getDramaId();
            }
        }

        ResKeyframe entity = new ResKeyframe();
        BeanUtils.copyProperties(dto, entity);
        entity.setId(null);
        entity.setDramaId(dramaId);
        entity.setShotId(shotId);
        if (StringUtils.isBlank(entity.getFrameType())) {
            entity.setFrameType("KEYFRAME");
        }
        if (StringUtils.isBlank(entity.getSourceType())) {
            entity.setSourceType("MANUAL_UPLOAD");
        }
        if (entity.getSortOrder() == null) {
            entity.setSortOrder(0);
        }
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }

        resKeyframeMapper.insert(entity);
        log.info("[ResKeyframeService] 成功创建关键帧: id={}, name={}, dramaId={}, shotId={}",
                entity.getId(), entity.getName(), dramaId, shotId);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(ResKeyframeDTO dto) {
        if (dto.getId() == null) {
            throw new BizException("关键帧ID不能为空");
        }
        ResKeyframe existing = resKeyframeMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BizException("关键帧资产不存在");
        }
        if (StringUtils.isBlank(dto.getName())) {
            throw new BizException("关键帧名称不能为空");
        }
        if (StringUtils.isBlank(dto.getFrameUrl())) {
            throw new BizException("关键帧图片URL不能为空");
        }

        Long dramaId = dto.getDramaId() != null ? dto.getDramaId() : 0L;
        Long shotId = dto.getShotId();

        if (shotId != null && shotId > 0) {
            DramaShot shot = dramaShotMapper.selectById(shotId);
            if (shot == null) {
                throw new BizException("关联的分镜镜头不存在");
            }
            if (shot.getDramaId() != null && shot.getDramaId() > 0) {
                dramaId = shot.getDramaId();
            }
        }

        BeanUtils.copyProperties(dto, existing);
        existing.setDramaId(dramaId);
        existing.setShotId(shotId);

        resKeyframeMapper.updateById(existing);
        log.info("[ResKeyframeService] 成功更新关键帧: id={}, name={}", existing.getId(), existing.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ResKeyframe existing = resKeyframeMapper.selectById(id);
        if (existing == null) {
            throw new BizException("关键帧资产不存在");
        }
        resKeyframeMapper.deleteById(id);
        log.info("[ResKeyframeService] 成功逻辑删除关键帧: id={}, name={}", id, existing.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException("状态值不合法");
        }
        ResKeyframe existing = resKeyframeMapper.selectById(id);
        if (existing == null) {
            throw new BizException("关键帧资产不存在");
        }
        existing.setStatus(status);
        resKeyframeMapper.updateById(existing);
        log.info("[ResKeyframeService] 成功修改关键帧状态: id={}, status={}", id, status);
    }

    @Override
    public List<ResKeyframeOptionVO> options(Long dramaId, Long shotId) {
        LambdaQueryWrapper<ResKeyframe> wrapper = new LambdaQueryWrapper<ResKeyframe>()
                .eq(ResKeyframe::getStatus, 1);

        if (dramaId != null) {
            if (dramaId == 0) {
                wrapper.eq(ResKeyframe::getDramaId, 0L);
            } else {
                // 允许查指定剧目或全局公共库
                wrapper.and(w -> w.eq(ResKeyframe::getDramaId, dramaId).or().eq(ResKeyframe::getDramaId, 0L));
            }
        }
        if (shotId != null && shotId > 0) {
            wrapper.eq(ResKeyframe::getShotId, shotId);
        }

        wrapper.orderByAsc(ResKeyframe::getSortOrder).orderByDesc(ResKeyframe::getId);

        List<ResKeyframe> list = resKeyframeMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return Collections.emptyList();
        }

        return list.stream().map(entity -> {
            ResKeyframeOptionVO vo = new ResKeyframeOptionVO();
            vo.setId(entity.getId());
            vo.setName(entity.getName());
            vo.setFrameType(entity.getFrameType());
            vo.setFrameUrl(entity.getFrameUrl());
            vo.setDramaId(entity.getDramaId());
            vo.setShotId(entity.getShotId());
            vo.setPrompt(entity.getPrompt());
            return vo;
        }).collect(Collectors.toList());
    }

    private ResKeyframeVO toVO(ResKeyframe entity) {
        ResKeyframeVO vo = new ResKeyframeVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
