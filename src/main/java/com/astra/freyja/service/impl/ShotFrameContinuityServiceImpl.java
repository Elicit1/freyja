package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dto.drama.PreviousVideoTailRequest;
import com.astra.freyja.dto.drama.PreviousVideoTailVO;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.service.ShotFrameContinuityService;
import com.astra.freyja.service.VideoFrameExtractService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 镜头视听连续性服务实现。
 * 负责解析同镜头组内直接前驱镜头、校验视频版本与 MinIO 安全边界、按需抽帧与回写首帧。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShotFrameContinuityServiceImpl implements ShotFrameContinuityService {

    private final DramaShotMapper shotMapper;
    private final VideoFrameExtractService videoFrameExtractService;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    /** 针对来源分镜 ID 的并发操作锁，防止同一分镜短时间内重复触发 ffmpeg 抽帧 */
    private final ConcurrentHashMap<Long, Object> sourceShotLockMap = new ConcurrentHashMap<>();

    @Override
    public PreviousVideoTailVO inheritPreviousVideoTail(Long currentShotId, PreviousVideoTailRequest request) {
        if (currentShotId == null) {
            throw new BizException(400, "当前分镜 ID 不能为空");
        }

        // 1. 查询并校验当前镜头
        DramaShot currentShot = shotMapper.selectById(currentShotId);
        if (currentShot == null) {
            throw new BizException(404, "当前分镜不存在");
        }
        if (currentShot.getShotGroupId() == null || currentShot.getShotGroupId() <= 0) {
            throw new BizException(400, "当前分镜未归属连续镜头组");
        }

        // 2. 在同一 ShotGroup 内查询所有镜头并稳定排序
        List<DramaShot> groupShots = shotMapper.selectList(
                new LambdaQueryWrapper<DramaShot>()
                        .eq(DramaShot::getShotGroupId, currentShot.getShotGroupId())
        );
        if (groupShots == null || groupShots.isEmpty()) {
            throw new BizException(400, "当前分镜未归属有效镜头组");
        }

        List<DramaShot> sortedShots = new ArrayList<>(groupShots);
        sortShotsStably(sortedShots);

        int currentIndex = findShotIndex(sortedShots, currentShotId);
        if (currentIndex < 0) {
            throw new BizException(404, "当前分镜未在所属镜头组列表中");
        }
        if (currentIndex == 0) {
            throw new BizException(400, "当前分镜没有可引用的上一镜");
        }

        // 3. 取得直接前驱镜头并校验视频
        DramaShot prevShot = sortedShots.get(currentIndex - 1);
        if (StringUtils.isBlank(prevShot.getVideoUrl())) {
            throw new BizException(400, "请先生成上一镜视频");
        }

        // 4. 校验视频 URL 的存储安全边界 (必须在本项目的 MinIO bucket 中)
        String videoUrl = prevShot.getVideoUrl().trim();
        String bucket = minioProperties.getBucketName();
        String bucketToken = "/" + bucket + "/";
        int bucketIndex = videoUrl.indexOf(bucketToken);
        if (bucketIndex < 0) {
            throw new BizException(400, "上一镜视频不支持服务端尾帧提取");
        }

        String rawObjectKey = videoUrl.substring(bucketIndex + bucketToken.length());
        if (rawObjectKey.contains("?")) {
            rawObjectKey = rawObjectKey.substring(0, rawObjectKey.indexOf("?"));
        }

        String objectKey;
        try {
            objectKey = URLDecoder.decode(rawObjectKey, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException(400, "上一镜视频路径解析失败");
        }

        if (StringUtils.isBlank(objectKey) || objectKey.contains("..") || objectKey.startsWith("/")) {
            throw new BizException(400, "上一镜视频路径非法");
        }

        // 5. 并发控制与尾帧提取 (优先复用有效缓存)
        Long sourceShotId = prevShot.getId();
        Object lock = sourceShotLockMap.computeIfAbsent(sourceShotId, k -> new Object());

        String tailFrameUrl;
        boolean reused = false;

        synchronized (lock) {
            try {
                DramaShot latestPrev = shotMapper.selectById(sourceShotId);
                if (latestPrev == null || StringUtils.isBlank(latestPrev.getVideoUrl())) {
                    throw new BizException(400, "上一镜视频不存在或已被修改");
                }

                boolean forceExtract = request != null && Boolean.TRUE.equals(request.getForceExtract());
                boolean cacheValid;
                if (latestPrev.getCurrentVideoTakeId() != null) {
                    cacheValid = StringUtils.isNotBlank(latestPrev.getLastFrameUrl())
                            && Objects.equals(latestPrev.getLastFrameSourceTakeId(), latestPrev.getCurrentVideoTakeId());
                } else {
                    cacheValid = StringUtils.isNotBlank(latestPrev.getLastFrameUrl())
                            && Objects.equals(latestPrev.getLastFrameSourceVideoUrl(), latestPrev.getVideoUrl());
                }

                if (!forceExtract && cacheValid) {
                    tailFrameUrl = latestPrev.getLastFrameUrl();
                    reused = true;
                    log.info("[ShotFrameContinuity] 命中上一镜尾帧缓存: sourceShotId={}, tailFrameUrl={}", sourceShotId, tailFrameUrl);
                } else {
                    // 从 MinIO 下载视频二进制
                    byte[] videoBytes;
                    try (InputStream is = minioClient.getObject(
                            GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
                        videoBytes = is.readAllBytes();
                    } catch (Exception e) {
                        log.error("[ShotFrameContinuity] 读取上一镜视频文件失败: bucket={}, key={}, error={}", bucket, objectKey, e.getMessage());
                        throw new BizException(500, "从存储服务读取上一镜视频失败: " + e.getMessage());
                    }

                    // 确定扩展名
                    String ext = "mp4";
                    int dotIdx = objectKey.lastIndexOf('.');
                    if (dotIdx >= 0 && dotIdx < objectKey.length() - 1) {
                        ext = objectKey.substring(dotIdx + 1);
                    }

                    Integer offsetMs = request != null ? request.getTailOffsetMs() : null;
                    byte[] frameBytes = videoFrameExtractService.extractLastFrame(videoBytes, ext, offsetMs);
                    if (frameBytes == null || frameBytes.length == 0) {
                        throw new BizException(500, "上一镜视频尾帧提取失败，请检查 ffmpeg 配置");
                    }

                    // 归档尾帧至 MinIO
                    long timestamp = System.currentTimeMillis();
                    long dramaId = latestPrev.getDramaId() != null ? latestPrev.getDramaId() : 0L;
                    String frameObjectKey = String.format("projects/%d/shots/%d/frames/video_tail_%d.jpg",
                            dramaId, sourceShotId, timestamp);
                    ensureBucketExists(bucket);

                    try (InputStream fis = new ByteArrayInputStream(frameBytes)) {
                        minioClient.putObject(
                                PutObjectArgs.builder()
                                        .bucket(bucket)
                                        .object(frameObjectKey)
                                        .stream(fis, frameBytes.length, -1)
                                        .contentType("image/jpeg")
                                        .build()
                        );
                    } catch (Exception e) {
                        log.error("[ShotFrameContinuity] 上传提取的尾帧至 MinIO 失败: {}", e.getMessage(), e);
                        throw new BizException(500, "上传提取尾帧失败: " + e.getMessage());
                    }

                    String base = StringUtils.defaultIfBlank(minioProperties.getExternalEndpoint(), minioProperties.getEndpoint());
                    String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
                    String cleanObject = frameObjectKey.startsWith("/") ? frameObjectKey.substring(1) : frameObjectKey;
                    tailFrameUrl = String.format("%s/%s/%s", cleanBase, bucket, cleanObject);

                    // 回写来源镜头缓存
                    latestPrev.setLastFrameUrl(tailFrameUrl);
                    latestPrev.setLastFrameSourceVideoUrl(latestPrev.getVideoUrl());
                    latestPrev.setLastFrameSourceTakeId(latestPrev.getCurrentVideoTakeId());
                    shotMapper.updateById(latestPrev);
                    reused = false;
                    log.info("[ShotFrameContinuity] 成功从上一镜视频提取并归档尾部稳定帧: sourceShotId={}, tailFrameUrl={}", sourceShotId, tailFrameUrl);
                }
            } finally {
                sourceShotLockMap.remove(sourceShotId, lock);
            }
        }

        // 6. 重新检查当前分镜与所属组关系，防止抽帧期间被并发调整
        DramaShot latestCurrent = shotMapper.selectById(currentShotId);
        if (latestCurrent == null) {
            throw new BizException(404, "当前分镜不存在");
        }
        if (!Objects.equals(latestCurrent.getShotGroupId(), currentShot.getShotGroupId())) {
            throw new BizException(409, "镜头顺序已变化，请刷新后重试");
        }

        List<DramaShot> verifyShots = shotMapper.selectList(
                new LambdaQueryWrapper<DramaShot>()
                        .eq(DramaShot::getShotGroupId, latestCurrent.getShotGroupId())
        );
        List<DramaShot> verifySorted = new ArrayList<>(verifyShots);
        sortShotsStably(verifySorted);
        int verifyIndex = findShotIndex(verifySorted, currentShotId);
        if (verifyIndex <= 0 || !Objects.equals(verifySorted.get(verifyIndex - 1).getId(), sourceShotId)) {
            throw new BizException(409, "镜头顺序已变化，请刷新后重试");
        }

        // 7. 回写当前分镜首帧及来源追踪字段
        latestCurrent.setPreviewImageUrl(tailFrameUrl);
        latestCurrent.setFirstFrameSourceType("PREVIOUS_VIDEO_TAIL");
        latestCurrent.setFirstFrameSourceShotId(sourceShotId);
        latestCurrent.setFirstFrameSourceVideoUrl(prevShot.getVideoUrl());
        latestCurrent.setFirstFrameSourceVideoTakeId(prevShot.getCurrentVideoTakeId());
        shotMapper.updateById(latestCurrent);
        log.info("[ShotFrameContinuity] 当前镜头首帧引用成功: currentShotId={}, previewImageUrl={}, sourceShotId={}",
                currentShotId, tailFrameUrl, sourceShotId);

        // 8. 组装返回结果
        return PreviousVideoTailVO.builder()
                .currentShotId(currentShotId)
                .sourceShotId(sourceShotId)
                .sourceShotNo(prevShot.getShotNo())
                .sourceShotName(prevShot.getShotName())
                .sourceVideoUrl(prevShot.getVideoUrl())
                .tailFrameUrl(tailFrameUrl)
                .reused(reused)
                .applied(true)
                .build();
    }

    private void sortShotsStably(List<DramaShot> shots) {
        if (shots == null || shots.size() <= 1) {
            return;
        }
        shots.sort((a, b) -> {
            int soA = a.getSortOrder() != null ? a.getSortOrder() : 0;
            int soB = b.getSortOrder() != null ? b.getSortOrder() : 0;
            if (soA != soB) {
                return Integer.compare(soA, soB);
            }
            int noA = a.getShotNo() != null ? a.getShotNo() : 0;
            int noB = b.getShotNo() != null ? b.getShotNo() : 0;
            if (noA != noB) {
                return Integer.compare(noA, noB);
            }
            long idA = a.getId() != null ? a.getId() : 0L;
            long idB = b.getId() != null ? b.getId() : 0L;
            return Long.compare(idA, idB);
        });
    }

    private int findShotIndex(List<DramaShot> shots, Long shotId) {
        if (shots == null || shotId == null) {
            return -1;
        }
        for (int i = 0; i < shots.size(); i++) {
            if (shotId.equals(shots.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    private void ensureBucketExists(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            log.warn("[ShotFrameContinuity] 检查/创建 MinIO bucket 异常: bucket={}, err={}", bucket, e.getMessage());
        }
    }
}
