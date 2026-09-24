package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaEpisodeMapper;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.DramaSceneMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.ResSceneMapper;
import com.astra.freyja.dto.drama.DramaDTO;
import com.astra.freyja.dto.drama.DramaOptionVO;
import com.astra.freyja.dto.drama.DramaQuery;
import com.astra.freyja.dto.drama.DramaStatsVO;
import com.astra.freyja.dto.drama.DramaTreeVO;
import com.astra.freyja.dto.drama.DramaVO;
import com.astra.freyja.dto.drama.DramaShotVO;
import com.astra.freyja.dto.drama.EpisodeTreeVO;
import com.astra.freyja.dto.drama.SceneTreeVO;
import com.astra.freyja.dto.drama.ShotSummaryVO;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.entity.DramaEpisode;
import com.astra.freyja.entity.DramaScene;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.ResScene;
import com.astra.freyja.service.DramaService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.astra.freyja.dao.DramaShotGroupMapper;
import com.astra.freyja.dto.drama.DramaShotGroupVO;
import com.astra.freyja.entity.DramaShotGroup;

/**
 * 短剧项目管理服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DramaServiceImpl implements DramaService {

    private final DramaMapper dramaMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaSceneMapper sceneMapper;
    private final DramaShotGroupMapper shotGroupMapper;
    private final DramaShotMapper shotMapper;
    private final ResSceneMapper resSceneMapper;

    @Override
    public Page<DramaVO> page(DramaQuery query) {
        if (query == null) {
            query = new DramaQuery();
        }
        Page<Drama> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<Drama> wrapper = new LambdaQueryWrapper<Drama>()
                .like(StringUtils.isNotBlank(query.getTitle()), Drama::getTitle, query.getTitle())
                .eq(StringUtils.isNotBlank(query.getGenre()), Drama::getGenre, query.getGenre())
                .eq(StringUtils.isNotBlank(query.getStatus()), Drama::getStatus, query.getStatus())
                .orderByAsc(Drama::getSortOrder)
                .orderByDesc(Drama::getCreateTime);

        Page<Drama> dramaPage = dramaMapper.selectPage(page, wrapper);
        Page<DramaVO> voPage = new Page<>(dramaPage.getCurrent(), dramaPage.getSize(), dramaPage.getTotal());

        if (dramaPage.getRecords().isEmpty()) {
            voPage.setRecords(Collections.emptyList());
            return voPage;
        }

        List<DramaVO> voList = dramaPage.getRecords().stream()
                .map(this::enrichDramaVO)
                .collect(Collectors.toList());
        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public DramaVO getById(Long id) {
        Drama drama = dramaMapper.selectById(id);
        if (drama == null) {
            throw new BizException(404, "短剧项目不存在或已被删除: " + id);
        }
        return enrichDramaVO(drama);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(DramaDTO dto) {
        if (dto == null || StringUtils.isBlank(dto.getTitle())) {
            throw new BizException(400, "短剧名称不能为空");
        }

        Drama drama = new Drama();
        BeanUtils.copyProperties(dto, drama);
        if (StringUtils.isBlank(drama.getGenre())) {
            drama.setGenre("DOMINANT_CEO");
        }
        if (drama.getTargetEpisodes() == null || drama.getTargetEpisodes() <= 0) {
            drama.setTargetEpisodes(80);
        }
        if (StringUtils.isBlank(drama.getAspectRatio())) {
            drama.setAspectRatio("9:16");
        }
        if (StringUtils.isBlank(drama.getStylePreset())) {
            drama.setStylePreset("cinematic-realism");
        }
        if (StringUtils.isBlank(drama.getStatus())) {
            drama.setStatus("PLANNING");
        }
        if (drama.getSortOrder() == null) {
            drama.setSortOrder(0);
        }

        dramaMapper.insert(drama);
        log.info("[DramaService] 创建短剧成功: id={}, title={}", drama.getId(), drama.getTitle());
        return drama.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(DramaDTO dto) {
        if (dto == null || dto.getId() == null) {
            throw new BizException(400, "短剧 ID 不能为空");
        }
        Drama existing = dramaMapper.selectById(dto.getId());
        if (existing == null) {
            throw new BizException(404, "短剧不存在: " + dto.getId());
        }

        Drama drama = new Drama();
        BeanUtils.copyProperties(dto, drama);
        dramaMapper.updateById(drama);
        log.info("[DramaService] 更新短剧成功: id={}", drama.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Drama existing = dramaMapper.selectById(id);
        if (existing == null) {
            return;
        }
        dramaMapper.deleteById(id);
        log.info("[DramaService] 删除短剧成功: id={}", id);
    }

    @Override
    public List<DramaOptionVO> options() {
        LambdaQueryWrapper<Drama> wrapper = new LambdaQueryWrapper<Drama>()
                .select(Drama::getId, Drama::getTitle, Drama::getCoverUrl, Drama::getGenre, Drama::getAspectRatio, Drama::getStylePreset, Drama::getStyleTone, Drama::getStatus)
                .orderByAsc(Drama::getSortOrder)
                .orderByDesc(Drama::getCreateTime);

        List<Drama> list = dramaMapper.selectList(wrapper);
        return list.stream().map(d -> DramaOptionVO.builder()
                .id(d.getId())
                .title(d.getTitle())
                .coverUrl(d.getCoverUrl())
                .genre(d.getGenre())
                .aspectRatio(d.getAspectRatio())
                .stylePreset(d.getStylePreset())
                .styleTone(d.getStyleTone())
                .status(d.getStatus())
                .build()
        ).collect(Collectors.toList());
    }

    @Override
    public DramaTreeVO getDramaTree(Long dramaId) {
        Drama drama = dramaMapper.selectById(dramaId);
        if (drama == null) {
            throw new BizException(404, "短剧不存在: " + dramaId);
        }

        // 1. 获取所有剧集
        List<DramaEpisode> episodes = episodeMapper.selectList(
                new LambdaQueryWrapper<DramaEpisode>()
                        .eq(DramaEpisode::getDramaId, dramaId)
                        .orderByAsc(DramaEpisode::getEpisodeNo)
                        .orderByAsc(DramaEpisode::getSortOrder)
        );

        // 2. 获取所有场次
        List<DramaScene> scenes = sceneMapper.selectList(
                new LambdaQueryWrapper<DramaScene>()
                        .eq(DramaScene::getDramaId, dramaId)
                        .orderByAsc(DramaScene::getSceneNo)
                        .orderByAsc(DramaScene::getSortOrder)
        );

        // 3. 获取所有镜头组
        List<DramaShotGroup> shotGroups = shotGroupMapper.selectList(
                new LambdaQueryWrapper<DramaShotGroup>()
                        .eq(DramaShotGroup::getDramaId, dramaId)
                        .orderByAsc(DramaShotGroup::getSceneId)
                        .orderByAsc(DramaShotGroup::getGroupNo)
                        .orderByAsc(DramaShotGroup::getSortOrder)
        );

        // 4. 获取所有分镜
        List<DramaShot> shots = shotMapper.selectList(
                new LambdaQueryWrapper<DramaShot>()
                        .eq(DramaShot::getDramaId, dramaId)
                        .orderByAsc(DramaShot::getShotNo)
                        .orderByAsc(DramaShot::getSortOrder)
        );

        // 5. 获取涉及的环境场景资产
        Map<Long, ResScene> resSceneMap = new HashMap<>();
        List<Long> resSceneIds = scenes.stream()
                .map(DramaScene::getResSceneId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (!resSceneIds.isEmpty()) {
            List<ResScene> resScenes = resSceneMapper.selectBatchIds(resSceneIds);
            for (ResScene rs : resScenes) {
                resSceneMap.put(rs.getId(), rs);
            }
        }

        // 6. 将分镜按 sceneId 和 shotGroupId 分组
        Map<Long, List<ShotSummaryVO>> shotMapByScene = new HashMap<>();
        Map<Long, List<DramaShotVO>> shotVOMapByGroup = new HashMap<>();
        int totalShotsCount = shots.size();
        int totalRenderedCount = 0;

        for (DramaShot s : shots) {
            if ("SUCCESS".equalsIgnoreCase(s.getRenderStatus())) {
                totalRenderedCount++;
            }
            ShotSummaryVO summaryVO = ShotSummaryVO.builder()
                    .id(s.getId())
                    .sceneId(s.getSceneId())
                    .shotGroupId(s.getShotGroupId())
                    .shotNo(s.getShotNo())
                    .shotName(s.getShotName())
                    .shotType(s.getShotType())
                    .cameraMovement(s.getCameraMovement())
                    .duration(s.getDuration())
                    .actionDescription(s.getActionDescription())
                    .dialogue(s.getDialogue())
                    .dialogueSpeaker(s.getDialogueSpeaker())
                    .renderStatus(s.getRenderStatus())
                    .generationMode(s.getGenerationMode())
                    .previewImageUrl(s.getPreviewImageUrl())
                    .endFrameImageUrl(s.getEndFrameImageUrl())
                    .videoUrl(s.getVideoUrl())
                    .sortOrder(s.getSortOrder())
                    .build();

            shotMapByScene.computeIfAbsent(s.getSceneId(), k -> new ArrayList<>()).add(summaryVO);

            if (s.getShotGroupId() != null && s.getShotGroupId() > 0) {
                DramaShotVO shotVO = new DramaShotVO();
                BeanUtils.copyProperties(s, shotVO);
                shotVOMapByGroup.computeIfAbsent(s.getShotGroupId(), k -> new ArrayList<>()).add(shotVO);
            }
        }

        // 7. 将镜头组按 sceneId 分组
        Map<Long, List<DramaShotGroupVO>> groupMapByScene = new HashMap<>();
        for (DramaShotGroup g : shotGroups) {
            DramaShotGroupVO gvo = new DramaShotGroupVO();
            BeanUtils.copyProperties(g, gvo);

            List<DramaShotVO> childShots = shotVOMapByGroup.getOrDefault(g.getId(), Collections.emptyList());
            gvo.setShots(childShots);
            gvo.setShotCount(childShots.size());
            gvo.setRenderedShotCount((int) childShots.stream().filter(s -> "SUCCESS".equalsIgnoreCase(s.getRenderStatus())).count());

            BigDecimal totalDur = BigDecimal.ZERO;
            for (DramaShotVO s : childShots) {
                if (s.getDuration() != null) {
                    totalDur = totalDur.add(s.getDuration());
                }
            }
            gvo.setTotalDuration(totalDur);

            groupMapByScene.computeIfAbsent(g.getSceneId(), k -> new ArrayList<>()).add(gvo);
        }

        // 8. 将场次按 episodeId 分组
        Map<Long, List<SceneTreeVO>> sceneMapByEpisode = new HashMap<>();
        for (DramaScene sc : scenes) {
            List<ShotSummaryVO> childShots = shotMapByScene.getOrDefault(sc.getId(), Collections.emptyList());
            List<DramaShotGroupVO> childGroups = groupMapByScene.getOrDefault(sc.getId(), Collections.emptyList());
            ResScene boundResScene = sc.getResSceneId() != null ? resSceneMap.get(sc.getResSceneId()) : null;

            SceneTreeVO sceneTreeVO = SceneTreeVO.builder()
                    .id(sc.getId())
                    .episodeId(sc.getEpisodeId())
                    .sceneNo(sc.getSceneNo())
                    .name(sc.getName())
                    .resSceneId(sc.getResSceneId())
                    .resSceneName(boundResScene != null ? boundResScene.getName() : null)
                    .resSceneCoverUrl(boundResScene != null ? boundResScene.getCoverUrl() : null)
                    .sceneType(sc.getSceneType())
                    .timeOfDay(sc.getTimeOfDay())
                    .weatherAtmosphere(sc.getWeatherAtmosphere())
                    .locationName(sc.getLocationName())
                    .summary(sc.getSummary())
                    .sortOrder(sc.getSortOrder())
                    .shotGroups(childGroups)
                    .shots(childShots)
                    .build();

            sceneMapByEpisode.computeIfAbsent(sc.getEpisodeId(), k -> new ArrayList<>()).add(sceneTreeVO);
        }

        // 7. 组装剧集节点
        List<EpisodeTreeVO> episodeTreeList = new ArrayList<>();
        for (DramaEpisode ep : episodes) {
            List<SceneTreeVO> childScenes = sceneMapByEpisode.getOrDefault(ep.getId(), Collections.emptyList());
            int epShotCount = 0;
            int epRenderedCount = 0;
            for (SceneTreeVO sc : childScenes) {
                if (sc.getShots() != null) {
                    epShotCount += sc.getShots().size();
                    for (ShotSummaryVO shot : sc.getShots()) {
                        if ("SUCCESS".equalsIgnoreCase(shot.getRenderStatus())) {
                            epRenderedCount++;
                        }
                    }
                }
            }

            EpisodeTreeVO episodeTreeVO = EpisodeTreeVO.builder()
                    .id(ep.getId())
                    .dramaId(ep.getDramaId())
                    .episodeNo(ep.getEpisodeNo())
                    .title(ep.getTitle())
                    .summary(ep.getSummary())
                    .targetDuration(ep.getTargetDuration())
                    .actualDuration(ep.getActualDuration())
                    .status(ep.getStatus())
                    .sortOrder(ep.getSortOrder())
                    .sceneCount(childScenes.size())
                    .shotCount(epShotCount)
                    .renderedShotCount(epRenderedCount)
                    .scenes(childScenes)
                    .build();

            episodeTreeList.add(episodeTreeVO);
        }

        int progressPct = totalShotsCount == 0 ? 0 : (totalRenderedCount * 100) / totalShotsCount;

        return DramaTreeVO.builder()
                .id(drama.getId())
                .title(drama.getTitle())
                .coverUrl(drama.getCoverUrl())
                .genre(drama.getGenre())
                .targetEpisodes(drama.getTargetEpisodes())
                .aspectRatio(drama.getAspectRatio())
                .stylePreset(drama.getStylePreset())
                .synopsis(drama.getSynopsis())
                .status(drama.getStatus())
                .totalEpisodes(episodes.size())
                .totalScenes(scenes.size())
                .totalShots(totalShotsCount)
                .renderedShots(totalRenderedCount)
                .progressPercentage(progressPct)
                .episodes(episodeTreeList)
                .build();
    }

    @Override
    public DramaStatsVO getStats(Long dramaId) {
        Drama drama = dramaMapper.selectById(dramaId);
        if (drama == null) {
            throw new BizException(404, "短剧不存在: " + dramaId);
        }

        Long episodeCount = episodeMapper.selectCount(new LambdaQueryWrapper<DramaEpisode>().eq(DramaEpisode::getDramaId, dramaId));
        Long sceneCount = sceneMapper.selectCount(new LambdaQueryWrapper<DramaScene>().eq(DramaScene::getDramaId, dramaId));
        List<DramaShot> shots = shotMapper.selectList(new LambdaQueryWrapper<DramaShot>().eq(DramaShot::getDramaId, dramaId));

        int totalShots = shots.size();
        int rendered = 0;
        int rendering = 0;
        int failed = 0;
        int init = 0;
        BigDecimal totalDuration = BigDecimal.ZERO;

        for (DramaShot s : shots) {
            String st = s.getRenderStatus();
            if ("SUCCESS".equalsIgnoreCase(st)) {
                rendered++;
            } else if ("RENDERING".equalsIgnoreCase(st) || "QUEUED".equalsIgnoreCase(st)) {
                rendering++;
            } else if ("FAILED".equalsIgnoreCase(st)) {
                failed++;
            } else {
                init++;
            }
            if (s.getDuration() != null) {
                totalDuration = totalDuration.add(s.getDuration());
            }
        }

        int progressPct = totalShots == 0 ? 0 : (rendered * 100) / totalShots;

        return DramaStatsVO.builder()
                .dramaId(dramaId)
                .title(drama.getTitle())
                .targetEpisodes(drama.getTargetEpisodes())
                .actualEpisodes(episodeCount.intValue())
                .totalScenes(sceneCount.intValue())
                .totalShots(totalShots)
                .renderedShots(rendered)
                .renderingShots(rendering)
                .failedShots(failed)
                .initShots(init)
                .progressPercentage(progressPct)
                .totalEstimatedDuration(totalDuration)
                .build();
    }

    private DramaVO enrichDramaVO(Drama drama) {
        DramaVO vo = new DramaVO();
        BeanUtils.copyProperties(drama, vo);

        Long epCount = episodeMapper.selectCount(new LambdaQueryWrapper<DramaEpisode>().eq(DramaEpisode::getDramaId, drama.getId()));
        Long scCount = sceneMapper.selectCount(new LambdaQueryWrapper<DramaScene>().eq(DramaScene::getDramaId, drama.getId()));
        Long shCount = shotMapper.selectCount(new LambdaQueryWrapper<DramaShot>().eq(DramaShot::getDramaId, drama.getId()));
        Long renderedCount = shotMapper.selectCount(new LambdaQueryWrapper<DramaShot>()
                .eq(DramaShot::getDramaId, drama.getId())
                .eq(DramaShot::getRenderStatus, "SUCCESS"));

        vo.setEpisodeCount(epCount.intValue());
        vo.setSceneCount(scCount.intValue());
        vo.setShotCount(shCount.intValue());
        vo.setRenderedShotCount(renderedCount.intValue());
        vo.setProgressPercentage(shCount == 0 ? 0 : (int) ((renderedCount * 100) / shCount));
        return vo;
    }
}
