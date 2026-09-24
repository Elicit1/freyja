package com.astra.freyja.service.impl;

import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.dashboard.DashboardStatsVO;
import com.astra.freyja.dto.dashboard.RecentDramaVO;
import com.astra.freyja.entity.AiProvider;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.entity.DramaEpisode;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.service.DashboardService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工作台首页统计服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final DramaMapper dramaMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaSceneMapper sceneMapper;
    private final DramaShotMapper shotMapper;
    private final ResCharacterMapper characterMapper;
    private final ResSceneMapper resSceneMapper;
    private final ResPropMapper resPropMapper;
    private final AiProviderMapper aiProviderMapper;

    @Autowired(required = false)
    private AiTaskMapper aiTaskMapper;

    @Autowired(required = false)
    private MinioProperties minioProperties;

    @Override
    public DashboardStatsVO getDashboardStats() {
        // 1. 剧作大纲统计
        long dramaCount = dramaMapper.selectCount(null);
        long episodeCount = episodeMapper.selectCount(null);
        long sceneCount = sceneMapper.selectCount(null);
        long shotCount = shotMapper.selectCount(null);

        // 2. 资产库统计
        long characterCount = characterMapper.selectCount(null);
        long sceneAssetCount = resSceneMapper.selectCount(null);
        long propCount = resPropMapper != null ? resPropMapper.selectCount(null) : 0L;
        long totalAssetCount = characterCount + sceneAssetCount + propCount;

        // 3. 渲染成片与产物统计
        long renderedVideoCount = shotMapper.selectCount(
                new LambdaQueryWrapper<DramaShot>().isNotNull(DramaShot::getVideoUrl).ne(DramaShot::getVideoUrl, "")
        );
        long renderedImageCount = shotMapper.selectCount(
                new LambdaQueryWrapper<DramaShot>().isNotNull(DramaShot::getPreviewImageUrl).ne(DramaShot::getPreviewImageUrl, "")
        );

        // 4. AI 与基础设施状态
        long activeAiProviders = aiProviderMapper.selectCount(
                new LambdaQueryWrapper<AiProvider>().eq(AiProvider::getStatus, 1)
        );
        long aiTaskCount = aiTaskMapper != null ? aiTaskMapper.selectCount(null) : 0L;
        boolean aiReady = activeAiProviders > 0;
        boolean storageReady = minioProperties != null && StringUtils.isNotBlank(minioProperties.getEndpoint());

        // 5. 题材分布统计
        List<Drama> allDramas = dramaMapper.selectList(new LambdaQueryWrapper<Drama>().select(Drama::getId, Drama::getGenre));
        Map<String, Long> genreDistribution = allDramas != null
                ? allDramas.stream()
                        .filter(d -> StringUtils.isNotBlank(d.getGenre()))
                        .collect(Collectors.groupingBy(Drama::getGenre, Collectors.counting()))
                : Collections.emptyMap();

        // 6. 最近活跃短剧项目 (前 6 条)
        List<Drama> recentDramaList = dramaMapper.selectList(
                new LambdaQueryWrapper<Drama>()
                        .orderByDesc(Drama::getUpdateTime)
                        .orderByDesc(Drama::getId)
                        .last("LIMIT 6")
        );

        List<RecentDramaVO> recentDramas = new ArrayList<>();
        if (recentDramaList != null && !recentDramaList.isEmpty()) {
            for (Drama d : recentDramaList) {
                long epCount = episodeMapper.selectCount(
                        new LambdaQueryWrapper<DramaEpisode>().eq(DramaEpisode::getDramaId, d.getId())
                );
                long shCount = shotMapper.selectCount(
                        new LambdaQueryWrapper<DramaShot>().eq(DramaShot::getDramaId, d.getId())
                );

                recentDramas.add(RecentDramaVO.builder()
                        .id(d.getId())
                        .title(d.getTitle())
                        .genre(d.getGenre())
                        .coverUrl(d.getCoverUrl())
                        .status(d.getStatus())
                        .episodeCount((int) epCount)
                        .shotCount((int) shCount)
                        .updateTime(d.getUpdateTime() != null ? d.getUpdateTime() : d.getCreateTime())
                        .build());
            }
        }

        return DashboardStatsVO.builder()
                .dramaCount(dramaCount)
                .episodeCount(episodeCount)
                .sceneCount(sceneCount)
                .shotCount(shotCount)
                .characterCount(characterCount)
                .sceneAssetCount(sceneAssetCount)
                .propCount(propCount)
                .totalAssetCount(totalAssetCount)
                .renderedVideoCount(renderedVideoCount)
                .renderedImageCount(renderedImageCount)
                .aiProviderCount(activeAiProviders)
                .aiTaskCount(aiTaskCount)
                .aiServiceReady(aiReady)
                .storageReady(storageReady)
                .genreDistribution(genreDistribution)
                .recentDramas(recentDramas)
                .build();
    }
}
