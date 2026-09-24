package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.video.ResolvedVideoProcessSource;
import com.astra.freyja.dto.video.VideoProcessProbeSourceDTO;
import com.astra.freyja.dto.video.VideoProcessSubmitDTO;
import com.astra.freyja.entity.*;
import com.astra.freyja.entity.enums.VideoProcessSourceType;
import com.astra.freyja.service.VideoProcessSourceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Objects;

/**
 * 视频后处理来源安全解析实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoProcessSourceResolverImpl implements VideoProcessSourceResolver {

    private final DramaShotMapper shotMapper;
    private final DramaShotVideoTakeMapper videoTakeMapper;
    private final DramaMapper dramaMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaSceneMapper sceneMapper;
    private final MinioProperties minioProperties;

    @Override
    public ResolvedVideoProcessSource resolve(VideoProcessSubmitDTO dto) {
        if (dto == null) {
            throw new BizException(400, "提交参数不能为空");
        }

        String sourceTypeStr = StringUtils.isBlank(dto.getSourceType())
                ? VideoProcessSourceType.DIRECT_URL.name()
                : dto.getSourceType().trim().toUpperCase();

        VideoProcessSourceType sourceType;
        try {
            sourceType = VideoProcessSourceType.valueOf(sourceTypeStr);
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "不支持的源视频类型: " + dto.getSourceType());
        }

        return doResolve(
                sourceType,
                dto.getSourceVideoUrl(),
                dto.getSourceShotId(),
                dto.getSourceVideoTakeId(),
                false
        );
    }

    @Override
    public ResolvedVideoProcessSource resolveForProbe(VideoProcessProbeSourceDTO dto) {
        if (dto == null) {
            throw new BizException(400, "探测参数不能为空");
        }

        String sourceTypeStr = StringUtils.isBlank(dto.getSourceType())
                ? VideoProcessSourceType.DIRECT_URL.name()
                : dto.getSourceType().trim().toUpperCase();

        VideoProcessSourceType sourceType;
        try {
            sourceType = VideoProcessSourceType.valueOf(sourceTypeStr);
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "不支持的源视频类型: " + dto.getSourceType());
        }

        return doResolve(
                sourceType,
                dto.getSourceVideoUrl(),
                dto.getSourceShotId(),
                dto.getSourceVideoTakeId(),
                true
        );
    }

    private ResolvedVideoProcessSource doResolve(
            VideoProcessSourceType sourceType,
            String sourceVideoUrl,
            Long sourceShotId,
            Long sourceVideoTakeId,
            boolean isProbe) {

        switch (sourceType) {
            case DIRECT_URL -> {
                if (sourceShotId != null || sourceVideoTakeId != null) {
                    throw new BizException(400, "DIRECT_URL 模式下不允许携带 sourceShotId 或 sourceVideoTakeId");
                }
                if (StringUtils.isBlank(sourceVideoUrl)) {
                    throw new BizException(400, "源视频地址 (sourceVideoUrl) 不能为空");
                }
                String url = sourceVideoUrl.trim();
                validateUrlSafety(url);
                return ResolvedVideoProcessSource.builder()
                        .sourceType(VideoProcessSourceType.DIRECT_URL.name())
                        .videoUrl(url)
                        .build();
            }

            case SHOT_CURRENT -> {
                if (sourceShotId == null) {
                    throw new BizException(400, "SHOT_CURRENT 模式下 sourceShotId 不能为空");
                }
                DramaShot shot = shotMapper.selectById(sourceShotId);
                if (shot == null || (shot.getDeleted() != null && shot.getDeleted() == 1)) {
                    throw new BizException(404, "指定的短剧分镜不存在");
                }
                if (StringUtils.isBlank(shot.getVideoUrl())) {
                    throw new BizException(400, "该分镜尚未生成视频");
                }

                Long resolvedTakeId = null;
                // 如果分镜关联了当前 Take，校验 Take 一致性
                if (shot.getCurrentVideoTakeId() != null) {
                    DramaShotVideoTake currentTake = videoTakeMapper.selectById(shot.getCurrentVideoTakeId());
                    if (currentTake == null || (currentTake.getDeleted() != null && currentTake.getDeleted() == 1)) {
                        throw new BizException(400, "分镜关联的当前视频 Take 不存在或已删除");
                    }
                    if (!Objects.equals(currentTake.getShotId(), shot.getId())) {
                        throw new BizException(400, "分镜当前 Take 与该分镜不匹配");
                    }
                    if (!"AVAILABLE".equalsIgnoreCase(currentTake.getStatus())) {
                        throw new BizException(400, "分镜当前 Take 状态不可用 (" + currentTake.getStatus() + ")");
                    }
                    if (!StringUtils.equals(shot.getVideoUrl().trim(), currentTake.getVideoUrl() != null ? currentTake.getVideoUrl().trim() : "")) {
                        throw new BizException(400, "分镜当前视频地址与当前 Take 记录不一致，数据存在冲突");
                    }
                    resolvedTakeId = currentTake.getId();
                }

                String videoUrl = shot.getVideoUrl().trim();
                validateUrlSafety(videoUrl);

                return buildShotResolvedSource(sourceType.name(), videoUrl, shot, resolvedTakeId);
            }

            case SHOT_VIDEO_TAKE -> {
                if (sourceShotId == null) {
                    throw new BizException(400, "SHOT_VIDEO_TAKE 模式下 sourceShotId 不能为空");
                }
                if (sourceVideoTakeId == null) {
                    throw new BizException(400, "SHOT_VIDEO_TAKE 模式下 sourceVideoTakeId 不能为空");
                }

                DramaShot shot = shotMapper.selectById(sourceShotId);
                if (shot == null || (shot.getDeleted() != null && shot.getDeleted() == 1)) {
                    throw new BizException(404, "指定的短剧分镜不存在");
                }

                DramaShotVideoTake take = videoTakeMapper.selectById(sourceVideoTakeId);
                if (take == null || (take.getDeleted() != null && take.getDeleted() == 1)) {
                    throw new BizException(404, "指定的视频 Take 版本不存在");
                }
                if (!Objects.equals(take.getShotId(), sourceShotId)) {
                    throw new BizException(400, "指定的视频 Take 不属于当前分镜");
                }
                if (!"AVAILABLE".equalsIgnoreCase(take.getStatus())) {
                    throw new BizException(400, "指定的视频 Take 状态不可用 (" + take.getStatus() + ")");
                }
                if (StringUtils.isBlank(take.getVideoUrl())) {
                    throw new BizException(400, "指定的视频 Take 视频地址为空");
                }

                String videoUrl = take.getVideoUrl().trim();
                validateUrlSafety(videoUrl);

                return buildShotResolvedSource(sourceType.name(), videoUrl, shot, take.getId());
            }

            default -> throw new BizException(400, "不支持的源类型: " + sourceType);
        }
    }

    private ResolvedVideoProcessSource buildShotResolvedSource(
            String sourceType,
            String videoUrl,
            DramaShot shot,
            Long takeId) {

        String dramaTitle = null;
        if (shot.getDramaId() != null) {
            Drama drama = dramaMapper.selectById(shot.getDramaId());
            if (drama != null) {
                dramaTitle = drama.getTitle();
            }
        }

        String episodeTitle = null;
        if (shot.getEpisodeId() != null) {
            DramaEpisode episode = episodeMapper.selectById(shot.getEpisodeId());
            if (episode != null) {
                episodeTitle = episode.getTitle();
            }
        }

        String sceneName = null;
        if (shot.getSceneId() != null) {
            DramaScene scene = sceneMapper.selectById(shot.getSceneId());
            if (scene != null) {
                sceneName = scene.getName();
            }
        }

        return ResolvedVideoProcessSource.builder()
                .sourceType(sourceType)
                .videoUrl(videoUrl)
                .dramaId(shot.getDramaId())
                .episodeId(shot.getEpisodeId())
                .sceneId(shot.getSceneId())
                .shotId(shot.getId())
                .videoTakeId(takeId)
                .shotNo(shot.getShotNo())
                .shotName(shot.getShotName())
                .dramaTitle(dramaTitle)
                .episodeTitle(episodeTitle)
                .sceneName(sceneName)
                .build();
    }

    @Override
    public void validateUrlSafety(String url) {
        if (StringUtils.isBlank(url)) {
            throw new BizException(400, "视频 URL 不能为空");
        }
        String cleanUrl = url.trim();
        URI uri;
        try {
            uri = URI.create(cleanUrl);
        } catch (Exception e) {
            throw new BizException(400, "非法的视频 URL 格式: " + cleanUrl);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new BizException(400, "不支持的视频协议: " + scheme + " (仅支持 HTTP / HTTPS)");
        }

        String host = uri.getHost();
        if (StringUtils.isBlank(host)) {
            throw new BizException(400, "视频 URL 缺少有效的主机名");
        }

        // 检查本地环回与私有地址安全
        boolean isLocal = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "0.0.0.0".equals(host);
        if (isLocal) {
            // 如果指向本地环回，必须是系统配置的 MinIO 存储端点
            boolean matchesMinio = isMatchingMinioEndpoint(cleanUrl, uri);
            if (!matchesMinio) {
                throw new BizException(400, "禁止访问未授权的本地受限网络资源 (SSRF Protection)");
            }
        }
    }

    private boolean isMatchingMinioEndpoint(String url, URI uri) {
        String bucket = minioProperties.getBucketName();
        if (StringUtils.isNotBlank(bucket) && url.contains("/" + bucket + "/")) {
            return true;
        }
        String ep = minioProperties.getEndpoint();
        if (StringUtils.isNotBlank(ep)) {
            try {
                URI epUri = URI.create(ep);
                if (Objects.equals(epUri.getHost(), uri.getHost()) && epUri.getPort() == uri.getPort()) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        String extEp = minioProperties.getExternalEndpoint();
        if (StringUtils.isNotBlank(extEp)) {
            try {
                URI extUri = URI.create(extEp);
                if (Objects.equals(extUri.getHost(), uri.getHost()) && extUri.getPort() == uri.getPort()) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}
