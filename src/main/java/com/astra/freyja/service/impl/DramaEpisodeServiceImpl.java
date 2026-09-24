package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaEpisodeMapper;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.DramaSceneMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dto.drama.DramaEpisodeBatchDTO;
import com.astra.freyja.dto.drama.DramaEpisodeDTO;
import com.astra.freyja.dto.drama.DramaEpisodeVO;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.entity.DramaEpisode;
import com.astra.freyja.entity.DramaScene;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.service.DramaEpisodeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 剧集管理服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DramaEpisodeServiceImpl implements DramaEpisodeService {

    private final DramaMapper dramaMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaSceneMapper sceneMapper;
    private final DramaShotMapper shotMapper;

    @Override
    public List<DramaEpisodeVO> listByDramaId(Long dramaId) {
        if (dramaId == null) {
            return Collections.emptyList();
        }
        List<DramaEpisode> list = episodeMapper.selectList(
                new LambdaQueryWrapper<DramaEpisode>()
                        .eq(DramaEpisode::getDramaId, dramaId)
                        .orderByAsc(DramaEpisode::getEpisodeNo)
                        .orderByAsc(DramaEpisode::getSortOrder)
        );

        return list.stream().map(this::enrichEpisodeVO).collect(Collectors.toList());
    }

    @Override
    public DramaEpisodeVO getById(Long id) {
        DramaEpisode episode = episodeMapper.selectById(id);
        if (episode == null) {
            throw new BizException(404, "剧集不存在: " + id);
        }
        return enrichEpisodeVO(episode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(DramaEpisodeDTO dto) {
        if (dto == null || dto.getDramaId() == null) {
            throw new BizException(400, "归属短剧 ID 不能为空");
        }
        Drama drama = dramaMapper.selectById(dto.getDramaId());
        if (drama == null) {
            throw new BizException(404, "归属短剧不存在: " + dto.getDramaId());
        }

        DramaEpisode episode = new DramaEpisode();
        BeanUtils.copyProperties(dto, episode);

        if (episode.getEpisodeNo() == null || episode.getEpisodeNo() <= 0) {
            // 自动计算下一集集号
            List<DramaEpisode> existing = episodeMapper.selectList(
                    new LambdaQueryWrapper<DramaEpisode>()
                            .eq(DramaEpisode::getDramaId, dto.getDramaId())
                            .orderByDesc(DramaEpisode::getEpisodeNo)
            );
            int nextNo = existing.isEmpty() ? 1 : existing.get(0).getEpisodeNo() + 1;
            episode.setEpisodeNo(nextNo);
        }

        if (StringUtils.isBlank(episode.getTitle())) {
            episode.setTitle("第" + episode.getEpisodeNo() + "集");
        }
        if (episode.getTargetDuration() == null || episode.getTargetDuration() <= 0) {
            episode.setTargetDuration(300);
        }
        if (episode.getActualDuration() == null) {
            episode.setActualDuration(BigDecimal.ZERO);
        }
        if (StringUtils.isBlank(episode.getStatus())) {
            episode.setStatus("DRAFT");
        }
        if (episode.getSortOrder() == null) {
            episode.setSortOrder(episode.getEpisodeNo());
        }

        episodeMapper.insert(episode);
        log.info("[DramaEpisodeService] 创建剧集成功: id={}, dramaId={}, epNo={}",
                episode.getId(), episode.getDramaId(), episode.getEpisodeNo());
        return episode.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Long> batchCreate(DramaEpisodeBatchDTO dto) {
        if (dto == null || dto.getDramaId() == null) {
            throw new BizException(400, "短剧 ID 不能为空");
        }
        Drama drama = dramaMapper.selectById(dto.getDramaId());
        if (drama == null) {
            throw new BizException(404, "短剧不存在: " + dto.getDramaId());
        }

        int count = (dto.getCount() == null || dto.getCount() <= 0) ? 10 : dto.getCount();
        int startNo = (dto.getStartEpisodeNo() == null || dto.getStartEpisodeNo() <= 0) ? 1 : dto.getStartEpisodeNo();
        String prefix = dto.getTitlePrefix() != null ? dto.getTitlePrefix() : "第";
        String suffix = dto.getTitleSuffix() != null ? dto.getTitleSuffix() : "集";
        int targetDuration = dto.getTargetDuration() != null && dto.getTargetDuration() > 0 ? dto.getTargetDuration() : 300;

        List<Long> createdIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int epNo = startNo + i;
            // 检查集号是否已存在
            Long exists = episodeMapper.selectCount(
                    new LambdaQueryWrapper<DramaEpisode>()
                            .eq(DramaEpisode::getDramaId, dto.getDramaId())
                            .eq(DramaEpisode::getEpisodeNo, epNo)
            );
            if (exists != null && exists > 0) {
                continue;
            }

            DramaEpisode ep = new DramaEpisode();
            ep.setDramaId(dto.getDramaId());
            ep.setEpisodeNo(epNo);
            ep.setTitle(prefix + epNo + suffix);
            ep.setTargetDuration(targetDuration);
            ep.setActualDuration(BigDecimal.ZERO);
            ep.setStatus("DRAFT");
            ep.setSortOrder(epNo);

            episodeMapper.insert(ep);
            createdIds.add(ep.getId());
        }

        log.info("[DramaEpisodeService] 批量创建剧集成功: dramaId={}, count={}", dto.getDramaId(), createdIds.size());
        return createdIds;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(DramaEpisodeDTO dto) {
        if (dto == null || dto.getId() == null) {
            throw new BizException(400, "剧集 ID 不能为空");
        }
        DramaEpisode existing = episodeMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BizException(404, "剧集不存在: " + dto.getId());
        }

        DramaEpisode ep = new DramaEpisode();
        BeanUtils.copyProperties(dto, ep);
        episodeMapper.updateById(ep);
        log.info("[DramaEpisodeService] 更新剧集成功: id={}", ep.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        DramaEpisode existing = episodeMapper.selectById(id);
        if (existing == null) {
            return;
        }
        episodeMapper.deleteById(id);
        log.info("[DramaEpisodeService] 删除剧集成功: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recalculateEpisodeDuration(Long episodeId) {
        if (episodeId == null) {
            return;
        }
        List<DramaShot> shots = shotMapper.selectList(
                new LambdaQueryWrapper<DramaShot>()
                        .eq(DramaShot::getEpisodeId, episodeId)
        );
        BigDecimal total = BigDecimal.ZERO;
        for (DramaShot s : shots) {
            if (s.getDuration() != null) {
                total = total.add(s.getDuration());
            }
        }
        DramaEpisode ep = new DramaEpisode();
        ep.setId(episodeId);
        ep.setActualDuration(total);
        episodeMapper.updateById(ep);
    }

    private DramaEpisodeVO enrichEpisodeVO(DramaEpisode episode) {
        DramaEpisodeVO vo = new DramaEpisodeVO();
        BeanUtils.copyProperties(episode, vo);

        Long scCount = sceneMapper.selectCount(new LambdaQueryWrapper<DramaScene>().eq(DramaScene::getEpisodeId, episode.getId()));
        Long shCount = shotMapper.selectCount(new LambdaQueryWrapper<DramaShot>().eq(DramaShot::getEpisodeId, episode.getId()));
        Long renderedCount = shotMapper.selectCount(new LambdaQueryWrapper<DramaShot>()
                .eq(DramaShot::getEpisodeId, episode.getId())
                .eq(DramaShot::getRenderStatus, "SUCCESS"));

        vo.setSceneCount(scCount.intValue());
        vo.setShotCount(shCount.intValue());
        vo.setRenderedShotCount(renderedCount.intValue());
        return vo;
    }
}
