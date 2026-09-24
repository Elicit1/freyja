package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.config.MinioProperties;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.ShotVideoTakeVO;
import com.astra.freyja.dto.render.RenderTaskVO;
import com.astra.freyja.dto.video.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.astra.freyja.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 视频后处理任务服务实现 (视频超分、补帧流水线调度)。
 */
@Slf4j
@Service
public class VideoProcessingServiceImpl implements VideoProcessingService {

    private final MediaProcessTaskMapper taskMapper;
    private final RenderTaskService renderTaskService;
    private final AiImageApiService aiImageApiService;
    private final AiProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final VideoFrameExtractService videoFrameExtractService;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper;
    private final Executor renderAsyncExecutor;
    private final VideoProcessSourceResolver sourceResolver;
    private final VideoProcessingModelResolver modelResolver;
    private final DramaShotMapper shotMapper;
    private final DramaShotVideoTakeMapper videoTakeMapper;
    private final ShotVideoTakeService shotVideoTakeService;
    private final DramaMapper dramaMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaSceneMapper sceneMapper;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public VideoProcessingServiceImpl(
            MediaProcessTaskMapper taskMapper,
            RenderTaskService renderTaskService,
            AiImageApiService aiImageApiService,
            AiProviderMapper providerMapper,
            AiModelMapper modelMapper,
            VideoFrameExtractService videoFrameExtractService,
            MinioClient minioClient,
            MinioProperties minioProperties,
            CryptoUtil cryptoUtil,
            ObjectMapper objectMapper,
            @Qualifier("renderAsyncExecutor") Executor renderAsyncExecutor,
            VideoProcessSourceResolver sourceResolver,
            VideoProcessingModelResolver modelResolver,
            DramaShotMapper shotMapper,
            DramaShotVideoTakeMapper videoTakeMapper,
            ShotVideoTakeService shotVideoTakeService,
            DramaMapper dramaMapper,
            DramaEpisodeMapper episodeMapper,
            DramaSceneMapper sceneMapper) {
        this.taskMapper = taskMapper;
        this.renderTaskService = renderTaskService;
        this.aiImageApiService = aiImageApiService;
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.videoFrameExtractService = videoFrameExtractService;
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        this.cryptoUtil = cryptoUtil;
        this.objectMapper = objectMapper;
        this.renderAsyncExecutor = renderAsyncExecutor;
        this.sourceResolver = sourceResolver;
        this.modelResolver = modelResolver;
        this.shotMapper = shotMapper;
        this.videoTakeMapper = videoTakeMapper;
        this.shotVideoTakeService = shotVideoTakeService;
        this.dramaMapper = dramaMapper;
        this.episodeMapper = episodeMapper;
        this.sceneMapper = sceneMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VideoProcessResultVO submitTask(VideoProcessSubmitDTO dto) {
        if (dto == null) {
            throw new BizException(400, "提交参数不能为空");
        }
        if (StringUtils.isBlank(dto.getOperation())) {
            throw new BizException(400, "处理操作类型 (operation) 不能为空");
        }

        String op = dto.getOperation().trim().toUpperCase();
        boolean isUpscale = "VIDEO_UPSCALE".equals(op);
        boolean isInterpolation = "FRAME_INTERPOLATION".equals(op);
        if (!isUpscale && !isInterpolation) {
            throw new BizException(400, "不支持的操作类型: " + op + " (仅支持 VIDEO_UPSCALE 与 FRAME_INTERPOLATION)");
        }

        // 1. 解析来源 (根据 sourceType 校验并提取可信不可变 videoUrl 快照与上下文)
        ResolvedVideoProcessSource resolvedSource = sourceResolver.resolve(dto);
        final String resolvedVideoUrl = resolvedSource.getVideoUrl();

        // 2. 解析模型中心模型与安全受控参数
        ResolvedVideoProcessingModel resolvedModel = modelResolver.resolve(
                dto.getProviderId(),
                dto.getModelId(),
                dto.getModelCode(),
                op,
                dto.getScale(),
                dto.getMultiplier(),
                dto.getCrf(),
                dto.getSourceFps(),
                dto.getTargetFps(),
                dto.getClearCacheFrames(),
                dto.getBatchSize()
        );

        // 3. 解析 AI 提供商
        AiProvider provider = aiImageApiService.resolveProvider(resolvedModel.getModel().getProviderId());

        int preserveAudio = (dto.getPreserveAudio() != null && !dto.getPreserveAudio()) ? 0 : 1;

        // 4. 生成业务 Task ID
        String taskId = "VP_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000, 9999);

        // 5. 持久化 MediaProcessTask
        MediaProcessTask task = new MediaProcessTask();
        task.setTaskId(taskId);
        task.setOperation(op);
        task.setSourceType(resolvedSource.getSourceType());
        task.setSourceVideoUrl(resolvedVideoUrl);
        task.setSourceDramaId(resolvedSource.getDramaId());
        task.setSourceEpisodeId(resolvedSource.getEpisodeId());
        task.setSourceSceneId(resolvedSource.getSceneId());
        task.setSourceShotId(resolvedSource.getShotId());
        task.setSourceVideoTakeId(resolvedSource.getVideoTakeId());
        task.setProviderId(provider.getId());
        task.setProviderName(provider.getProviderName());
        task.setModelId(resolvedModel.getModelId());
        task.setModelCode(resolvedModel.getModelCode());
        task.setStatus("QUEUED");
        task.setProgress(0);
        task.setCurrentNode("排队等待调度");
        task.setSourceFps(resolvedModel.getSourceFps());
        task.setTargetFps(resolvedModel.getTargetFps());
        task.setScale(resolvedModel.getScale());
        task.setMultiplier(resolvedModel.getMultiplier());
        task.setCrf(resolvedModel.getCrf());
        task.setPreserveAudio(preserveAudio);
        task.setClearCacheFrames(resolvedModel.getClearCacheFrames());
        task.setSubmitTime(LocalDateTime.now());

        boolean autoSave = dto.getAutoSaveToShot() == null || dto.getAutoSaveToShot();
        boolean setAsCurrent = dto.getSetAsCurrent() == null || dto.getSetAsCurrent();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("autoSaveToShot", autoSave);
            meta.put("setAsCurrent", setAsCurrent);
            task.setRemark(objectMapper.writeValueAsString(meta));
        } catch (Exception ignored) {}

        taskMapper.insert(task);

        // 6. 联动创建 RenderTask 注册至实时渲染中心与 WebSocket 广播
        try {
            RenderTaskVO renderVO = new RenderTaskVO();
            renderVO.setTaskId(taskId);
            renderVO.setTaskType(op);
            renderVO.setDramaId(resolvedSource.getDramaId());
            renderVO.setEpisodeId(resolvedSource.getEpisodeId());
            renderVO.setSceneId(resolvedSource.getSceneId());
            renderVO.setShotId(resolvedSource.getShotId());

            String taskDesc;
            if (isUpscale) {
                taskDesc = "视频 " + resolvedModel.getScale() + "x 超分 (" + resolvedModel.getModelName() + ")";
            } else {
                taskDesc = "视频补帧 (" + resolvedModel.getSourceFps() + "→" + resolvedModel.getTargetFps() + " FPS, " + resolvedModel.getModelName() + ")";
            }

            if (resolvedSource.getShotNo() != null) {
                String shotTag = "S" + String.format("%02d", resolvedSource.getShotNo());
                if (StringUtils.isNotBlank(resolvedSource.getShotName())) {
                    shotTag += " " + resolvedSource.getShotName();
                }
                renderVO.setTaskName("[视频后处理][" + shotTag + "] " + taskDesc);
            } else {
                renderVO.setTaskName("[视频后处理] " + taskDesc);
            }

            renderVO.setProviderId(provider.getId());
            renderVO.setProviderName(provider.getProviderName());
            renderVO.setModelCode(resolvedModel.getModelCode());
            renderVO.setStatus("QUEUED");
            renderVO.setProgress(0);
            renderVO.setCurrentNode("排队中");
            renderVO.setSubmitTime(task.getSubmitTime());

            RenderTaskVO createdRenderVO = renderTaskService.createTask(renderVO);
            if (createdRenderVO != null && createdRenderVO.getId() != null) {
                task.setRenderTaskId(createdRenderVO.getId());
                taskMapper.updateById(task);
            }
        } catch (Exception e) {
            log.warn("[VideoProcessing] 联动向 RenderTask 注册失败 (不阻断主业务): {}", e.getMessage());
        }

        // 7. 提交线程池异步执行处理 (只使用已固化的 URL 快照与模型 extraBody)
        final String finalModelCode = resolvedModel.getModelCode();
        final Map<String, Object> finalExtraBody = resolvedModel.getExtraBody();

        renderAsyncExecutor.execute(() -> executeVideoProcessAsync(
                task.getId(),
                taskId,
                op,
                resolvedVideoUrl,
                provider,
                finalModelCode,
                finalExtraBody
        ));

        VideoProcessResultVO vo = convertToVO(task);
        vo.setSourceDramaTitle(resolvedSource.getDramaTitle());
        vo.setSourceEpisodeTitle(resolvedSource.getEpisodeTitle());
        vo.setSourceSceneName(resolvedSource.getSceneName());
        vo.setSourceShotNo(resolvedSource.getShotNo());
        vo.setSourceShotName(resolvedSource.getShotName());
        return vo;
    }

    private void executeVideoProcessAsync(
            Long entityId,
            String taskId,
            String operation,
            String sourceVideoUrl,
            AiProvider provider,
            String modelCode,
            Map<String, Object> extraBody) {

        long startMs = System.currentTimeMillis();
        boolean isUpscale = "VIDEO_UPSCALE".equals(operation);
        log.info("[VideoProcessing] 开始异步执行视频处理任务: taskId={}, op={}, model={}", taskId, operation, modelCode);

        // 更新状态为 PROCESSING
        updateTaskProgress(entityId, taskId, "PROCESSING", 10, "正在连接网关并提交工作流...", null, null);

        try {
            // 1. 构造发往 FastAPI /v1/videos/generations 的 Payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", modelCode);
            payload.put("prompt", isUpscale ? "video upscale" : "video frame interpolation");
            payload.put("video_url", sourceVideoUrl);
            payload.put("response_format", "url");
            payload.put("task_id", taskId);
            payload.put("extra_body", extraBody);

            String requestUrl = buildVideoEndpointUrl(provider.getBaseUrl());
            String apiKey = cryptoUtil.decrypt(provider.getApiKey());
            int timeoutSec = provider.getTimeout() != null && provider.getTimeout() > 0 ? provider.getTimeout() : 3600;

            log.info("[VideoProcessing] 正在请求网关: url={}, model={}, taskId={}", requestUrl, modelCode, taskId);
            updateTaskProgress(entityId, taskId, "PROCESSING", 30, "网关正在调度节点执行...", null, null);

            String jsonBody = objectMapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                throw new BizException(500, "FastAPI 网关返回错误 (" + resp.statusCode() + "): " + resp.body());
            }

            updateTaskProgress(entityId, taskId, "PROCESSING", 70, "执行完成，正在下载产物转存 MinIO...", null, null);

            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode dataArray = root.path("data");
            if (!dataArray.isArray() || dataArray.isEmpty()) {
                throw new BizException(500, "网关未返回任何视频产物: " + resp.body());
            }

            JsonNode firstItem = dataArray.get(0);
            byte[] videoBytes;
            if (firstItem.hasNonNull("url")) {
                String remoteUrl = firstItem.get("url").asText();
                String targetUrl = resolveRemoteUrl(remoteUrl, provider.getBaseUrl());
                log.info("[VideoProcessing] 下载远程产物视频: {}", targetUrl);
                videoBytes = downloadRemoteBytes(targetUrl);
            } else if (firstItem.hasNonNull("b64_json")) {
                videoBytes = Base64.getDecoder().decode(firstItem.get("b64_json").asText());
            } else {
                throw new BizException(500, "网关响应缺少有效的产物 url 或 b64_json");
            }

            // 2. 归档视频至 MinIO: video-processing/{taskId}/output.mp4
            updateTaskProgress(entityId, taskId, "PROCESSING", 85, "正在持久化归档至对象存储...", null, null);
            String outputVideoUrl = uploadToMinio("video-processing/" + taskId + "/output.mp4", videoBytes, "video/mp4");

            // 3. 抽取末帧/封面图并归档: video-processing/{taskId}/cover.jpg
            String coverImageUrl = null;
            try {
                byte[] frameBytes = videoFrameExtractService.extractLastFrame(videoBytes, "mp4");
                if (frameBytes != null && frameBytes.length > 0) {
                    coverImageUrl = uploadToMinio("video-processing/" + taskId + "/cover.jpg", frameBytes, "image/jpeg");
                }
            } catch (Exception e) {
                log.warn("[VideoProcessing] 抽取视频封面失败 (不影响任务主线): {}", e.getMessage());
            }

            // 4. 探测产出视频元信息 (宽高、时长)
            Integer width = null;
            Integer height = null;
            BigDecimal duration = null;
            try {
                VideoProbeInfoVO probe = videoFrameExtractService.probeVideoInfo(videoBytes, "mp4");
                if (probe != null) {
                    width = probe.getWidth();
                    height = probe.getHeight();
                    duration = probe.getDuration();
                }
            } catch (Exception e) {
                log.warn("[VideoProcessing] 探测视频元信息失败: {}", e.getMessage());
            }

            // 5. 任务标记成功
            long costMs = System.currentTimeMillis() - startMs;
            MediaProcessTask task = taskMapper.selectById(entityId);
            if (task != null) {
                task.setStatus("SUCCESS");
                task.setProgress(100);
                task.setCurrentNode("处理完成");
                task.setOutputVideoUrl(outputVideoUrl);
                task.setCoverImageUrl(coverImageUrl);
                task.setWidth(width);
                task.setHeight(height);
                task.setDuration(duration);
                task.setCostMs(costMs);
                task.setFinishTime(LocalDateTime.now());
                taskMapper.updateById(task);

                // 🌟 若来源为分镜且开启了自动保存
                if (task.getSourceShotId() != null && shouldAutoSave(task)) {
                    try {
                        VideoProcessSaveToShotDTO saveDto = new VideoProcessSaveToShotDTO();
                        saveDto.setShotId(task.getSourceShotId());
                        saveDto.setSetAsCurrent(shouldSetAsCurrent(task));
                        saveToShot(task.getTaskId(), saveDto);
                        log.info("[VideoProcessing] 任务完成已自动录入为分镜 Take: taskId={}, shotId={}", taskId, task.getSourceShotId());
                    } catch (Exception ex) {
                        log.error("[VideoProcessing] 任务完成自动录入分镜 Take 失败: taskId={}, err={}", taskId, ex.getMessage(), ex);
                    }
                }
            }

            renderTaskService.finishTask(taskId, outputVideoUrl, coverImageUrl);
            log.info("[VideoProcessing] 视频处理任务成功完成: taskId={}, costMs={}ms, outputUrl={}", taskId, costMs, outputVideoUrl);

        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startMs;
            log.error("[VideoProcessing] 视频后处理任务执行失败: taskId={}, err={}", taskId, e.getMessage(), e);

            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String errorDetail = sw.toString();

            MediaProcessTask task = taskMapper.selectById(entityId);
            if (task != null) {
                task.setStatus("FAILED");
                task.setProgress(0);
                task.setCurrentNode("执行异常失败");
                task.setErrorMessage(e.getMessage());
                task.setErrorDetail(errorDetail);
                task.setCostMs(costMs);
                task.setFinishTime(LocalDateTime.now());
                taskMapper.updateById(task);
            }

            renderTaskService.failTask(taskId, e.getMessage(), errorDetail);
        }
    }

    private void updateTaskProgress(
            Long entityId,
            String taskId,
            String status,
            int progress,
            String currentNode,
            String outputUrl,
            String coverUrl) {

        MediaProcessTask task = taskMapper.selectById(entityId);
        if (task != null) {
            task.setStatus(status);
            task.setProgress(progress);
            task.setCurrentNode(currentNode);
            if (task.getStartTime() == null && "PROCESSING".equals(status)) {
                task.setStartTime(LocalDateTime.now());
            }
            if (outputUrl != null) {
                task.setOutputVideoUrl(outputUrl);
            }
            if (coverUrl != null) {
                task.setCoverImageUrl(coverUrl);
            }
            taskMapper.updateById(task);
        }
        renderTaskService.updateProgress(taskId, progress, currentNode);
    }

    @Override
    public VideoProcessResultVO getTaskById(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            throw new BizException(400, "taskId 不能为空");
        }
        MediaProcessTask task = taskMapper.selectOne(new LambdaQueryWrapper<MediaProcessTask>()
                .eq(MediaProcessTask::getTaskId, taskId.trim())
                .last("LIMIT 1"));
        if (task == null) {
            throw new BizException(404, "指定的视频处理任务不存在: " + taskId);
        }
        return convertToVO(task);
    }

    @Override
    public Page<VideoProcessResultVO> pageTasks(VideoProcessQuery query) {
        int current = (query != null && query.getCurrent() != null && query.getCurrent() > 0) ? query.getCurrent() : 1;
        int size = (query != null && query.getSize() != null && query.getSize() > 0) ? query.getSize() : 10;

        LambdaQueryWrapper<MediaProcessTask> qw = new LambdaQueryWrapper<>();
        if (query != null) {
            if (StringUtils.isNotBlank(query.getOperation())) {
                qw.eq(MediaProcessTask::getOperation, query.getOperation().trim());
            }
            if (StringUtils.isNotBlank(query.getStatus())) {
                qw.eq(MediaProcessTask::getStatus, query.getStatus().trim());
            }
            if (StringUtils.isNotBlank(query.getKeyword())) {
                String kw = query.getKeyword().trim();
                qw.and(w -> w.like(MediaProcessTask::getTaskId, kw)
                        .or().like(MediaProcessTask::getModelCode, kw)
                        .or().like(MediaProcessTask::getProviderName, kw));
            }
            if (StringUtils.isNotBlank(query.getStartTime())) {
                qw.ge(MediaProcessTask::getSubmitTime, query.getStartTime().trim());
            }
            if (StringUtils.isNotBlank(query.getEndTime())) {
                qw.le(MediaProcessTask::getSubmitTime, query.getEndTime().trim());
            }
        }
        qw.orderByDesc(MediaProcessTask::getSubmitTime);

        Page<MediaProcessTask> page = taskMapper.selectPage(new Page<>(current, size), qw);
        Page<VideoProcessResultVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());

        if (page.getRecords().isEmpty()) {
            voPage.setRecords(Collections.emptyList());
            return voPage;
        }

        List<MediaProcessTask> records = page.getRecords();
        Set<Long> dramaIds = records.stream().map(MediaProcessTask::getSourceDramaId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> episodeIds = records.stream().map(MediaProcessTask::getSourceEpisodeId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> sceneIds = records.stream().map(MediaProcessTask::getSourceSceneId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> shotIds = records.stream().map(MediaProcessTask::getSourceShotId).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, Drama> dramaMap = dramaIds.isEmpty() ? Collections.emptyMap() :
                dramaMapper.selectBatchIds(dramaIds).stream().collect(Collectors.toMap(Drama::getId, d -> d));
        Map<Long, DramaEpisode> episodeMap = episodeIds.isEmpty() ? Collections.emptyMap() :
                episodeMapper.selectBatchIds(episodeIds).stream().collect(Collectors.toMap(DramaEpisode::getId, e -> e));
        Map<Long, DramaScene> sceneMap = sceneIds.isEmpty() ? Collections.emptyMap() :
                sceneMapper.selectBatchIds(sceneIds).stream().collect(Collectors.toMap(DramaScene::getId, s -> s));
        Map<Long, DramaShot> shotMap = shotIds.isEmpty() ? Collections.emptyMap() :
                shotMapper.selectBatchIds(shotIds).stream().collect(Collectors.toMap(DramaShot::getId, s -> s));

        List<VideoProcessResultVO> voList = records.stream().map(task -> {
            VideoProcessResultVO vo = new VideoProcessResultVO();
            BeanUtils.copyProperties(task, vo);
            vo.setId(task.getId() != null ? String.valueOf(task.getId()) : null);
            vo.setProviderId(task.getProviderId() != null ? String.valueOf(task.getProviderId()) : null);
            vo.setModelId(task.getModelId() != null ? String.valueOf(task.getModelId()) : null);
            vo.setRenderTaskId(task.getRenderTaskId() != null ? String.valueOf(task.getRenderTaskId()) : null);
            vo.setSourceDramaId(task.getSourceDramaId() != null ? String.valueOf(task.getSourceDramaId()) : null);
            vo.setSourceEpisodeId(task.getSourceEpisodeId() != null ? String.valueOf(task.getSourceEpisodeId()) : null);
            vo.setSourceSceneId(task.getSourceSceneId() != null ? String.valueOf(task.getSourceSceneId()) : null);
            vo.setSourceShotId(task.getSourceShotId() != null ? String.valueOf(task.getSourceShotId()) : null);
            vo.setSourceVideoTakeId(task.getSourceVideoTakeId() != null ? String.valueOf(task.getSourceVideoTakeId()) : null);
            vo.setPreserveAudio(task.getPreserveAudio() != null && task.getPreserveAudio() == 1);

            if (task.getSourceDramaId() != null && dramaMap.containsKey(task.getSourceDramaId())) {
                vo.setSourceDramaTitle(dramaMap.get(task.getSourceDramaId()).getTitle());
            }
            if (task.getSourceEpisodeId() != null && episodeMap.containsKey(task.getSourceEpisodeId())) {
                vo.setSourceEpisodeTitle(episodeMap.get(task.getSourceEpisodeId()).getTitle());
            }
            if (task.getSourceSceneId() != null && sceneMap.containsKey(task.getSourceSceneId())) {
                vo.setSourceSceneName(sceneMap.get(task.getSourceSceneId()).getName());
            }
            if (task.getSourceShotId() != null && shotMap.containsKey(task.getSourceShotId())) {
                DramaShot shot = shotMap.get(task.getSourceShotId());
                vo.setSourceShotNo(shot.getShotNo());
                vo.setSourceShotName(shot.getShotName());
            }
            return vo;
        }).toList();

        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelTask(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            throw new BizException(400, "taskId 不能为空");
        }
        MediaProcessTask task = taskMapper.selectOne(new LambdaQueryWrapper<MediaProcessTask>()
                .eq(MediaProcessTask::getTaskId, taskId.trim())
                .last("LIMIT 1"));
        if (task == null) {
            throw new BizException(404, "任务不存在: " + taskId);
        }

        // 调用网关中断
        try {
            aiImageApiService.cancelRemoteTask(task.getProviderId(), taskId.trim(), null);
        } catch (Exception e) {
            log.warn("[VideoProcessing] 远程中断任务异常 (继续更新本地状态): {}", e.getMessage());
        }

        task.setStatus("CANCELLED");
        task.setCurrentNode("已手动取消");
        task.setFinishTime(LocalDateTime.now());
        taskMapper.updateById(task);

        renderTaskService.cancelTask(taskId);
        return true;
    }

    @Override
    public VideoProbeInfoVO probeVideo(String videoUrl) {
        if (StringUtils.isBlank(videoUrl)) {
            throw new BizException(400, "视频 URL 不能为空");
        }
        sourceResolver.validateUrlSafety(videoUrl.trim());
        byte[] bytes = downloadOrFetchVideoBytes(videoUrl.trim());
        if (bytes == null || bytes.length == 0) {
            throw new BizException(400, "未能获取视频二进制内容");
        }
        VideoProbeInfoVO info = videoFrameExtractService.probeVideoInfo(bytes, "mp4");
        if (info == null) {
            info = new VideoProbeInfoVO();
        }
        info.setSourceUrl(videoUrl);
        return info;
    }

    @Override
    public VideoProbeInfoVO probeVideoSource(VideoProcessProbeSourceDTO dto) {
        ResolvedVideoProcessSource resolved = sourceResolver.resolveForProbe(dto);
        byte[] bytes = downloadOrFetchVideoBytes(resolved.getVideoUrl());
        if (bytes == null || bytes.length == 0) {
            throw new BizException(400, "未能获取源视频内容进行元数据探测");
        }
        VideoProbeInfoVO info = videoFrameExtractService.probeVideoInfo(bytes, "mp4");
        if (info == null) {
            info = new VideoProbeInfoVO();
        }
        info.setSourceUrl(resolved.getVideoUrl());
        return info;
    }

    @Override
    public Page<ShotVideoSourceOptionVO> querySourceShots(ShotVideoSourceQuery query) {
        int current = (query != null && query.getCurrent() != null && query.getCurrent() > 0) ? query.getCurrent() : 1;
        int size = (query != null && query.getSize() != null && query.getSize() > 0) ? Math.min(query.getSize(), 50) : 20;

        LambdaQueryWrapper<DramaShot> qw = new LambdaQueryWrapper<>();
        qw.isNotNull(DramaShot::getVideoUrl)
                .ne(DramaShot::getVideoUrl, "");

        if (query != null) {
            if (query.getDramaId() != null) {
                qw.eq(DramaShot::getDramaId, query.getDramaId());
            }
            if (query.getEpisodeId() != null) {
                qw.eq(DramaShot::getEpisodeId, query.getEpisodeId());
            }
            if (query.getSceneId() != null) {
                qw.eq(DramaShot::getSceneId, query.getSceneId());
            }
            if (StringUtils.isNotBlank(query.getKeyword())) {
                String kw = query.getKeyword().trim();
                qw.and(w -> w.like(DramaShot::getShotName, kw)
                        .or().apply("CAST(shot_no AS CHAR) LIKE {0}", "%" + kw + "%"));
            }
        }

        qw.orderByAsc(DramaShot::getDramaId)
                .orderByAsc(DramaShot::getEpisodeId)
                .orderByAsc(DramaShot::getSceneId)
                .orderByAsc(DramaShot::getSortOrder)
                .orderByAsc(DramaShot::getShotNo);

        Page<DramaShot> page = shotMapper.selectPage(new Page<>(current, size), qw);
        Page<ShotVideoSourceOptionVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());

        if (page.getRecords().isEmpty()) {
            voPage.setRecords(Collections.emptyList());
            return voPage;
        }

        List<DramaShot> shots = page.getRecords();
        Set<Long> dramaIds = shots.stream().map(DramaShot::getDramaId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> episodeIds = shots.stream().map(DramaShot::getEpisodeId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> sceneIds = shots.stream().map(DramaShot::getSceneId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> shotIds = shots.stream().map(DramaShot::getId).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, Drama> dramaMap = dramaIds.isEmpty() ? Collections.emptyMap() :
                dramaMapper.selectBatchIds(dramaIds).stream().collect(Collectors.toMap(Drama::getId, d -> d));
        Map<Long, DramaEpisode> episodeMap = episodeIds.isEmpty() ? Collections.emptyMap() :
                episodeMapper.selectBatchIds(episodeIds).stream().collect(Collectors.toMap(DramaEpisode::getId, e -> e));
        Map<Long, DramaScene> sceneMap = sceneIds.isEmpty() ? Collections.emptyMap() :
                sceneMapper.selectBatchIds(sceneIds).stream().collect(Collectors.toMap(DramaScene::getId, s -> s));

        Map<Long, Integer> takeCountMap = new HashMap<>();
        if (!shotIds.isEmpty()) {
            List<DramaShotVideoTake> takes = videoTakeMapper.selectList(new LambdaQueryWrapper<DramaShotVideoTake>()
                    .in(DramaShotVideoTake::getShotId, shotIds)
                    .eq(DramaShotVideoTake::getStatus, "AVAILABLE"));
            for (DramaShotVideoTake take : takes) {
                takeCountMap.merge(take.getShotId(), 1, Integer::sum);
            }
        }

        List<ShotVideoSourceOptionVO> voList = new ArrayList<>();
        for (DramaShot s : shots) {
            Drama drama = dramaMap.get(s.getDramaId());
            DramaEpisode ep = episodeMap.get(s.getEpisodeId());
            DramaScene sc = sceneMap.get(s.getSceneId());

            ShotVideoSourceOptionVO vo = ShotVideoSourceOptionVO.builder()
                    .dramaId(s.getDramaId() != null ? String.valueOf(s.getDramaId()) : null)
                    .dramaTitle(drama != null ? drama.getTitle() : null)
                    .episodeId(s.getEpisodeId() != null ? String.valueOf(s.getEpisodeId()) : null)
                    .episodeNo(ep != null ? ep.getEpisodeNo() : null)
                    .episodeTitle(ep != null ? ep.getTitle() : null)
                    .sceneId(s.getSceneId() != null ? String.valueOf(s.getSceneId()) : null)
                    .sceneNo(sc != null ? sc.getSceneNo() : null)
                    .sceneName(sc != null ? sc.getName() : null)
                    .shotGroupId(s.getShotGroupId() != null ? String.valueOf(s.getShotGroupId()) : null)
                    .shotId(String.valueOf(s.getId()))
                    .shotNo(s.getShotNo())
                    .shotName(s.getShotName())
                    .duration(s.getDuration())
                    .generationMode(s.getGenerationMode())
                    .videoUrl(s.getVideoUrl())
                    .posterUrl(s.getPreviewImageUrl())
                    .currentVideoTakeId(s.getCurrentVideoTakeId() != null ? String.valueOf(s.getCurrentVideoTakeId()) : null)
                    .videoTakeCount(takeCountMap.getOrDefault(s.getId(), 0))
                    .videoUpdatedTime(s.getUpdateTime())
                    .build();
            voList.add(vo);
        }

        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public List<VideoProcessingModelOptionVO> listModelOptions(Long providerId, String operation) {
        return modelResolver.listModelOptions(providerId, operation);
    }

    private byte[] downloadOrFetchVideoBytes(String videoUrl) {
        String bucket = minioProperties.getBucketName();
        if (videoUrl.contains("/" + bucket + "/")) {
            try {
                int bucketIndex = videoUrl.indexOf("/" + bucket + "/");
                String objectName = videoUrl.substring(bucketIndex + bucket.length() + 2);
                if (objectName.contains("?")) {
                    objectName = objectName.substring(0, objectName.indexOf("?"));
                }
                try (InputStream stream = minioClient.getObject(
                        GetObjectArgs.builder().bucket(bucket).object(objectName).build())) {
                    return stream.readAllBytes();
                }
            } catch (Exception e) {
                log.warn("[VideoProcessing] 从 MinIO 提取视频失败，尝试 HTTP 下载: err={}", e.getMessage());
            }
        }

        if (videoUrl.startsWith("http://") || videoUrl.startsWith("https://")) {
            return downloadRemoteBytes(videoUrl);
        }
        return null;
    }

    private String uploadToMinio(String objectPath, byte[] bytes, String contentType) {
        String bucket = minioProperties.getBucketName();
        ensureBucketExists(bucket);

        try (InputStream is = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .stream(is, bytes.length, -1)
                    .contentType(contentType)
                    .build());

            String base = minioProperties.getExternalEndpoint();
            if (StringUtils.isBlank(base)) {
                base = minioProperties.getEndpoint();
            }
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return String.format("%s/%s/%s", base, bucket, objectPath);
        } catch (Exception e) {
            throw new BizException(500, "上传产物到 MinIO 失败: " + e.getMessage());
        }
    }

    private void ensureBucketExists(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            log.warn("[VideoProcessing] 确保 MinIO bucket 存在异常: {}", e.getMessage());
        }
    }

    private String buildVideoEndpointUrl(String baseUrl) {
        String clean = baseUrl.trim();
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.endsWith("/v1/videos/generations") || clean.endsWith("/videos/generations")) {
            return clean;
        }
        if (clean.endsWith("/v1")) {
            return clean + "/videos/generations";
        }
        return clean + "/v1/videos/generations";
    }

    private String resolveRemoteUrl(String remoteUrl, String providerBaseUrl) {
        if (StringUtils.isBlank(remoteUrl) || StringUtils.isBlank(providerBaseUrl)) {
            return remoteUrl;
        }
        try {
            URI imageUri = URI.create(remoteUrl);
            URI providerUri = URI.create(providerBaseUrl);
            String imageHost = imageUri.getHost();
            String providerHost = providerUri.getHost();

            if (("127.0.0.1".equals(imageHost) || "localhost".equalsIgnoreCase(imageHost))
                    && StringUtils.isNotBlank(providerHost)
                    && !"127.0.0.1".equals(providerHost)
                    && !"localhost".equalsIgnoreCase(providerHost)) {
                int port = imageUri.getPort() != -1 ? imageUri.getPort() : providerUri.getPort();
                String portPart = (port != -1 && port != 80 && port != 443) ? ":" + port : "";
                return String.format("%s://%s%s%s%s",
                        providerUri.getScheme() != null ? providerUri.getScheme() : imageUri.getScheme(),
                        providerHost,
                        portPart,
                        imageUri.getRawPath() != null ? imageUri.getRawPath() : "",
                        StringUtils.isNotBlank(imageUri.getRawQuery()) ? "?" + imageUri.getRawQuery() : "");
            }
        } catch (Exception e) {
            log.warn("[VideoProcessing] 解析校正远程产物 URL 失败，保留原始地址: {}", e.getMessage());
        }
        return remoteUrl;
    }

    private byte[] downloadRemoteBytes(String remoteUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(remoteUrl))
                    .timeout(Duration.ofSeconds(300))
                    .GET()
                    .build();
            HttpResponse<byte[]> res = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() >= 300) {
                throw new IllegalStateException("下载远程产物失败 HTTP " + res.statusCode());
            }
            return res.body();
        } catch (Exception e) {
            throw new BizException(500, "下载远程媒体资源流失败: " + e.getMessage());
        }
    }

    private VideoProcessResultVO convertToVO(MediaProcessTask task) {
        VideoProcessResultVO vo = new VideoProcessResultVO();
        BeanUtils.copyProperties(task, vo);
        vo.setId(task.getId() != null ? String.valueOf(task.getId()) : null);
        vo.setProviderId(task.getProviderId() != null ? String.valueOf(task.getProviderId()) : null);
        vo.setModelId(task.getModelId() != null ? String.valueOf(task.getModelId()) : null);
        vo.setRenderTaskId(task.getRenderTaskId() != null ? String.valueOf(task.getRenderTaskId()) : null);
        vo.setSourceDramaId(task.getSourceDramaId() != null ? String.valueOf(task.getSourceDramaId()) : null);
        vo.setSourceEpisodeId(task.getSourceEpisodeId() != null ? String.valueOf(task.getSourceEpisodeId()) : null);
        vo.setSourceSceneId(task.getSourceSceneId() != null ? String.valueOf(task.getSourceSceneId()) : null);
        vo.setSourceShotId(task.getSourceShotId() != null ? String.valueOf(task.getSourceShotId()) : null);
        vo.setSourceVideoTakeId(task.getSourceVideoTakeId() != null ? String.valueOf(task.getSourceVideoTakeId()) : null);
        vo.setPreserveAudio(task.getPreserveAudio() != null && task.getPreserveAudio() == 1);
        vo.setAutoSaveToShot(shouldAutoSave(task));
        vo.setSetAsCurrent(shouldSetAsCurrent(task));

        if (task.getSourceShotId() != null) {
            DramaShot shot = shotMapper.selectById(task.getSourceShotId());
            if (shot != null) {
                vo.setSourceShotNo(shot.getShotNo());
                vo.setSourceShotName(shot.getShotName());
            }

            DramaShotVideoTake take = videoTakeMapper.selectOne(new LambdaQueryWrapper<DramaShotVideoTake>()
                    .eq(DramaShotVideoTake::getShotId, task.getSourceShotId())
                    .eq(DramaShotVideoTake::getTaskId, task.getTaskId())
                    .last("LIMIT 1"));
            if (take != null) {
                vo.setSavedTakeId(String.valueOf(take.getId()));
                vo.setSavedTakeNo(take.getTakeNo());
                vo.setIsCurrentTake(shot != null && Objects.equals(shot.getCurrentVideoTakeId(), take.getId()));
            }
        }
        if (task.getSourceDramaId() != null) {
            Drama drama = dramaMapper.selectById(task.getSourceDramaId());
            if (drama != null) {
                vo.setSourceDramaTitle(drama.getTitle());
            }
        }
        if (task.getSourceEpisodeId() != null) {
            DramaEpisode episode = episodeMapper.selectById(task.getSourceEpisodeId());
            if (episode != null) {
                vo.setSourceEpisodeTitle(episode.getTitle());
            }
        }
        if (task.getSourceSceneId() != null) {
            DramaScene scene = sceneMapper.selectById(task.getSourceSceneId());
            if (scene != null) {
                vo.setSourceSceneName(scene.getName());
            }
        }
        return vo;
    }

    private boolean shouldAutoSave(MediaProcessTask task) {
        if (task == null || StringUtils.isBlank(task.getRemark())) {
            return true;
        }
        try {
            JsonNode node = objectMapper.readTree(task.getRemark());
            if (node.has("autoSaveToShot")) {
                return node.get("autoSaveToShot").asBoolean(true);
            }
        } catch (Exception ignored) {}
        return true;
    }

    private boolean shouldSetAsCurrent(MediaProcessTask task) {
        if (task == null || StringUtils.isBlank(task.getRemark())) {
            return true;
        }
        try {
            JsonNode node = objectMapper.readTree(task.getRemark());
            if (node.has("setAsCurrent")) {
                return node.get("setAsCurrent").asBoolean(true);
            }
        } catch (Exception ignored) {}
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShotVideoTakeVO saveToShot(String taskId, VideoProcessSaveToShotDTO dto) {
        if (StringUtils.isBlank(taskId)) {
            throw new BizException(400, "任务 ID 不能为空");
        }
        MediaProcessTask task = taskMapper.selectOne(new LambdaQueryWrapper<MediaProcessTask>()
                .eq(MediaProcessTask::getTaskId, taskId.trim())
                .last("LIMIT 1"));
        if (task == null) {
            throw new BizException(404, "指定的视频后处理任务不存在: " + taskId);
        }
        if (!"SUCCESS".equalsIgnoreCase(task.getStatus())) {
            throw new BizException(400, "该任务尚未成功完成，无法保存为分镜历史 (当前状态: " + task.getStatus() + ")");
        }
        if (StringUtils.isBlank(task.getOutputVideoUrl())) {
            throw new BizException(400, "该任务缺少有效的产物视频地址");
        }

        Long targetShotId = (dto != null && dto.getShotId() != null) ? dto.getShotId() : task.getSourceShotId();
        if (targetShotId == null) {
            throw new BizException(400, "该任务未关联源分镜，请指定目标分镜 ID");
        }

        DramaShot shot = shotMapper.selectById(targetShotId);
        if (shot == null) {
            throw new BizException(404, "目标分镜不存在: " + targetShotId);
        }

        boolean setAsCurrent = dto == null || dto.getSetAsCurrent() == null || dto.getSetAsCurrent();

        // 幂等判断：是否已经基于该后处理 taskId 录入过 Take
        DramaShotVideoTake existingTake = videoTakeMapper.selectOne(new LambdaQueryWrapper<DramaShotVideoTake>()
                .eq(DramaShotVideoTake::getShotId, targetShotId)
                .eq(DramaShotVideoTake::getTaskId, task.getTaskId())
                .last("LIMIT 1"));

        DramaShotVideoTake take;
        if (existingTake != null) {
            take = existingTake;
            log.info("[VideoProcessing] 任务产物已作为分镜 Take 存在，复用现有版本: taskId={}, shotId={}, takeId={}",
                    taskId, targetShotId, take.getId());
        } else {
            // 分配递增 Take 编号
            int nextTakeNo = shotVideoTakeService.allocateNextTakeNo(targetShotId);

            take = new DramaShotVideoTake();
            take.setDramaId(shot.getDramaId());
            take.setEpisodeId(shot.getEpisodeId());
            take.setSceneId(shot.getSceneId());
            take.setShotGroupId(shot.getShotGroupId());
            take.setShotId(targetShotId);
            take.setTakeNo(nextTakeNo);
            take.setTaskId(task.getTaskId());
            take.setSourceType(task.getOperation()); // VIDEO_UPSCALE 或 FRAME_INTERPOLATION
            take.setStatus("AVAILABLE");
            take.setVideoUrl(task.getOutputVideoUrl());
            take.setProviderId(task.getProviderId());
            take.setProviderName(task.getProviderName());
            take.setModelCode(task.getModelCode());
            take.setGenerationMode(shot.getGenerationMode());
            if (task.getWidth() != null && task.getHeight() != null) {
                take.setSize(task.getWidth() + "x" + task.getHeight());
            }
            take.setDuration(task.getDuration() != null ? task.getDuration() : shot.getDuration());
            take.setFirstFrameUrl(task.getCoverImageUrl());
            take.setPromptSnapshot(StringUtils.defaultIfBlank(shot.getVideoPrompt(), shot.getPrompt()));

            // 序列化后处理参数快照
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("operation", task.getOperation());
                params.put("modelCode", task.getModelCode());
                params.put("scale", task.getScale());
                params.put("multiplier", task.getMultiplier());
                params.put("sourceFps", task.getSourceFps());
                params.put("targetFps", task.getTargetFps());
                params.put("crf", task.getCrf());
                params.put("preserveAudio", task.getPreserveAudio());
                take.setRequestSnapshotJson(objectMapper.writeValueAsString(params));
            } catch (Exception e) {
                log.warn("[VideoProcessing] 序列化后处理任务参数快照异常: {}", e.getMessage());
            }

            videoTakeMapper.insert(take);
            log.info("[VideoProcessing] 成功将后处理产物保存为分镜新 Take: taskId={}, shotId={}, takeId={}, takeNo={}",
                    taskId, targetShotId, take.getId(), nextTakeNo);
        }

        // 若设为分镜当前生效视频 (默认 true)
        if (setAsCurrent) {
            shotVideoTakeService.selectTake(targetShotId, take.getId());
        }

        // 重新查询分镜以获取最新 currentVideoTakeId 状态
        DramaShot updatedShot = shotMapper.selectById(targetShotId);
        boolean isCurrent = updatedShot != null && Objects.equals(updatedShot.getCurrentVideoTakeId(), take.getId());

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
    }
}
