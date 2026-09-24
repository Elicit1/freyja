package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiTaskMapper;
import com.astra.freyja.dao.DramaEpisodeMapper;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dto.script.AiTaskDTO;
import com.astra.freyja.dto.script.AiTaskQueryDTO;
import com.astra.freyja.dto.script.ChapterDecomposeHistoryVO;
import com.astra.freyja.dto.script.ScriptDecomposeResultVO;
import com.astra.freyja.dto.script.ScriptDecomposeRequestDTO;
import com.astra.freyja.entity.AiTask;
import com.astra.freyja.entity.enums.AiTaskStatus;
import com.astra.freyja.entity.enums.AiTaskType;
import com.astra.freyja.service.AiTaskService;
import com.astra.freyja.service.ScriptDecomposeDraftStore;
import com.astra.freyja.service.ShotPromptEventStore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.astra.freyja.event.TaskStatusChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 任务流水线数据库存储与历史管理服务实现类。
 * 设计为高容错模式：即使数据库 ai_task 表未初始化或发生瞬态异常，也不阻断 AI 核心流水线的执行。
 */
@Slf4j
@Service
public class AiTaskServiceImpl implements AiTaskService {

    private final AiTaskMapper aiTaskMapper;
    private final ObjectMapper objectMapper;
    private final DramaMapper dramaMapper;
    private final DramaEpisodeMapper episodeMapper;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;
    @Autowired(required = false)
    private ScriptDecomposeDraftStore draftStore;
    @Autowired(required = false)
    private ShotPromptEventStore aiEvents;

    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    private void publishTaskStatusChanged(Long taskId, String status) {
        if (eventPublisher != null && taskId != null) {
            try {
                eventPublisher.publishEvent(new TaskStatusChangedEvent("AI_TASK", String.valueOf(taskId), status));
            } catch (Exception e) {
                log.warn("[AiTaskService] 发布任务状态事件失败: taskId={}, status={}, msg={}", taskId, status, e.getMessage());
            }
        }
    }

    @Autowired
    public AiTaskServiceImpl(AiTaskMapper aiTaskMapper,
                             ObjectMapper objectMapper,
                             @Autowired(required = false) DramaMapper dramaMapper,
                             @Autowired(required = false) DramaEpisodeMapper episodeMapper) {
        this.aiTaskMapper = aiTaskMapper;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.dramaMapper = dramaMapper;
        this.episodeMapper = episodeMapper;
    }

    public AiTaskServiceImpl(AiTaskMapper aiTaskMapper) {
        this(aiTaskMapper, new ObjectMapper(), null, null);
    }

    @Override
    public AiTask createTask(Long dramaId, Long episodeId, AiTaskType taskType, String targetId,
                              Long parentTaskId, String inputPayload, String modelCode, Integer maxTokens) {
        AiTask task = new AiTask();
        task.setDramaId(dramaId != null ? dramaId : 0L);
        task.setEpisodeId(episodeId);
        task.setTaskType(taskType != null ? taskType.name() : AiTaskType.SHOT_DECOMPOSE.name());
        task.setStatus(AiTaskStatus.PENDING.name());
        task.setTargetId(targetId);
        task.setParentTaskId(parentTaskId);
        task.setInputPayload(inputPayload);
        task.setModelCode(modelCode);
        task.setMaxTokens(maxTokens != null ? maxTokens : 8192);
        task.setRetryCount(0);

        if (draftStore != null && (taskType == AiTaskType.CHAPTER_DECOMPOSE
                || taskType == AiTaskType.WORKER_RETRY
                || (parentTaskId != null && draftStore.getTask(parentTaskId) != null))) {
            task.setId(IdWorker.getId());
            task.setCreateTime(LocalDateTime.now());
            draftStore.saveTask(task);
            publishTaskStatusChanged(task.getId(), task.getStatus());
            return task;
        }

        try {
            aiTaskMapper.insert(task);
            log.info("[AiTaskService] 新建 AI 任务记录: id={}, type={}, targetId={}", task.getId(), task.getTaskType(), targetId);
            publishTaskStatusChanged(task.getId(), task.getStatus());
        } catch (Exception e) {
            log.warn("[AiTaskService] 写入 AI 任务记录至数据库失败 (若 ai_task 表尚未初始化，请执行 resources/sql/ai_task_schema.sql): {}", e.getMessage());
            if (task.getId() == null) {
                task.setId(System.currentTimeMillis());
            }
        }

        return task;
    }

    @Override
    public void markRunning(Long taskId) {
        if (taskId == null) return;
        AiTask draftTask = draftStore != null ? draftStore.getTask(taskId) : null;
        if (draftTask != null) {
            draftTask.setStatus(AiTaskStatus.RUNNING.name());
            draftTask.setStartedAt(LocalDateTime.now());
            draftStore.saveTask(draftTask);
            publishTaskStatusChanged(taskId, draftTask.getStatus());
            return;
        }
        try {
            AiTask task = aiTaskMapper.selectById(taskId);
            if (task != null) {
                task.setStatus(AiTaskStatus.RUNNING.name());
                task.setStartedAt(LocalDateTime.now());
                aiTaskMapper.updateById(task);
                publishTaskStatusChanged(taskId, AiTaskStatus.RUNNING.name());
            }
        } catch (Exception e) {
            log.debug("[AiTaskService] 更新任务状态为 RUNNING 失败: taskId={}, msg={}", taskId, e.getMessage());
        }
    }

    @Override
    public void markSuccess(Long taskId, String outputPayload, Integer consumedTokens) {
        if (taskId == null) return;
        AiTask draftTask = draftStore != null ? draftStore.getTask(taskId) : null;
        if (draftTask != null) {
            draftTask.setStatus(AiTaskStatus.SUCCESS.name());
            draftTask.setOutputPayload(outputPayload);
            draftTask.setConsumedTokens(consumedTokens != null ? consumedTokens : 0);
            draftTask.setFinishedAt(LocalDateTime.now());
            draftTask.setErrorMessage(null);
            draftStore.saveTask(draftTask);
            publishTaskStatusChanged(taskId, draftTask.getStatus());
            return;
        }
        try {
            AiTask task = aiTaskMapper.selectById(taskId);
            if (task != null) {
                task.setStatus(AiTaskStatus.SUCCESS.name());
                task.setOutputPayload(outputPayload);
                task.setConsumedTokens(consumedTokens != null ? consumedTokens : 0);
                task.setFinishedAt(LocalDateTime.now());
                task.setErrorMessage(null);
                aiTaskMapper.updateById(task);
                log.info("[AiTaskService] 任务执行成功: id={}, type={}", taskId, task.getTaskType());
                publishTaskStatusChanged(taskId, AiTaskStatus.SUCCESS.name());
            }
        } catch (Exception e) {
            log.debug("[AiTaskService] 更新任务状态为 SUCCESS 失败: taskId={}, msg={}", taskId, e.getMessage());
        }
    }

    @Override
    public void markFailed(Long taskId, String errorMessage) {
        if (taskId == null) return;
        AiTask draftTask = draftStore != null ? draftStore.getTask(taskId) : null;
        if (draftTask != null) {
            draftTask.setStatus(AiTaskStatus.FAILED.name());
            draftTask.setErrorMessage(errorMessage);
            draftTask.setFinishedAt(LocalDateTime.now());
            draftStore.saveTask(draftTask);
            publishTaskStatusChanged(taskId, draftTask.getStatus());
            return;
        }
        try {
            AiTask task = aiTaskMapper.selectById(taskId);
            if (task != null) {
                task.setStatus(AiTaskStatus.FAILED.name());
                task.setErrorMessage(errorMessage);
                task.setFinishedAt(LocalDateTime.now());
                aiTaskMapper.updateById(task);
                log.warn("[AiTaskService] 任务标记失败: id={}, error={}", taskId, errorMessage);
                publishTaskStatusChanged(taskId, AiTaskStatus.FAILED.name());
            }
        } catch (Exception e) {
            log.debug("[AiTaskService] 更新任务状态为 FAILED 失败: taskId={}, msg={}", taskId, e.getMessage());
        }
    }

    @Override
    public AiTask retryTask(Long taskId) {
        if (taskId == null) {
            throw new BizException("任务 ID 不能为空");
        }
        AiTask draftTask = draftStore != null ? draftStore.getTask(taskId) : null;
        if (draftTask != null) {
            throw new BizException("拆解任务请使用指定分段的 Worker 重试功能");
        }
        try {
            AiTask task = aiTaskMapper.selectById(taskId);
            if (task != null) {
                task.setStatus(AiTaskStatus.RETRYING.name());
                task.setRetryCount((task.getRetryCount() != null ? task.getRetryCount() : 0) + 1);
                task.setStartedAt(LocalDateTime.now());
                task.setErrorMessage(null);
                aiTaskMapper.updateById(task);
                log.info("[AiTaskService] 触发单任务重试: id={}, retryCount={}", taskId, task.getRetryCount());
                publishTaskStatusChanged(taskId, AiTaskStatus.RETRYING.name());
                return task;
            }
        } catch (Exception e) {
            log.warn("[AiTaskService] 重试查询任务失败: taskId={}, msg={}", taskId, e.getMessage());
        }

        AiTask fallback = new AiTask();
        fallback.setId(taskId);
        fallback.setStatus(AiTaskStatus.RETRYING.name());
        fallback.setRetryCount(1);
        return fallback;
    }

    @Override
    public List<AiTaskDTO> listTasks(AiTaskQueryDTO query) {
        List<AiTaskDTO> result = new ArrayList<>();
        if (draftStore != null) {
            result.addAll(draftStore.listTasks().stream()
                    .filter(task -> matches(query, task))
                    .map(this::toDTO).toList());
        }
        try {
            LambdaQueryWrapper<AiTask> qw = new LambdaQueryWrapper<>();
            if (query != null) {
                qw.eq(query.getDramaId() != null, AiTask::getDramaId, query.getDramaId())
                  .eq(query.getEpisodeId() != null, AiTask::getEpisodeId, query.getEpisodeId())
                  .eq(StringUtils.isNotBlank(query.getTaskType()), AiTask::getTaskType, query.getTaskType())
                  .eq(StringUtils.isNotBlank(query.getTargetId()), AiTask::getTargetId, query.getTargetId());
                if (!CollectionUtils.isEmpty(query.getStatuses())) {
                    qw.in(AiTask::getStatus, query.getStatuses());
                } else if (StringUtils.isNotBlank(query.getStatus())) {
                    qw.eq(AiTask::getStatus, query.getStatus());
                }
            }
            qw.orderByDesc(AiTask::getId);
            result.addAll(aiTaskMapper.selectList(qw).stream().map(this::toDTO).toList());
        } catch (Exception e) {
            log.warn("[AiTaskService] 查询任务列表失败 (可能 ai_task 表尚未初始化): {}", e.getMessage());
        }
        result.sort((left, right) -> Long.compare(right.getId(), left.getId()));
        return result;
    }

    private boolean matches(AiTaskQueryDTO query, AiTask task) {
        if (query == null) return true;
        return (query.getDramaId() == null || query.getDramaId().equals(task.getDramaId()))
                && (query.getEpisodeId() == null || query.getEpisodeId().equals(task.getEpisodeId()))
                && (StringUtils.isBlank(query.getTaskType()) || query.getTaskType().equals(task.getTaskType()))
                && (StringUtils.isBlank(query.getTargetId()) || query.getTargetId().equals(task.getTargetId()))
                && (query.getStatuses() == null || query.getStatuses().isEmpty() || query.getStatuses().contains(task.getStatus()))
                && (StringUtils.isBlank(query.getStatus()) || query.getStatus().equals(task.getStatus()));
    }

    @Override
    public AiTaskDTO getTaskById(Long taskId) {
        if (taskId == null) return null;
        AiTask draftTask = draftStore != null ? draftStore.getTask(taskId) : null;
        if (draftTask != null) return toDTO(draftTask);
        try {
            AiTask task = aiTaskMapper.selectById(taskId);
            return task != null ? toDTO(task) : null;
        } catch (Exception e) {
            log.warn("[AiTaskService] 获取任务详情失败: taskId={}, msg={}", taskId, e.getMessage());
            return null;
        }
    }

    @Override
    public void markPartialSuccess(Long taskId, String outputPayload, String errorMessage) {
        if (taskId == null) return;
        AiTask draftTask = draftStore != null ? draftStore.getTask(taskId) : null;
        if (draftTask != null) {
            draftTask.setStatus(AiTaskStatus.PARTIAL_SUCCESS.name());
            draftTask.setOutputPayload(outputPayload);
            draftTask.setErrorMessage(errorMessage);
            draftTask.setFinishedAt(LocalDateTime.now());
            draftStore.saveTask(draftTask);
            publishTaskStatusChanged(taskId, draftTask.getStatus());
            return;
        }
        try {
            AiTask task = aiTaskMapper.selectById(taskId);
            if (task != null) {
                task.setStatus(AiTaskStatus.PARTIAL_SUCCESS.name());
                task.setOutputPayload(outputPayload);
                task.setErrorMessage(errorMessage);
                task.setFinishedAt(LocalDateTime.now());
                aiTaskMapper.updateById(task);
                log.info("[AiTaskService] 任务标记为 PARTIAL_SUCCESS: id={}", taskId);
                publishTaskStatusChanged(taskId, AiTaskStatus.PARTIAL_SUCCESS.name());
            }
        } catch (Exception e) {
            log.warn("[AiTaskService] 更新任务为 PARTIAL_SUCCESS 失败: {}", e.getMessage());
        }
    }

    @Override
    public void updateTaskPayload(Long taskId, String status, String outputPayload, String errorMessage) {
        if (taskId == null) return;
        AiTask draftTask = draftStore != null ? draftStore.getTask(taskId) : null;
        if (draftTask != null) {
            if (StringUtils.isNotBlank(status)) draftTask.setStatus(status);
            if (outputPayload != null) draftTask.setOutputPayload(outputPayload);
            draftTask.setErrorMessage(errorMessage);
            draftStore.saveTask(draftTask);
            publishTaskStatusChanged(taskId, draftTask.getStatus());
            return;
        }
        try {
            AiTask task = aiTaskMapper.selectById(taskId);
            if (task != null) {
                if (StringUtils.isNotBlank(status)) {
                    task.setStatus(status);
                }
                if (outputPayload != null) {
                    task.setOutputPayload(outputPayload);
                }
                task.setErrorMessage(errorMessage);
                aiTaskMapper.updateById(task);
                log.info("[AiTaskService] 更新任务载荷: id={}, status={}", taskId, task.getStatus());
                publishTaskStatusChanged(taskId, task.getStatus());
            }
        } catch (Exception e) {
            log.warn("[AiTaskService] 更新任务载荷失败: {}", e.getMessage());
        }
    }

    @Override
    public List<ChapterDecomposeHistoryVO> listChapterHistory(Long dramaId, Long episodeId) {
        List<ChapterDecomposeHistoryVO> historyList = new ArrayList<>();
        if (draftStore != null) {
            historyList.addAll(draftStore.listTasks().stream()
                    .filter(task -> AiTaskType.CHAPTER_DECOMPOSE.name().equals(task.getTaskType()))
                    .filter(task -> dramaId == null || dramaId <= 0 || dramaId.equals(task.getDramaId()))
                    .filter(task -> episodeId == null || episodeId <= 0 || episodeId.equals(task.getEpisodeId()))
                    .map(this::parseHistoryItem).toList());
        }
        try {
            QueryWrapper<AiTask> qw = new QueryWrapper<>();
            qw.eq("task_type", AiTaskType.CHAPTER_DECOMPOSE.name());
            if (dramaId != null && dramaId > 0) {
                qw.eq("drama_id", dramaId);
            }
            if (episodeId != null && episodeId > 0) {
                qw.eq("episode_id", episodeId);
            }
            qw.orderByDesc("id");
            if (dramaId == null || dramaId <= 0) {
                qw.last("LIMIT 50");
            }

            List<AiTask> tasks = aiTaskMapper.selectList(qw);
            if (tasks != null) for (AiTask t : tasks) historyList.add(parseHistoryItem(t));
        } catch (Exception e) {
            log.warn("[AiTaskService] 查询章节拆解历史失败: dramaId={}, episodeId={}, msg={}", dramaId, episodeId, e.getMessage());
        }
        historyList.sort((left, right) -> Long.compare(right.getTaskId(), left.getTaskId()));
        return dramaId == null && historyList.size() > 50 ? historyList.subList(0, 50) : historyList;
    }

    private ChapterDecomposeHistoryVO parseHistoryItem(AiTask task) {
        ChapterDecomposeHistoryVO.ChapterDecomposeHistoryVOBuilder builder = ChapterDecomposeHistoryVO.builder()
                .taskId(task.getId())
                .dramaId(task.getDramaId())
                .episodeId(task.getEpisodeId())
                .status(task.getStatus())
                .modelCode(task.getModelCode())
                .consumedTokens(task.getConsumedTokens())
                .errorMessage(task.getErrorMessage())
                .createTime(task.getCreateTime())
                .finishedAt(task.getFinishedAt());

        if (task.getStartedAt() != null && task.getFinishedAt() != null) {
            long seconds = java.time.Duration.between(task.getStartedAt(), task.getFinishedAt()).toSeconds();
            builder.durationSeconds((double) seconds);
        }

        // 解析 inputPayload 获取原始请求中的标题、集号等
        ScriptDecomposeRequestDTO draftRequest = draftStore != null ? draftStore.getRequest(task.getId()) : null;
        if (draftRequest != null) {
            builder.chapterTitle(draftRequest.getChapterTitle())
                    .episodeNo(draftRequest.getStartEpisodeNo());
        } else if (StringUtils.isNotBlank(task.getInputPayload())) {
            try {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(task.getInputPayload());
                if (root.has("dramaTitle") && !root.get("dramaTitle").isNull()) {
                    builder.dramaTitle(root.get("dramaTitle").asText());
                }
                if (root.has("chapterTitle") && !root.get("chapterTitle").isNull()) {
                    builder.chapterTitle(root.get("chapterTitle").asText());
                }
                if (root.has("startEpisodeNo") && !root.get("startEpisodeNo").isNull()) {
                    builder.episodeNo(root.get("startEpisodeNo").asInt());
                }
            } catch (Exception ignored) {}
        }

        // 解析 outputPayload 获取统计指标 (分段数、镜头数、碎片率等)
        ScriptDecomposeResultVO draftResult = draftStore != null ? draftStore.getResult(task.getId()) : null;
        if (draftResult != null || StringUtils.isNotBlank(task.getOutputPayload())) {
            try {
                ScriptDecomposeResultVO resultVO = draftResult != null ? draftResult
                        : objectMapper.readValue(task.getOutputPayload(), ScriptDecomposeResultVO.class);
                if (StringUtils.isNotBlank(resultVO.getDramaTitle())) {
                    builder.dramaTitle(resultVO.getDramaTitle());
                }
                int totalSegs = resultVO.getSegments() != null ? resultVO.getSegments().size() : 0;
                builder.totalSegments(totalSegs);

                int successSegs = 0;
                int failedSegs = 0;
                List<String> failedIds = new ArrayList<>();
                if (resultVO.getSegmentResults() != null) {
                    for (var sr : resultVO.getSegmentResults()) {
                        if ("SUCCESS".equalsIgnoreCase(sr.getStatus())) {
                            successSegs++;
                        } else if ("FAILED".equalsIgnoreCase(sr.getStatus())) {
                            failedSegs++;
                            failedIds.add(sr.getSegmentId());
                        }
                    }
                } else if (totalSegs > 0 && "SUCCESS".equalsIgnoreCase(task.getStatus())) {
                    successSegs = totalSegs;
                }
                builder.successSegments(successSegs);
                builder.failedSegments(failedSegs);
                builder.failedSegmentIds(failedIds);

                int totalShots = 0;
                if (resultVO.getEpisodes() != null) {
                    for (var ep : resultVO.getEpisodes()) {
                        if (ep.getScenes() != null) {
                            for (var sc : ep.getScenes()) {
                                if (sc.getShots() != null) {
                                    totalShots += sc.getShots().size();
                                }
                            }
                        }
                    }
                }
                builder.totalShots(totalShots);

                if (resultVO.getFragmentationStats() != null) {
                    builder.averageDuration(resultVO.getFragmentationStats().getAverageDuration());
                }
            } catch (Exception e) {
                log.debug("[AiTaskService] 解析任务 {} outputPayload 失败: {}", task.getId(), e.getMessage());
            }
        }

        // 数据库兜底填充短剧名称
        ChapterDecomposeHistoryVO historyVO = builder.build();
        if (StringUtils.isBlank(historyVO.getDramaTitle()) && dramaMapper != null && task.getDramaId() != null && task.getDramaId() > 0) {
            try {
                var drama = dramaMapper.selectById(task.getDramaId());
                if (drama != null) historyVO.setDramaTitle(drama.getTitle());
            } catch (Exception ignored) {}
        }

        return historyVO;
    }

    @Override
    public ScriptDecomposeResultVO getDecomposePreview(Long taskId) {
        if (taskId == null) {
            throw new BizException("任务 ID 不能为空");
        }
        AiTask task = draftStore != null ? draftStore.getTask(taskId) : null;
        if (task == null) task = aiTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(404, "拆解任务记录不存在: " + taskId);
        }
        ScriptDecomposeResultVO draftResult = draftStore != null ? draftStore.getResult(taskId) : null;
        if (draftResult != null) {
            draftResult.setTaskId(taskId);
            draftResult.setStatus(task.getStatus());
            return draftResult;
        }
        if (StringUtils.isBlank(task.getOutputPayload())) {
            throw new BizException("该任务尚未生成预览大纲或输出数据为空");
        }
        try {
            ScriptDecomposeResultVO resultVO = objectMapper.readValue(task.getOutputPayload(), ScriptDecomposeResultVO.class);
            resultVO.setTaskId(task.getId());
            resultVO.setStatus(task.getStatus());
            return resultVO;
        } catch (Exception e) {
            log.error("[AiTaskService] 反序列化任务 {} 的预览数据失败: {}", taskId, e.getMessage(), e);
            throw new BizException("解析任务预览大纲失败: " + e.getMessage());
        }
    }

    @Override
    public void bindDrama(Long taskId, Long dramaId) {
        if (taskId == null || dramaId == null) return;
        AiTask draftTask = draftStore != null ? draftStore.getTask(taskId) : null;
        if (draftTask != null) {
            draftTask.setDramaId(dramaId);
            draftStore.saveTask(draftTask);
            return;
        }
        try {
            AiTask task = aiTaskMapper.selectById(taskId);
            if (task != null) {
                task.setDramaId(dramaId);
                aiTaskMapper.updateById(task);
                log.info("[AiTaskService] 成功将拆解任务 taskId={} 绑定至短剧 dramaId={}", taskId, dramaId);
            }
            // 同时更新关联子任务的 dramaId
            AiTask updateSub = new AiTask();
            updateSub.setDramaId(dramaId);
            QueryWrapper<AiTask> subQw = new QueryWrapper<>();
            subQw.eq("parent_task_id", taskId);
            aiTaskMapper.update(updateSub, subQw);
        } catch (Exception e) {
            log.warn("[AiTaskService] 回填任务 dramaId 失败: taskId={}, dramaId={}, msg={}", taskId, dramaId, e.getMessage());
        }
    }

    @Override
    public void deleteTask(Long taskId) {
        if (taskId == null) return;
        AiTask draftTask = draftStore != null ? draftStore.getTask(taskId) : null;
        if (draftTask != null) {
            if (AiTaskStatus.PENDING.name().equals(draftTask.getStatus())
                    || AiTaskStatus.RUNNING.name().equals(draftTask.getStatus())
                    || AiTaskStatus.RETRYING.name().equals(draftTask.getStatus())) {
                throw new BizException("正在运行的拆解任务不能删除");
            }
            List<AiTask> children = draftStore.listTasks().stream()
                    .filter(child -> taskId.equals(child.getParentTaskId())).toList();
            if (children.stream().anyMatch(child -> AiTaskStatus.PENDING.name().equals(child.getStatus())
                    || AiTaskStatus.RUNNING.name().equals(child.getStatus())
                    || AiTaskStatus.RETRYING.name().equals(child.getStatus()))) {
                throw new BizException("Worker 重试正在运行，暂不能删除拆解任务");
            }
            if (aiEvents != null) {
                for (AiTask child : children) aiEvents.delete(String.valueOf(child.getId()));
            }
            draftStore.delete(taskId);
            if (aiEvents != null) aiEvents.delete(String.valueOf(taskId));
            return;
        }
        aiTaskMapper.deleteById(taskId);
        QueryWrapper<AiTask> deleteQw = new QueryWrapper<>();
        deleteQw.eq("parent_task_id", taskId);
        aiTaskMapper.delete(deleteQw);
        if (draftStore != null) draftStore.delete(taskId);
        log.info("[AiTaskService] 删除拆解任务及子任务记录: taskId={}", taskId);
    }

    private AiTaskDTO toDTO(AiTask entity) {
        return AiTaskDTO.builder()
                .id(entity.getId())
                .dramaId(entity.getDramaId())
                .episodeId(entity.getEpisodeId())
                .taskType(entity.getTaskType())
                .status(entity.getStatus())
                .targetId(entity.getTargetId())
                .parentTaskId(entity.getParentTaskId())
                .inputPayload(entity.getInputPayload())
                .outputPayload(entity.getOutputPayload())
                .modelCode(entity.getModelCode())
                .maxTokens(entity.getMaxTokens())
                .consumedTokens(entity.getConsumedTokens())
                .retryCount(entity.getRetryCount())
                .errorMessage(entity.getErrorMessage())
                .startedAt(entity.getStartedAt())
                .finishedAt(entity.getFinishedAt())
                .createTime(entity.getCreateTime())
                .build();
    }
}
