package com.astra.freyja.service.impl;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dao.RenderTaskMapper;
import com.astra.freyja.dto.render.RenderTaskQuery;
import com.astra.freyja.dto.render.RenderTaskVO;
import com.astra.freyja.dto.render.RenderTaskWsMessage;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.entity.RenderTask;
import com.astra.freyja.service.AiImageApiService;
import com.astra.freyja.service.RenderTaskService;
import com.astra.freyja.websocket.RenderTaskWebSocketHandler;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import com.astra.freyja.event.TaskStatusChangedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 渲染任务全生命周期调度服务实现。
 * 严格分离实时任务（Redis + WebSocket 推流）与历史任务（MySQL）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RenderTaskServiceImpl implements RenderTaskService {

    private static final String REDIS_ACTIVE_HASH = "freyja:render:active_tasks";
    private static final String REDIS_ACTIVE_ZSET = "freyja:render:active_queue";

    private final RenderTaskMapper renderTaskMapper;
    private final DramaShotMapper dramaShotMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    @Lazy
    private final RenderTaskWebSocketHandler wsHandler;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;
    @Lazy
    private final AiImageApiService aiImageApiService;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    private void publishTaskStatusChanged(String taskId, String status) {
        if (eventPublisher != null && taskId != null) {
            try {
                eventPublisher.publishEvent(new TaskStatusChangedEvent("RENDER_TASK", taskId, status));
            } catch (Exception e) {
                log.warn("[RenderTaskService] 发布任务状态事件失败: taskId={}, status={}, msg={}", taskId, status, e.getMessage());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RenderTaskVO createTask(RenderTaskVO vo) {
        if (vo == null) {
            throw new BizException(400, "渲染任务信息不能为空");
        }
        if (StringUtils.isBlank(vo.getTaskId())) {
            vo.setTaskId("RENDER_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000, 9999));
        }
        if (StringUtils.isBlank(vo.getStatus())) {
            vo.setStatus("QUEUED");
        }
        if (vo.getProgress() == null) {
            vo.setProgress(0);
        }
        if (vo.getSubmitTime() == null) {
            vo.setSubmitTime(LocalDateTime.now());
        }

        // 1. 写入 MySQL 初始化记录
        RenderTask entity = new RenderTask();
        BeanUtils.copyProperties(vo, entity);
        renderTaskMapper.insert(entity);
        vo.setId(entity.getId());

        // 2. 写入 Redis 活跃任务缓存 (Hash + ZSet)
        saveActiveTaskToRedis(vo);

        // 3. 广播与发布任务状态变更
        publishTaskStatusChanged(vo.getTaskId(), vo.getStatus());

        int activeCount = getActiveTaskCount();
        wsHandler.broadcast(RenderTaskWsMessage.builder()
                .event("TASK_QUEUED")
                .timestamp(System.currentTimeMillis())
                .activeCount(activeCount)
                .data(vo)
                .build());

        log.info("[RenderTaskService] 渲染任务创建并入队: taskId={}, name={}, activeCount={}",
                vo.getTaskId(), vo.getTaskName(), activeCount);
        return vo;
    }

    @Override
    public void updateProgress(String taskId, int progress, String currentNode) {
        if (StringUtils.isBlank(taskId)) return;

        RenderTaskVO vo = getActiveTaskFromRedis(taskId);
        if (vo == null) {
            // 尝试从 MySQL 查
            RenderTask entity = renderTaskMapper.selectOne(
                    new LambdaQueryWrapper<RenderTask>().eq(RenderTask::getTaskId, taskId)
            );
            if (entity == null) {
                log.warn("[RenderTaskService] 更新进度未找到任务: taskId={}", taskId);
                return;
            }
            vo = convertToVO(entity);
        }

        vo.setProgress(Math.min(100, Math.max(0, progress)));
        vo.setStatus("RENDERING");
        if (StringUtils.isNotBlank(currentNode)) {
            vo.setCurrentNode(currentNode);
        }
        if (vo.getStartTime() == null) {
            vo.setStartTime(LocalDateTime.now());
        }

        // 高频仅写 Redis
        saveActiveTaskToRedis(vo);

        publishTaskStatusChanged(taskId, "RENDERING");

        // WebSocket 毫秒级推流广播
        wsHandler.broadcast(RenderTaskWsMessage.builder()
                .event("TASK_PROGRESS")
                .timestamp(System.currentTimeMillis())
                .activeCount(getActiveTaskCount())
                .data(vo)
                .build());
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishTask(String taskId, String outputUrl, String lastFrameUrl) {
        if (StringUtils.isBlank(taskId)) return;

        RenderTaskVO vo = getActiveTaskFromRedis(taskId);
        LocalDateTime now = LocalDateTime.now();
        Long costMs = 0L;

        if (vo != null) {
            vo.setStatus("SUCCESS");
            vo.setProgress(100);
            vo.setOutputUrl(outputUrl);
            vo.setLastFrameUrl(lastFrameUrl);
            vo.setFinishTime(now);
            if (vo.getSubmitTime() != null) {
                costMs = Duration.between(vo.getSubmitTime(), now).toMillis();
            }
            vo.setCostMs(costMs);
        }

        // 1. 归档持久化到 MySQL
        RenderTask entity = renderTaskMapper.selectOne(
                new LambdaQueryWrapper<RenderTask>().eq(RenderTask::getTaskId, taskId)
        );
        if (entity != null) {
            entity.setStatus("SUCCESS");
            entity.setProgress(100);
            entity.setOutputUrl(outputUrl);
            entity.setLastFrameUrl(lastFrameUrl);
            entity.setFinishTime(now);
            if (entity.getSubmitTime() != null) {
                entity.setCostMs(Duration.between(entity.getSubmitTime(), now).toMillis());
            }
            renderTaskMapper.updateById(entity);
        }

        // 2. 从 Redis 活跃集合剔除
        removeActiveTaskFromRedis(taskId);

        publishTaskStatusChanged(taskId, "SUCCESS");

        // 3. WebSocket 广播完成事件
        int activeCount = getActiveTaskCount();
        wsHandler.broadcast(RenderTaskWsMessage.builder()
                .event("TASK_SUCCESS")
                .timestamp(System.currentTimeMillis())
                .activeCount(activeCount)
                .data(vo != null ? vo : (entity != null ? convertToVO(entity) : null))
                .build());

        log.info("[RenderTaskService] 渲染任务成功并归档: taskId={}, costMs={}, 剩余活跃数={}",
                taskId, costMs, activeCount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void failTask(String taskId, String errorMessage, String errorDetail) {
        if (StringUtils.isBlank(taskId)) return;

        RenderTaskVO vo = getActiveTaskFromRedis(taskId);
        LocalDateTime now = LocalDateTime.now();
        Long costMs = 0L;

        if (vo != null) {
            vo.setStatus("FAILED");
            vo.setErrorMessage(errorMessage);
            vo.setFinishTime(now);
            if (vo.getSubmitTime() != null) {
                costMs = Duration.between(vo.getSubmitTime(), now).toMillis();
            }
            vo.setCostMs(costMs);
        }

        // 1. 更新 MySQL 失败归档
        RenderTask entity = renderTaskMapper.selectOne(
                new LambdaQueryWrapper<RenderTask>().eq(RenderTask::getTaskId, taskId)
        );
        if (entity != null) {
            entity.setStatus("FAILED");
            entity.setErrorMessage(errorMessage);
            entity.setErrorDetail(errorDetail);
            entity.setFinishTime(now);
            if (entity.getSubmitTime() != null) {
                entity.setCostMs(Duration.between(entity.getSubmitTime(), now).toMillis());
            }
            renderTaskMapper.updateById(entity);
        }

        // 2. 从 Redis 剔除
        removeActiveTaskFromRedis(taskId);

        publishTaskStatusChanged(taskId, "FAILED");

        // 3. WebSocket 广播失败事件
        int activeCount = getActiveTaskCount();

        wsHandler.broadcast(RenderTaskWsMessage.builder()
                .event("TASK_FAILED")
                .timestamp(System.currentTimeMillis())
                .activeCount(activeCount)
                .data(vo != null ? vo : (entity != null ? convertToVO(entity) : null))
                .build());

        log.warn("[RenderTaskService] 渲染任务失败并归档: taskId={}, error={}, 剩余活跃数={}",
                taskId, errorMessage, activeCount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelTask(String taskId) {
        if (StringUtils.isBlank(taskId)) return false;

        RenderTask entity = renderTaskMapper.selectOne(
                new LambdaQueryWrapper<RenderTask>().eq(RenderTask::getTaskId, taskId)
        );
        RenderTaskVO vo = getActiveTaskFromRedis(taskId);

        if (entity == null && vo == null) {
            log.warn("[RenderTaskService] 取消任务未找到记录: taskId={}", taskId);
            return false;
        }

        // 终态幂等检查
        String currentStatus = entity != null ? entity.getStatus() : (vo != null ? vo.getStatus() : null);
        if ("SUCCESS".equalsIgnoreCase(currentStatus) || "FAILED".equalsIgnoreCase(currentStatus) || "CANCELLED".equalsIgnoreCase(currentStatus)) {
            log.info("[RenderTaskService] 任务已处于终态 ({})，无需取消: taskId={}", currentStatus, taskId);
            return true;
        }

        Long providerId = entity != null ? entity.getProviderId() : (vo != null ? vo.getProviderId() : null);
        Long shotId = entity != null ? entity.getShotId() : (vo != null ? vo.getShotId() : null);

        // 1. 优先向远程 AI 网关及 ComfyUI 发送精准取消指令 (避免本地先断开导致网关 finally 丢失映射)
        com.astra.freyja.dto.render.RemoteCancelResultVO cancelResult = null;
        if (aiImageApiService != null) {
            try {
                cancelResult = aiImageApiService.cancelRemoteTask(providerId, taskId, shotId);
            } catch (Exception e) {
                log.error("[RenderTaskService] 调用远程网关取消任务异常: taskId={}, error={}", taskId, e.getMessage(), e);
                throw new BizException(500, "向远程渲染服务发送取消指令失败: " + e.getMessage() + "，请重试");
            }
        }

        // 2. 根据远程真实执行结果进行状态流转
        if (cancelResult != null && !cancelResult.isSuccess()) {
            if ("ALREADY_FINISHED".equalsIgnoreCase(cancelResult.getDetailStatus())) {
                log.info("[RenderTaskService] 远程渲染任务已计算完成，拒绝标记取消: taskId={}", taskId);
                throw new BizException(400, "渲染任务已生成完毕，无法取消");
            }
            if ("FAILED".equalsIgnoreCase(cancelResult.getDetailStatus())) {
                log.error("[RenderTaskService] 远程渲染节点取消失败: taskId={}, message={}", taskId, cancelResult.getMessage());
                throw new BizException(500, "远程渲染取消失败: " + cancelResult.getMessage() + "，请重试");
            }
            // 若为 NOT_FOUND，说明远程映射已清理或未开始，继续执行本地清理
            log.warn("[RenderTaskService] 远程未找到活跃渲染任务 ({})，执行本地清理: taskId={}", cancelResult.getDetailStatus(), taskId);
        }

        LocalDateTime now = LocalDateTime.now();

        // 3. 远程取消确认成功后，打断本地正在等待或执行的渲染工作线程
        com.astra.freyja.service.RenderTaskThreadRegistry.interrupt(taskId);

        // 4. 更新 MySQL 状态为 CANCELLED
        if (entity != null) {
            entity.setStatus("CANCELLED");
            entity.setFinishTime(now);
            if (entity.getSubmitTime() != null) {
                entity.setCostMs(Duration.between(entity.getSubmitTime(), now).toMillis());
            }
            renderTaskMapper.updateById(entity);
            resetShotRenderStatus(entity);
        } else if (vo != null) {
            RenderTask mock = new RenderTask();
            BeanUtils.copyProperties(vo, mock);
            resetShotRenderStatus(mock);
        }

        // 5. 从 Redis 移除活跃队列
        removeActiveTaskFromRedis(taskId);

        publishTaskStatusChanged(taskId, "CANCELLED");

        if (vo != null) {
            vo.setStatus("CANCELLED");
        }


        // 6. WebSocket 广播取消事件
        int activeCount = getActiveTaskCount();
        wsHandler.broadcast(RenderTaskWsMessage.builder()
                .event("TASK_CANCELLED")
                .timestamp(System.currentTimeMillis())
                .activeCount(activeCount)
                .data(vo != null ? vo : (entity != null ? convertToVO(entity) : null))
                .build());

        log.info("[RenderTaskService] 渲染任务已确认取消: taskId={}, 剩余活跃数={}", taskId, activeCount);
        return true;
    }

    private void resetShotRenderStatus(RenderTask entity) {
        if (entity == null) return;
        try {
            // 1. 如果有关联的单分镜
            if (entity.getShotId() != null) {
                DramaShot shot = dramaShotMapper.selectById(entity.getShotId());
                if (shot != null && ("QUEUED".equalsIgnoreCase(shot.getRenderStatus()) || "RENDERING".equalsIgnoreCase(shot.getRenderStatus()))) {
                    shot.setRenderStatus("INIT");
                    shot.setLatestTaskId(null);
                    dramaShotMapper.updateById(shot);
                    log.info("[RenderTaskService] 取消任务联动恢复分镜状态为待渲染: shotId={}, taskId={}", shot.getId(), entity.getTaskId());
                }
            }
        } catch (Exception e) {
            log.error("[RenderTaskService] 取消任务恢复分镜状态异常: taskId={}, err={}", entity.getTaskId(), e.getMessage(), e);
        }
    }

    @Override
    public List<RenderTaskVO> getActiveTasks() {
        try {
            // 按排队时间先后（Score 升序）从 ZSet 获取所有活跃任务 ID
            Set<Object> taskIds = redisTemplate.opsForZSet().range(REDIS_ACTIVE_ZSET, 0, -1);
            if (taskIds == null || taskIds.isEmpty()) {
                return Collections.emptyList();
            }

            List<RenderTaskVO> result = new ArrayList<>();
            for (Object idObj : taskIds) {
                String taskId = String.valueOf(idObj);
                RenderTaskVO vo = getActiveTaskFromRedis(taskId);
                if (vo != null) {
                    result.add(vo);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("[RenderTaskService] 从 Redis 获取活跃任务异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public int getActiveTaskCount() {
        try {
            Long count = redisTemplate.opsForZSet().zCard(REDIS_ACTIVE_ZSET);
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            log.error("[RenderTaskService] 获取活跃任务数异常: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public RenderTaskVO getTaskById(String taskId) {
        if (StringUtils.isBlank(taskId)) return null;
        // 先查 Redis
        RenderTaskVO vo = getActiveTaskFromRedis(taskId);
        if (vo != null) {
            return vo;
        }
        // 再查 MySQL
        RenderTask entity = renderTaskMapper.selectOne(
                new LambdaQueryWrapper<RenderTask>().eq(RenderTask::getTaskId, taskId)
        );
        return entity != null ? convertToVO(entity) : null;
    }

    @Override
    public Page<RenderTaskVO> getHistoryTasks(RenderTaskQuery query) {
        if (query == null) query = new RenderTaskQuery();

        Page<RenderTask> page = new Page<>(
                query.getCurrent() != null ? query.getCurrent() : 1,
                query.getSize() != null ? query.getSize() : 10
        );

        LambdaQueryWrapper<RenderTask> qw = new LambdaQueryWrapper<RenderTask>()
                .eq(query.getDramaId() != null && query.getDramaId() > 0, RenderTask::getDramaId, query.getDramaId())
                .eq(query.getEpisodeId() != null && query.getEpisodeId() > 0, RenderTask::getEpisodeId, query.getEpisodeId())
                .eq(StringUtils.isNotBlank(query.getTaskType()), RenderTask::getTaskType, query.getTaskType())
                .ge(StringUtils.isNotBlank(query.getStartTime()), RenderTask::getSubmitTime, query.getStartTime())
                .le(StringUtils.isNotBlank(query.getEndTime()), RenderTask::getSubmitTime, query.getEndTime())
                .orderByDesc(RenderTask::getSubmitTime);

        // 如果明确传了状态就查指定状态，否则默认展示终态历史 (SUCCESS, FAILED, CANCELLED)
        if (StringUtils.isNotBlank(query.getStatus())) {
            qw.eq(RenderTask::getStatus, query.getStatus());
        } else {
            qw.in(RenderTask::getStatus, "SUCCESS", "FAILED", "CANCELLED");
        }

        if (StringUtils.isNotBlank(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            qw.and(wrapper -> wrapper.like(RenderTask::getTaskName, kw)
                    .or().like(RenderTask::getPrompt, kw)
                    .or().like(RenderTask::getTaskId, kw));
        }

        Page<RenderTask> entityPage = renderTaskMapper.selectPage(page, qw);

        Page<RenderTaskVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        List<RenderTaskVO> voList = entityPage.getRecords().stream().map(this::convertToVO).toList();
        voPage.setRecords(voList);
        return voPage;
    }

    // =========================================================================
    // 内部私有辅助
    // =========================================================================

    private void saveActiveTaskToRedis(RenderTaskVO vo) {
        try {
            String json = objectMapper.writeValueAsString(vo);
            redisTemplate.opsForHash().put(REDIS_ACTIVE_HASH, vo.getTaskId(), json);
            double score = vo.getSubmitTime() != null ?
                    java.sql.Timestamp.valueOf(vo.getSubmitTime()).getTime() : System.currentTimeMillis();
            redisTemplate.opsForZSet().add(REDIS_ACTIVE_ZSET, vo.getTaskId(), score);
        } catch (Exception e) {
            log.error("[RenderTaskService] 缓存实时任务至 Redis 失败: taskId={}, err={}", vo.getTaskId(), e.getMessage());
        }
    }

    private RenderTaskVO getActiveTaskFromRedis(String taskId) {
        try {
            Object obj = redisTemplate.opsForHash().get(REDIS_ACTIVE_HASH, taskId);
            if (obj == null) return null;
            return objectMapper.readValue(String.valueOf(obj), RenderTaskVO.class);
        } catch (Exception e) {
            log.warn("[RenderTaskService] 从 Redis 反序列化任务失败: taskId={}, err={}", taskId, e.getMessage());
            return null;
        }
    }

    private void removeActiveTaskFromRedis(String taskId) {
        try {
            redisTemplate.opsForHash().delete(REDIS_ACTIVE_HASH, taskId);
            redisTemplate.opsForZSet().remove(REDIS_ACTIVE_ZSET, taskId);
        } catch (Exception e) {
            log.warn("[RenderTaskService] 从 Redis 移除活跃任务失败: taskId={}, err={}", taskId, e.getMessage());
        }
    }

    private RenderTaskVO convertToVO(RenderTask entity) {
        if (entity == null) return null;
        RenderTaskVO vo = new RenderTaskVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
