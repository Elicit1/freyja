package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.video.*;
import com.astra.freyja.service.VideoProcessingService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 视频后处理任务控制器 (视频超分、补帧等)。
 */
@RestController
@RequestMapping("/video-processing")
@RequiredArgsConstructor
public class VideoProcessingController {

    private final VideoProcessingService videoProcessingService;

    /**
     * 统一提交视频后处理任务 (超分 / 补帧)。
     */
    @PostMapping("/tasks")
    public R<VideoProcessResultVO> submitTask(@RequestBody VideoProcessSubmitDTO dto) {
        return R.ok(videoProcessingService.submitTask(dto));
    }

    /**
     * 根据任务唯一 ID 查询详情。
     */
    @GetMapping("/tasks/{taskId}")
    public R<VideoProcessResultVO> getTaskById(@PathVariable String taskId) {
        return R.ok(videoProcessingService.getTaskById(taskId));
    }

    /**
     * 分页查询视频后处理任务记录。
     */
    @GetMapping("/tasks/page")
    public R<Page<VideoProcessResultVO>> pageTasks(VideoProcessQuery query) {
        return R.ok(videoProcessingService.pageTasks(query));
    }

    /**
     * 取消视频后处理任务。
     */
    @PostMapping("/tasks/{taskId}/cancel")
    public R<Boolean> cancelTask(@PathVariable String taskId) {
        return R.ok(videoProcessingService.cancelTask(taskId));
    }

    /**
     * 探测源视频元信息 (URL 兼容接口)。
     */
    @PostMapping("/probe")
    public R<VideoProbeInfoVO> probeVideo(@RequestBody Map<String, String> body) {
        String videoUrl = body.get("videoUrl");
        return R.ok(videoProcessingService.probeVideo(videoUrl));
    }

    /**
     * 探测结构化来源视频元信息 (支持 DIRECT_URL, SHOT_CURRENT, SHOT_VIDEO_TAKE)。
     */
    @PostMapping("/probe-source")
    public R<VideoProbeInfoVO> probeVideoSource(@RequestBody VideoProcessProbeSourceDTO dto) {
        return R.ok(videoProcessingService.probeVideoSource(dto));
    }

    /**
     * 分页查询可选分镜视频 (供工作台分镜视频选择器使用)。
     */
    @GetMapping("/source-shots")
    public R<Page<ShotVideoSourceOptionVO>> querySourceShots(ShotVideoSourceQuery query) {
        return R.ok(videoProcessingService.querySourceShots(query));
    }

    /**
     * 查询指定提供商和操作类型的可用后处理模型列表及受控配置范围。
     */
    @GetMapping("/models")
    public R<List<VideoProcessingModelOptionVO>> listModelOptions(
            @RequestParam Long providerId,
            @RequestParam String operation) {
        return R.ok(videoProcessingService.listModelOptions(providerId, operation));
    }

    /**
     * 将视频后处理任务产物保存为分镜历史 (Take)，默认设为分镜当前生效视频。
     */
    @PostMapping("/tasks/{taskId}/save-to-shot")
    public R<com.astra.freyja.dto.drama.ShotVideoTakeVO> saveToShot(
            @PathVariable String taskId,
            @RequestBody(required = false) VideoProcessSaveToShotDTO dto) {
        return R.ok(videoProcessingService.saveToShot(taskId, dto != null ? dto : new VideoProcessSaveToShotDTO()));
    }
}
