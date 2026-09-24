package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaEpisodeMapper;
import com.astra.freyja.dao.DramaSceneMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.ResSceneMapper;
import com.astra.freyja.dto.drama.DramaSceneDTO;
import com.astra.freyja.dto.drama.DramaSceneVO;
import com.astra.freyja.entity.DramaEpisode;
import com.astra.freyja.entity.DramaScene;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.ResScene;
import com.astra.freyja.service.DramaSceneService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 情景场次管理服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DramaSceneServiceImpl implements DramaSceneService {

    private final DramaEpisodeMapper episodeMapper;
    private final DramaSceneMapper sceneMapper;
    private final DramaShotMapper shotMapper;
    private final ResSceneMapper resSceneMapper;

    @Override
    public List<DramaSceneVO> listByEpisodeId(Long episodeId) {
        if (episodeId == null) {
            return Collections.emptyList();
        }
        List<DramaScene> list = sceneMapper.selectList(
                new LambdaQueryWrapper<DramaScene>()
                        .eq(DramaScene::getEpisodeId, episodeId)
                        .orderByAsc(DramaScene::getSceneNo)
                        .orderByAsc(DramaScene::getSortOrder)
        );

        return list.stream().map(this::enrichSceneVO).collect(Collectors.toList());
    }

    @Override
    public DramaSceneVO getById(Long id) {
        DramaScene scene = sceneMapper.selectById(id);
        if (scene == null) {
            throw new BizException(404, "场次不存在: " + id);
        }
        return enrichSceneVO(scene);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(DramaSceneDTO dto) {
        if (dto == null || dto.getEpisodeId() == null) {
            throw new BizException(400, "归属剧集 ID 不能为空");
        }
        DramaEpisode episode = episodeMapper.selectById(dto.getEpisodeId());
        if (episode == null) {
            throw new BizException(404, "归属剧集不存在: " + dto.getEpisodeId());
        }

        DramaScene scene = new DramaScene();
        BeanUtils.copyProperties(dto, scene);

        if (scene.getDramaId() == null) {
            scene.setDramaId(episode.getDramaId());
        }

        if (scene.getSceneNo() == null || scene.getSceneNo() <= 0) {
            List<DramaScene> existing = sceneMapper.selectList(
                    new LambdaQueryWrapper<DramaScene>()
                            .eq(DramaScene::getEpisodeId, dto.getEpisodeId())
                            .orderByDesc(DramaScene::getSceneNo)
            );
            int nextNo = existing.isEmpty() ? 1 : existing.get(0).getSceneNo() + 1;
            scene.setSceneNo(nextNo);
        }

        if (StringUtils.isBlank(scene.getName())) {
            scene.setName("场次" + scene.getSceneNo());
        }

        // 若绑定了环境资产，但未指定环境属性，则从 ResScene 继承
        if (scene.getResSceneId() != null && scene.getResSceneId() > 0) {
            ResScene resScene = resSceneMapper.selectById(scene.getResSceneId());
            if (resScene != null) {
                if (StringUtils.isBlank(scene.getSceneType())) {
                    scene.setSceneType(resScene.getSceneType());
                }
                if (StringUtils.isBlank(scene.getTimeOfDay())) {
                    scene.setTimeOfDay(resScene.getTimeOfDay());
                }
                if (StringUtils.isBlank(scene.getWeatherAtmosphere())) {
                    scene.setWeatherAtmosphere(resScene.getWeatherAtmosphere());
                }
            }
        }

        if (StringUtils.isBlank(scene.getSceneType())) {
            scene.setSceneType(null);
        }
        if (StringUtils.isBlank(scene.getTimeOfDay())) {
            scene.setTimeOfDay("DAY");
        }
        if (scene.getSortOrder() == null) {
            scene.setSortOrder(scene.getSceneNo());
        }

        sceneMapper.insert(scene);
        log.info("[DramaSceneService] 创建场次成功: id={}, episodeId={}, sceneNo={}",
                scene.getId(), scene.getEpisodeId(), scene.getSceneNo());
        return scene.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(DramaSceneDTO dto) {
        if (dto == null || dto.getId() == null) {
            throw new BizException(400, "场次 ID 不能为空");
        }
        DramaScene existing = sceneMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BizException(404, "场次不存在: " + dto.getId());
        }

        DramaScene scene = new DramaScene();
        BeanUtils.copyProperties(dto, scene);
        sceneMapper.updateById(scene);
        log.info("[DramaSceneService] 更新场次成功: id={}", scene.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        DramaScene existing = sceneMapper.selectById(id);
        if (existing == null) {
            return;
        }
        sceneMapper.deleteById(id);
        log.info("[DramaSceneService] 删除场次成功: id={}", id);
    }

    private DramaSceneVO enrichSceneVO(DramaScene scene) {
        DramaSceneVO vo = new DramaSceneVO();
        BeanUtils.copyProperties(scene, vo);

        if (scene.getResSceneId() != null && scene.getResSceneId() > 0) {
            ResScene resScene = resSceneMapper.selectById(scene.getResSceneId());
            if (resScene != null) {
                vo.setResSceneName(resScene.getName());
                vo.setResSceneCoverUrl(resScene.getCoverUrl());
            }
        }

        Long shCount = shotMapper.selectCount(new LambdaQueryWrapper<DramaShot>().eq(DramaShot::getSceneId, scene.getId()));
        Long renderedCount = shotMapper.selectCount(new LambdaQueryWrapper<DramaShot>()
                .eq(DramaShot::getSceneId, scene.getId())
                .eq(DramaShot::getRenderStatus, "SUCCESS"));

        vo.setShotCount(shCount.intValue());
        vo.setRenderedShotCount(renderedCount.intValue());
        return vo;
    }
}
