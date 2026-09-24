package com.astra.freyja.service.impl;

import com.astra.freyja.dao.DramaEpisodeMapper;
import com.astra.freyja.dao.DramaMapper;
import com.astra.freyja.dao.DramaSceneMapper;
import com.astra.freyja.dao.DramaShotMapper;
import com.astra.freyja.dto.render.RenderTaskQuery;
import com.astra.freyja.dto.render.RenderTaskVO;
import com.astra.freyja.dto.script.AiTaskDTO;
import com.astra.freyja.dto.script.AiTaskQueryDTO;
import com.astra.freyja.dto.task.TaskCenterHistoryVO;
import com.astra.freyja.dto.task.TaskCenterItemVO;
import com.astra.freyja.entity.Drama;
import com.astra.freyja.entity.DramaEpisode;
import com.astra.freyja.entity.DramaScene;
import com.astra.freyja.entity.DramaShot;
import com.astra.freyja.service.AiTaskService;
import com.astra.freyja.service.RenderTaskService;
import com.astra.freyja.service.TaskCenterService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务中心聚合实现。
 *
 * <p>保留 ai_task 与 render_task 的独立生命周期，只在查询层统一视图，
 * 避免将渲染调度状态机和 AI 分析状态机强行合表。</p>
 */
@Service
@RequiredArgsConstructor
public class TaskCenterServiceImpl implements TaskCenterService {

    private static final Set<String> ACTIVE_AI_STATUSES = Set.of("PENDING", "RUNNING", "RETRYING");

    private final AiTaskService aiTaskService;
    private final RenderTaskService renderTaskService;
    private final DramaMapper dramaMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaSceneMapper sceneMapper;
    private final DramaShotMapper shotMapper;

    @Override
    public List<TaskCenterItemVO> getActiveTasks() {
        List<TaskCenterItemVO> result = new ArrayList<>();
        result.addAll(renderTaskService.getActiveTasks().stream().map(this::fromRender).toList());

        AiTaskQueryDTO query = AiTaskQueryDTO.builder().statuses(ACTIVE_AI_STATUSES).build();
        result.addAll(aiTaskService.listTasks(query).stream()
                .filter(task -> !isDecomposeSubtask(task))
                .map(this::fromAi).toList());
        return sortNewestFirst(result);
    }


    @Override
    public TaskCenterHistoryVO getHistoryTasks(Integer limit) {
        int safeLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        RenderTaskQuery query = new RenderTaskQuery();
        query.setCurrent(1);
        query.setSize(safeLimit);
        Page<RenderTaskVO> renderPage = renderTaskService.getHistoryTasks(query);

        List<TaskCenterItemVO> result = new ArrayList<>();
        if (renderPage != null && renderPage.getRecords() != null) {
            result.addAll(renderPage.getRecords().stream().map(this::fromRender).toList());
        }
        result.addAll(aiTaskService.listTasks(null).stream()
                .filter(task -> !isDecomposeSubtask(task))
                .filter(task -> !ACTIVE_AI_STATUSES.contains(task.getStatus()))
                .limit(safeLimit)
                .map(this::fromAi)
                .toList());

        List<TaskCenterItemVO> sorted = sortNewestFirst(result);
        if (sorted.size() > safeLimit) {
            sorted = sorted.subList(0, safeLimit);
        }
        return TaskCenterHistoryVO.builder().records(sorted).total(sorted.size()).build();
    }

    @Override
    public TaskCenterItemVO getTask(String sourceType, String taskId) {
        if (StringUtils.isBlank(sourceType) || StringUtils.isBlank(taskId)) return null;
        if ("RENDER_TASK".equalsIgnoreCase(sourceType)) {
            RenderTaskVO task = renderTaskService.getTaskById(taskId);
            return task == null ? null : fromRender(task);
        }
        if ("AI_TASK".equalsIgnoreCase(sourceType)) {
            try {
                AiTaskDTO task = aiTaskService.getTaskById(Long.valueOf(taskId));
                return task == null || isDecomposeSubtask(task) ? null : fromAi(task);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @Override
    public boolean cancelTask(String sourceType, String taskId) {
        if (StringUtils.isBlank(sourceType) || StringUtils.isBlank(taskId)) return false;
        if ("RENDER_TASK".equalsIgnoreCase(sourceType)) {
            return renderTaskService.cancelTask(taskId);
        }
        // AI 分析任务当前没有安全的中断协议，保留后台执行并支持结果恢复。
        return false;
    }

    private TaskCenterItemVO fromAi(AiTaskDTO task) {
        String taskType = StringUtils.defaultIfBlank(task.getTaskType(), "AI_TASK");
        Long shotId = ("SHOT_PROMPT_DERIVE".equals(taskType) || "SHOT_VISUAL_PLAN".equals(taskType)
                || "SHOT_VOICE_GENERATE".equals(taskType) || "SHOT_FRAME_GENERATE".equals(taskType)
                || "SHOT_DECOMPOSE".equals(taskType) || "SHOT_ENRICHMENT".equals(taskType)
                || "CONTINUITY_CHECK".equals(taskType))
                ? parseLong(task.getTargetId()) : null;
        DramaShot shot = shotId == null ? null : shotMapper.selectById(shotId);
        Context context = loadContext(
                shot != null ? shot.getDramaId() : task.getDramaId(),
                shot != null ? shot.getEpisodeId() : task.getEpisodeId(),
                shot != null ? shot.getSceneId() : null,
                shotId);

        String title = aiTitle(taskType, context.shotName(), task.getTargetId());
        String resumeAction = aiResumeAction(taskType, shotId);
        return TaskCenterItemVO.builder()
                .sourceType("AI_TASK")
                .taskId(String.valueOf(task.getId()))
                .category("AI_ANALYSIS")
                .taskType(taskType)
                .title(title)
                .status(task.getStatus())
                // AI 任务没有可靠的阶段百分比，运行中只展示当前阶段，避免制造虚假的进度。
                .progress("SUCCESS".equals(task.getStatus()) ? 100 : null)
                .currentStage(aiStage(taskType, task.getStatus()))
                .modelCode(task.getModelCode())
                .errorMessage(task.getErrorMessage())
                .dramaId(asString(context.dramaId() != null ? context.dramaId() : task.getDramaId()))
                .dramaTitle(context.dramaTitle())
                .episodeId(asString(context.episodeId() != null ? context.episodeId() : task.getEpisodeId()))
                .episodeName(context.episodeName())
                .sceneId(asString(context.sceneId()))
                .sceneName(context.sceneName())
                .shotId(asString(context.shotId()))
                .shotNo(context.shotNo())
                .shotName(context.shotName())
                .targetType(shotId != null ? "SHOT" : "TASK_TARGET")
                .targetId(task.getTargetId())
                .resumeAction(resumeAction)
                .createdAt(task.getCreateTime())
                .startedAt(task.getStartedAt())
                .finishedAt(task.getFinishedAt())
                .unread(Boolean.FALSE)
                .build();
    }

    private boolean isDecomposeSubtask(AiTaskDTO task) {
        return task.getParentTaskId() != null && ("PLOT_EXTRACTION".equals(task.getTaskType())
                || "SHOT_DECOMPOSE".equals(task.getTaskType())
                || "WORKER_RETRY".equals(task.getTaskType()));
    }

    private TaskCenterItemVO fromRender(RenderTaskVO task) {
        return TaskCenterItemVO.builder()
                .sourceType("RENDER_TASK")
                .taskId(task.getTaskId())
                .category("RENDER")
                .taskType(task.getTaskType())
                .title(StringUtils.defaultIfBlank(task.getTaskName(), "渲染任务"))
                .status(normalizeRenderStatus(task.getStatus()))
                .progress(task.getProgress())
                .currentStage(task.getCurrentNode())
                .modelCode(task.getModelCode())
                .errorMessage(task.getErrorMessage())
                .dramaId(asString(task.getDramaId()))
                .dramaTitle(task.getDramaTitle())
                .episodeId(asString(task.getEpisodeId()))
                .episodeName(task.getEpisodeName())
                .sceneId(asString(task.getSceneId()))
                .shotId(asString(task.getShotId()))
                .shotNo(task.getShotNo())
                .targetType(task.getShotId() != null ? "SHOT" : task.getAssetId() != null ? "ASSET" : "TASK")
                .targetId(task.getShotId() != null ? asString(task.getShotId()) : asString(task.getAssetId()))
                .resumeAction(renderResumeAction(task))
                .createdAt(task.getSubmitTime())
                .startedAt(task.getStartTime())
                .finishedAt(task.getFinishTime())
                .unread(Boolean.FALSE)
                .build();
    }

    private List<TaskCenterItemVO> sortNewestFirst(List<TaskCenterItemVO> tasks) {
        return tasks.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(this::sortTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    private LocalDateTime sortTime(TaskCenterItemVO task) {
        if (task.getStartedAt() != null) return task.getStartedAt();
        return task.getCreatedAt();
    }

    private Context loadContext(Long dramaId, Long episodeId, Long sceneId, Long shotId) {
        Drama drama = dramaId == null ? null : dramaMapper.selectById(dramaId);
        DramaEpisode episode = episodeId == null ? null : episodeMapper.selectById(episodeId);
        DramaScene scene = sceneId == null ? null : sceneMapper.selectById(sceneId);
        DramaShot shot = shotId == null ? null : shotMapper.selectById(shotId);
        return new Context(
                dramaId, drama == null ? null : drama.getTitle(),
                episodeId, episode == null ? null : episode.getTitle(),
                sceneId, scene == null ? null : scene.getName(),
                shotId, shot == null ? null : shot.getShotNo(), shot == null ? null : shot.getShotName());
    }

    private String aiTitle(String taskType, String shotName, String targetId) {
        if ("SHOT_PROMPT_DERIVE".equals(taskType)) {
            return StringUtils.isBlank(shotName) ? "分镜 · AI 提示词分析" : "分镜 " + shotName + " · AI 提示词分析";
        }
        String label = switch (taskType) {
            case "CHAPTER_DECOMPOSE" -> "整章剧情并行分镜拆解";
            case "SHOT_DECOMPOSE" -> "分镜拆解";
            case "SHOT_ENRICHMENT" -> "分镜特征增强";
            case "CONTINUITY_CHECK" -> "镜头连续性分析";
            case "ENTITY_RESOLUTION" -> "角色场景实体消歧";
            case "CHARACTER_PROMPT_DERIVE" -> "角色提示词衍生";
            case "LOOK_PROMPT_DERIVE" -> "造型提示词衍生";
            case "SCENE_PROMPT_DERIVE" -> "场景提示词衍生";
            case "PROP_PROMPT_DERIVE" -> "道具提示词衍生";
            case "SHOT_VISUAL_PLAN" -> "分镜视觉导演规划";
            case "CHARACTER_VOICE_DESIGN" -> "角色母音设计";
            case "VOICE_PREVIEW" -> "音色试听";
            case "SHOT_VOICE_GENERATE" -> "分镜台词配音";
            case "EPISODE_VOICE_GENERATE" -> "整集配音";
            case "SHOT_FRAME_GENERATE" -> "分镜关键帧生图";
            case "ASSET_IMAGE_GENERATE" -> "资产生图";
            case "EPISODE_DECOMPOSE" -> "单集剧本拆解";
            case "WORKER_RETRY" -> "分段 Worker 重试";
            default -> "AI 后台任务";
        };
        return StringUtils.isBlank(targetId) ? label : label + " · " + targetId;
    }

    private String aiResumeAction(String taskType, Long shotId) {
        if ("SHOT_PROMPT_DERIVE".equals(taskType) && shotId != null) return "SHOT_PROMPT_DERIVE";
        if ("CHAPTER_DECOMPOSE".equals(taskType)) return "SCRIPT_DECOMPOSE";
        if ("SHOT_DECOMPOSE".equals(taskType) || "SHOT_ENRICHMENT".equals(taskType)
                || "CONTINUITY_CHECK".equals(taskType)) return shotId != null ? "SHOT_DETAIL" : "TASK_DETAIL";
        return "TASK_DETAIL";
    }

    private String renderResumeAction(RenderTaskVO task) {
        if (task.getShotId() != null) return "SHOT_RENDER";
        if (task.getAssetId() != null) return "ASSET_DETAIL";
        return "TASK_DETAIL";
    }

    private String aiStage(String taskType, String status) {
        if ("SUCCESS".equals(status)) return "已完成";
        if ("FAILED".equals(status)) return "执行失败";
        if ("RETRYING".equals(status)) return "重试中";
        return switch (taskType) {
            case "SHOT_PROMPT_DERIVE" -> "正在分析镜头提示词";
            case "CHAPTER_DECOMPOSE" -> "正在拆解剧情与分镜";
            default -> "AI 分析处理中";
        };
    }

    private boolean isActive(String status) {
        return ACTIVE_AI_STATUSES.contains(status);
    }

    private String normalizeRenderStatus(String status) {
        return "RENDERING".equals(status) ? "RUNNING" : status;
    }

    private Long parseLong(String value) {
        if (StringUtils.isBlank(value)) return null;
        try { return Long.valueOf(value); } catch (NumberFormatException ignored) { return null; }
    }

    private String asString(Long value) { return value == null ? null : String.valueOf(value); }

    private record Context(Long dramaId, String dramaTitle, Long episodeId, String episodeName,
                           Long sceneId, String sceneName, Long shotId, Integer shotNo, String shotName) {}
}
