package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.DramaShotVideoTakeMapper;
import com.astra.freyja.dao.MediaProcessTaskMapper;
import com.astra.freyja.dto.drama.DramaShotRenderRequestDTO;
import com.astra.freyja.dto.drama.ShotVideoTakeQuery;
import com.astra.freyja.dto.drama.ShotVideoTakeSelectVO;
import com.astra.freyja.dto.drama.ShotVideoTakeVO;
import com.astra.freyja.dto.drama.VideoGenerationResultVO;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.DramaShotVideoTake;
import com.astra.freyja.entity.MediaProcessTask;
import com.astra.freyja.entity.RenderTask;
import com.astra.freyja.service.ShotVideoTakeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLDecoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 分镜视频抽卡候选版本服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShotVideoTakeServiceImpl implements ShotVideoTakeService {

    private final DramaShotMapper shotMapper;
    private final DramaShotVideoTakeMapper videoTakeMapper;
    private final MediaProcessTaskMapper mediaProcessTaskMapper;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final ObjectMapper objectMapper;

    @Override
    public Page<ShotVideoTakeVO> pageByShotId(Long shotId, ShotVideoTakeQuery query) {
        if (shotId == null) {
            throw new BizException(400, "分镜ID不能为空");
        }
        DramaShot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BizException(404, "分镜不存在: " + shotId);
        }

        if (query == null) {
            query = new ShotVideoTakeQuery();
        }

        LambdaQueryWrapper<DramaShotVideoTake> qw = new LambdaQueryWrapper<DramaShotVideoTake>()
                .eq(DramaShotVideoTake::getShotId, shotId)
                .orderByDesc(DramaShotVideoTake::getCreateTime)
                .orderByDesc(DramaShotVideoTake::getTakeNo)
                .orderByDesc(DramaShotVideoTake::getId);

        if (StringUtils.isNotBlank(query.getStatus())) {
            qw.eq(DramaShotVideoTake::getStatus, query.getStatus().trim());
        }

        Page<DramaShotVideoTake> pageParam = new Page<>(query.getCurrent(), query.getSize());
        Page<DramaShotVideoTake> takePage = videoTakeMapper.selectPage(pageParam, qw);

        Page<ShotVideoTakeVO> resultPage = new Page<>(takePage.getCurrent(), takePage.getSize(), takePage.getTotal());
        final Long currentTakeId = shot.getCurrentVideoTakeId();

        resultPage.setRecords(takePage.getRecords().stream().map(take -> {
            boolean isCurrent = Objects.equals(take.getId(), currentTakeId);
            return ShotVideoTakeVO.builder()
                    .id(take.getId())
                    .shotId(take.getShotId())
                    .takeNo(take.getTakeNo())
                    .taskId(take.getTaskId())
                    .sourceType(take.getSourceType())
                    .status(take.getStatus())
                    .videoUrl(take.getVideoUrl())
                    .current(isCurrent)
                    .providerId(take.getProviderId())
                    .providerName(take.getProviderName())
                    .modelCode(take.getModelCode())
                    .generationMode(take.getGenerationMode())
                    .seed(take.getSeed())
                    .size(take.getSize())
                    .duration(take.getDuration())
                    .promptSnapshot(take.getPromptSnapshot())
                    .negativePromptSnapshot(take.getNegativePromptSnapshot())
                    .firstFrameUrl(take.getFirstFrameUrl())
                    .endFrameUrl(take.getEndFrameUrl())
                    .createTime(take.getCreateTime())
                    .build();
        }).toList());

        return resultPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DramaShotVideoTake recordGeneratedTake(
            DramaShot shotSnapshot,
            RenderTask renderTask,
            DramaShotRenderRequestDTO requestSnapshot,
            VideoGenerationResultVO result) {

        if (shotSnapshot == null || shotSnapshot.getId() == null) {
            throw new BizException(400, "分镜快照数据缺失，无法保存视频候选版本");
        }
        if (result == null || StringUtils.isBlank(result.getVideoUrl())) {
            throw new BizException(400, "生成的视频产物地址为空，无法创建候选版本");
        }

        String taskId = renderTask != null ? renderTask.getTaskId() : null;
        if (StringUtils.isNotBlank(taskId)) {
            DramaShotVideoTake existing = videoTakeMapper.selectOne(
                    new LambdaQueryWrapper<DramaShotVideoTake>().eq(DramaShotVideoTake::getTaskId, taskId)
            );
            if (existing != null) {
                log.info("[ShotVideoTakeService] 渲染任务对应视频版本已存在，幂等返回: taskId={}, takeId={}", taskId, existing.getId());
                return existing;
            }
        }

        Long shotId = shotSnapshot.getId();
        int maxRetries = 2;
        DramaShotVideoTake take = null;

        for (int i = 0; i <= maxRetries; i++) {
            int nextTakeNo = allocateNextTakeNo(shotId);

            take = new DramaShotVideoTake();
            take.setDramaId(shotSnapshot.getDramaId());
            take.setEpisodeId(shotSnapshot.getEpisodeId());
            take.setSceneId(shotSnapshot.getSceneId());
            take.setShotGroupId(shotSnapshot.getShotGroupId());
            take.setShotId(shotId);
            take.setTakeNo(nextTakeNo);
            take.setTaskId(taskId);
            take.setSourceType("AI_GENERATED");
            take.setStatus("AVAILABLE");
            take.setVideoUrl(result.getVideoUrl());
            take.setProviderId(renderTask != null ? renderTask.getProviderId() : (requestSnapshot != null ? requestSnapshot.getProviderId() : null));
            take.setProviderName(renderTask != null ? renderTask.getProviderName() : null);
            take.setModelCode(renderTask != null ? renderTask.getModelCode() : null);
            take.setGenerationMode(shotSnapshot.getGenerationMode());
            take.setSeed(requestSnapshot != null ? requestSnapshot.getSeed() : null);
            take.setSize(requestSnapshot != null ? requestSnapshot.getSize() : null);
            take.setDuration(shotSnapshot.getDuration());

            String prompt = StringUtils.defaultIfBlank(shotSnapshot.getVideoPrompt(), shotSnapshot.getPrompt());
            if (StringUtils.isBlank(prompt) && renderTask != null) {
                prompt = renderTask.getPrompt();
            }
            take.setPromptSnapshot(prompt);

            String negPrompt = shotSnapshot.getNegativePrompt();
            if (StringUtils.isBlank(negPrompt) && renderTask != null) {
                negPrompt = renderTask.getNegativePrompt();
            }
            take.setNegativePromptSnapshot(negPrompt);

            take.setFirstFrameUrl(shotSnapshot.getPreviewImageUrl());
            take.setEndFrameUrl(shotSnapshot.getEndFrameImageUrl());
            take.setRefImagesJson(shotSnapshot.getRefImagesJson());
            take.setRefAudiosJson(shotSnapshot.getRefAudiosJson());
            take.setRequestSnapshotJson(serializeCleanRequestSnapshot(requestSnapshot));

            try {
                videoTakeMapper.insert(take);
                log.info("[ShotVideoTakeService] 成功创建视频候选版本: shotId={}, takeId={}, takeNo={}",
                        shotId, take.getId(), take.getTakeNo());
                break;
            } catch (DuplicateKeyException e) {
                if (i == maxRetries) {
                    log.error("[ShotVideoTakeService] 创建候选版本唯一键冲突重试耗尽: shotId={}, error={}", shotId, e.getMessage(), e);
                    throw e;
                }
                log.warn("[ShotVideoTakeService] 分配 takeNo 发生冲突，准备第 {} 次重试...", i + 1);
            }
        }

        // 原子判断并设置当前视频，避免旧任务完成时覆盖新任务状态。
        int promoted = StringUtils.isNotBlank(taskId)
                ? shotMapper.promoteVideoTakeIfLatest(shotId, taskId, take.getId(), take.getVideoUrl())
                : 0;
        if (promoted > 0) {
            log.info("[ShotVideoTakeService] 最新任务视频自动生效为当前视频: shotId={}, takeId={}, videoUrl={}",
                    shotId, take.getId(), take.getVideoUrl());
        } else {
            log.info("[ShotVideoTakeService] 较旧任务完成，视频已存入历史 Take 但不覆盖当前分镜视频: shotId={}, taskId={}",
                    shotId, taskId);
        }

        return take;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShotVideoTakeSelectVO selectTake(Long shotId, Long takeId) {
        if (shotId == null || takeId == null) {
            throw new BizException(400, "分镜ID与视频版本ID均不能为空");
        }

        DramaShot shot = shotMapper.selectById(shotId);
        if (shot == null) {
            throw new BizException(404, "分镜不存在: " + shotId);
        }

        DramaShotVideoTake take = videoTakeMapper.selectById(takeId);
        if (take == null) {
            throw new BizException(404, "指定的视频版本不存在: " + takeId);
        }

        if (!Objects.equals(take.getShotId(), shotId)) {
            throw new BizException(400, "指定的视频版本不属于当前分镜");
        }

        if (!"AVAILABLE".equalsIgnoreCase(take.getStatus())) {
            throw new BizException(400, "指定的视频版本处于不可用状态: " + take.getStatus());
        }

        if (StringUtils.isBlank(take.getVideoUrl())) {
            throw new BizException(400, "指定的视频版本缺少有效播放地址");
        }

        // 校验 MinIO 存储中对象文件是否仍然存在
        if (!validateMinioVideoExists(take.getVideoUrl())) {
            take.setStatus("UNAVAILABLE");
            videoTakeMapper.updateById(take);
            throw new BizException(400, "视频文件在存储服务中已不存在，无法选用该版本");
        }

        Long previousTakeId = shot.getCurrentVideoTakeId();

        // 幂等判断：若已经是当前选中的 Take，直接返回 changed=false
        if (Objects.equals(previousTakeId, take.getId())
                && Objects.equals(shot.getVideoUrl(), take.getVideoUrl())
                && "SUCCESS".equalsIgnoreCase(shot.getRenderStatus())) {
            return ShotVideoTakeSelectVO.builder()
                    .shotId(shotId)
                    .previousTakeId(previousTakeId)
                    .currentTakeId(take.getId())
                    .videoUrl(take.getVideoUrl())
                    .changed(false)
                    .build();
        }

        // 切换分镜当前视频指针与兼容投影 URL
        shot.setCurrentVideoTakeId(take.getId());
        shot.setVideoUrl(take.getVideoUrl());
        shot.setRenderStatus("SUCCESS");

        // 切换视频版本后，旧当前视频派生的尾帧缓存必须失效
        shot.setLastFrameUrl(null);
        shot.setLastFrameSourceVideoUrl(null);
        shot.setLastFrameSourceTakeId(null);
        shotMapper.updateById(shot);

        log.info("[ShotVideoTakeService] 创作者手动重选历史版本成功: shotId={}, previousTakeId={}, currentTakeId={}, videoUrl={}",
                shotId, previousTakeId, take.getId(), take.getVideoUrl());

        return ShotVideoTakeSelectVO.builder()
                .shotId(shotId)
                .previousTakeId(previousTakeId)
                .currentTakeId(take.getId())
                .videoUrl(take.getVideoUrl())
                .changed(true)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTake(Long shotId, Long takeId) {
        if (shotId == null || takeId == null) {
            throw new BizException(400, "分镜ID与视频版本ID均不能为空");
        }
        DramaShot shot = shotMapper.selectById(shotId);
        DramaShotVideoTake take = videoTakeMapper.selectById(takeId);
        if (shot == null || take == null || !Objects.equals(take.getShotId(), shotId)) {
            throw new BizException(404, "指定分镜的视频版本不存在");
        }

        String objectKey = ownedMinioObjectKey(take.getVideoUrl());
        if (objectKey == null) {
            throw new BizException(400, "该视频不属于当前 MinIO 存储桶，无法同步删除文件");
        }
        long otherTakes = videoTakeMapper.selectCount(new LambdaQueryWrapper<DramaShotVideoTake>()
                .eq(DramaShotVideoTake::getVideoUrl, take.getVideoUrl())
                .ne(DramaShotVideoTake::getId, takeId));
        long otherShots = shotMapper.selectCount(new LambdaQueryWrapper<DramaShot>()
                .eq(DramaShot::getVideoUrl, take.getVideoUrl())
                .ne(DramaShot::getId, shotId));
        long activeProcesses = mediaProcessTaskMapper.selectCount(new LambdaQueryWrapper<MediaProcessTask>()
                .eq(MediaProcessTask::getSourceVideoUrl, take.getVideoUrl())
                .in(MediaProcessTask::getStatus, "QUEUED", "PROCESSING"));
        if (otherTakes > 0 || otherShots > 0
                || activeProcesses > 0
                || (!Objects.equals(shot.getCurrentVideoTakeId(), takeId)
                && Objects.equals(shot.getVideoUrl(), take.getVideoUrl()))) {
            throw new BizException(409, "该视频仍被其他分镜、版本或正在执行的任务引用，无法删除文件");
        }

        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucketName()).object(objectKey).build());
        } catch (Exception e) {
            log.error("[ShotVideoTakeService] 删除 MinIO 视频失败: takeId={}, objectKey={}", takeId, objectKey, e);
            throw new BizException(500, "删除 MinIO 视频文件失败，历史记录已保留");
        }

        if (Objects.equals(shot.getCurrentVideoTakeId(), takeId)) {
            shotMapper.update(null, new LambdaUpdateWrapper<DramaShot>()
                    .eq(DramaShot::getId, shotId)
                    .set(DramaShot::getCurrentVideoTakeId, null)
                    .set(DramaShot::getVideoUrl, null)
                    .set(DramaShot::getRenderStatus, "INIT")
                    .set(DramaShot::getLastFrameUrl, null)
                    .set(DramaShot::getLastFrameSourceVideoUrl, null)
                    .set(DramaShot::getLastFrameSourceTakeId, null));
        }
        videoTakeMapper.deleteById(takeId);
        mediaProcessTaskMapper.update(null, new LambdaUpdateWrapper<MediaProcessTask>()
                .eq(MediaProcessTask::getOutputVideoUrl, take.getVideoUrl())
                .set(MediaProcessTask::getOutputVideoUrl, null)
                .set(MediaProcessTask::getCurrentNode, "视频文件已删除"));
    }

    private String ownedMinioObjectKey(String videoUrl) {
        if (StringUtils.isBlank(videoUrl)) return null;
        try {
            URI videoUri = URI.create(videoUrl);
            URI internal = URI.create(minioProperties.getEndpoint());
            URI external = URI.create(minioProperties.getExternalEndpoint());
            URI endpoint = sameOrigin(videoUri, internal) ? internal
                    : sameOrigin(videoUri, external) ? external : null;
            if (endpoint == null) return null;
            String basePath = StringUtils.removeEnd(StringUtils.defaultString(endpoint.getRawPath()), "/");
            String bucketPath = basePath + "/" + minioProperties.getBucketName() + "/";
            String path = videoUri.getRawPath();
            if (path == null || !path.startsWith(bucketPath)) return null;
            String key = URLDecoder.decode(path.substring(bucketPath.length()), StandardCharsets.UTF_8);
            if (StringUtils.isBlank(key) || key.startsWith("/") || key.contains("..") || key.contains("\\")) return null;
            return key;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean sameOrigin(URI left, URI right) {
        return Objects.equals(left.getScheme(), right.getScheme())
                && Objects.equals(left.getRawAuthority(), right.getRawAuthority());
    }

    @Override
    public int allocateNextTakeNo(Long shotId) {
        Integer maxTakeNo = videoTakeMapper.selectMaxTakeNoByShotId(shotId);
        return (maxTakeNo != null ? maxTakeNo : 0) + 1;
    }

    @Override
    public boolean shouldAutoSelect(Long shotId, String taskId) {
        if (shotId == null || StringUtils.isBlank(taskId)) {
            return false;
        }
        DramaShot shot = shotMapper.selectById(shotId);
        return shot != null && Objects.equals(shot.getLatestTaskId(), taskId);
    }

    private boolean validateMinioVideoExists(String videoUrl) {
        if (StringUtils.isBlank(videoUrl)) {
            return false;
        }
        String bucket = minioProperties.getBucketName();
        String bucketToken = "/" + bucket + "/";
        int bucketIndex = videoUrl.indexOf(bucketToken);
        if (bucketIndex < 0) {
            // 非本 MinIO 标准桶路径，跳过物理校验
            return true;
        }

        String rawObjectKey = videoUrl.substring(bucketIndex + bucketToken.length());
        if (rawObjectKey.contains("?")) {
            rawObjectKey = rawObjectKey.substring(0, rawObjectKey.indexOf("?"));
        }

        String objectKey;
        try {
            objectKey = URLDecoder.decode(rawObjectKey, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return false;
        }

        if (StringUtils.isBlank(objectKey) || objectKey.contains("..") || objectKey.startsWith("/")) {
            return false;
        }

        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equalsIgnoreCase(e.errorResponse().code()) || e.response().code() == 404) {
                log.warn("[ShotVideoTakeService] MinIO 视频对象不存在: bucket={}, objectKey={}", bucket, objectKey);
                return false;
            }
            log.warn("[ShotVideoTakeService] MinIO 校验视频异常响应: {}", e.getMessage());
            return true;
        } catch (Exception e) {
            log.warn("[ShotVideoTakeService] MinIO 校验视频未知异常: {}", e.getMessage());
            return true;
        }
    }

    private String serializeCleanRequestSnapshot(DramaShotRenderRequestDTO requestDTO) {
        if (requestDTO == null) {
            return null;
        }
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("providerId", requestDTO.getProviderId());
            map.put("workflowTemplateId", requestDTO.getWorkflowTemplateId());
            map.put("seed", requestDTO.getSeed());
            map.put("size", requestDTO.getSize());
            map.put("taskId", requestDTO.getTaskId());
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("[ShotVideoTakeService] 序列化请求参数快照异常: {}", e.getMessage());
            return null;
        }
    }
}
