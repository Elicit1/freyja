package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.comfy.ComfyRenderTaskVO;
import com.astra.freyja.dto.drama.DramaShotBatchAssembleDTO;
import com.astra.freyja.dto.drama.DramaShotDTO;
import com.astra.freyja.dto.drama.DramaShotRenderRequestDTO;
import com.astra.freyja.dto.drama.DramaShotReorderDTO;
import com.astra.freyja.dto.drama.DramaShotVO;
import com.astra.freyja.dto.drama.ShotAiVisualPlanApplyDTO;
import com.astra.freyja.dto.drama.ShotAiVisualPlanRequestDTO;
import com.astra.freyja.dto.drama.ShotAiVisualPlanVO;
import com.astra.freyja.dto.drama.ShotPromptDeriveDTO;
import com.astra.freyja.dto.drama.PreviousVideoTailRequest;
import com.astra.freyja.dto.drama.PreviousVideoTailVO;
import com.astra.freyja.dto.drama.ShotPromptPackageVO;
import com.astra.freyja.dto.drama.ShotPromptParseRequestDTO;
import com.astra.freyja.dto.drama.ShotPromptValidationResult;
import com.astra.freyja.dto.script.AiTaskDTO;
import com.astra.freyja.dto.script.AiTaskQueryDTO;
import com.astra.freyja.entity.enums.AiTaskType;
import com.astra.freyja.dto.res.PromptAssembleResultVO;
import com.astra.freyja.service.DramaShotService;
import com.astra.freyja.service.ShotAiVisualPlanService;
import com.astra.freyja.service.ShotFrameContinuityService;
import com.astra.freyja.service.AiTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 分镜镜头管理 Controller。
 */
@RestController
@RequestMapping("/drama/shot")
@RequiredArgsConstructor
public class DramaShotController {

    private final DramaShotService shotService;
    private final ShotAiVisualPlanService shotAiVisualPlanService;
    private final ShotFrameContinuityService continuityService;
    private final com.astra.freyja.service.ShotVideoTakeService shotVideoTakeService;
    private final AiTaskService aiTaskService;

    @GetMapping("/list/{sceneId}")
    public R<List<DramaShotVO>> listBySceneId(@PathVariable Long sceneId) {
        return R.ok(shotService.listBySceneId(sceneId));
    }

    @GetMapping("/list-by-episode")
    public R<List<DramaShotVO>> listByEpisodeId(@RequestParam("episodeId") Long episodeId) {
        return R.ok(shotService.listByEpisodeId(episodeId));
    }

    @GetMapping("/{id}")
    public R<DramaShotVO> getById(@PathVariable Long id) {
        return R.ok(shotService.getById(id));
    }

    @PostMapping
    public R<Long> create(@RequestBody DramaShotDTO dto) {
        return R.ok(shotService.create(dto));
    }

    @PutMapping
    public R<Void> update(@RequestBody DramaShotDTO dto) {
        shotService.update(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        shotService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/clone")
    public R<Long> cloneShot(@PathVariable Long id) {
        return R.ok(shotService.cloneShot(id));
    }

    @PutMapping("/reorder")
    public R<Void> reorder(@RequestBody DramaShotReorderDTO dto) {
        shotService.reorderShots(dto);
        return R.ok();
    }

    @GetMapping("/{id}/preview-prompt")
    public R<PromptAssembleResultVO> previewPrompt(@PathVariable Long id) {
        return R.ok(shotService.previewShotPrompt(id));
    }

    @PostMapping("/{id}/assemble-prompt")
    public R<PromptAssembleResultVO> assemblePrompt(@PathVariable Long id) {
        return R.ok(shotService.assembleShotPrompt(id));
    }

    @PostMapping("/batch-assemble")
    public R<Integer> batchAssemble(@RequestBody DramaShotBatchAssembleDTO dto) {
        return R.ok(shotService.batchAssemblePrompts(dto));
    }

    @PostMapping("/{id}/generate-first-frame")
    public R<ComfyRenderTaskVO> generateFirstFrame(@PathVariable Long id,
                                                   @RequestBody(required = false) com.astra.freyja.dto.drama.DramaShotFirstFrameDTO dto) {
        return R.ok(shotService.generateFirstFrame(id, dto));
    }

    @PostMapping("/{id}/ai-visual-plan")
    public R<ShotAiVisualPlanVO> generateAiVisualPlan(@PathVariable Long id,
                                                       @RequestBody(required = false) ShotAiVisualPlanRequestDTO dto) {
        return R.ok(shotAiVisualPlanService.generate(id, dto));
    }

    @PostMapping("/{id}/ai-visual-plan/apply")
    public R<ShotAiVisualPlanVO> applyAiVisualPlan(@PathVariable Long id,
                                                   @RequestBody ShotAiVisualPlanApplyDTO dto) {
        return R.ok(shotAiVisualPlanService.apply(id, dto));
    }

    @PutMapping("/{id}/set-first-frame")
    public R<Void> setFirstFrame(@PathVariable Long id,
                                 @RequestBody Map<String, String> body) {
        String url = body != null ? body.get("previewImageUrl") : null;
        shotService.setFirstFrame(id, url);
        return R.ok();
    }

    @PostMapping("/{currentShotId}/inherit-previous-video-tail")
    public R<PreviousVideoTailVO> inheritPreviousVideoTail(
            @PathVariable Long currentShotId,
            @RequestBody(required = false) PreviousVideoTailRequest request) {
        return R.ok(continuityService.inheritPreviousVideoTail(currentShotId, request));
    }

    @PutMapping("/{id}/set-end-frame")
    public R<Void> setEndFrame(@PathVariable Long id,
                               @RequestBody Map<String, String> body) {
        String url = body != null ? body.get("endFrameImageUrl") : null;
        shotService.setEndFrame(id, url);
        return R.ok();
    }

    @PostMapping("/{id}/render")
    public R<ComfyRenderTaskVO> submitRender(@PathVariable Long id,
                                             @RequestBody(required = false) DramaShotRenderRequestDTO requestDTO) {
        return R.ok(shotService.submitShotRender(id, requestDTO));
    }

    @PostMapping(value = "/derive-prompt-stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter derivePromptStream(@RequestBody ShotPromptDeriveDTO dto) {
        return shotAiVisualPlanService.derivePromptStream(dto);
    }

    @PostMapping("/derive-prompt-task")
    public R<String> startPromptTask(@RequestBody ShotPromptDeriveDTO dto) {
        return R.ok(shotAiVisualPlanService.startPromptTask(dto));
    }

    @PostMapping("/derive-prompt-package")
    public R<ShotPromptPackageVO> derivePromptPackage(@RequestBody ShotPromptDeriveDTO dto) {
        return R.ok(shotAiVisualPlanService.buildPromptPackage(dto));
    }

    @PostMapping("/parse-derived-prompt")
    public R<ShotPromptValidationResult> parseDerivedPrompt(@RequestBody ShotPromptParseRequestDTO request) {
        return R.ok(shotAiVisualPlanService.parseAndValidateDerivedPrompt(request));
    }

    /** 获取持久化的分镜提示词分析任务，页面关闭后可据此恢复结果。 */
    @GetMapping("/prompt-task/{taskId}")
    public R<AiTaskDTO> getPromptTask(@PathVariable Long taskId) {
        return R.ok(aiTaskService.getTaskById(taskId));
    }

    /** 获取指定分镜最近一次提示词分析任务。 */
    @GetMapping("/{shotId}/prompt-task/latest")
    public R<AiTaskDTO> getLatestPromptTask(@PathVariable Long shotId) {
        AiTaskQueryDTO query = AiTaskQueryDTO.builder()
                .taskType(AiTaskType.SHOT_PROMPT_DERIVE.name())
                .targetId(String.valueOf(shotId))
                .build();
        List<AiTaskDTO> tasks = aiTaskService.listTasks(query);
        return R.ok(tasks == null || tasks.isEmpty() ? null : tasks.get(0));
    }

    @GetMapping("/{shotId}/video-takes")
    public R<com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.astra.freyja.dto.drama.ShotVideoTakeVO>> getVideoTakes(
            @PathVariable Long shotId,
            com.astra.freyja.dto.drama.ShotVideoTakeQuery query) {
        return R.ok(shotVideoTakeService.pageByShotId(shotId, query));
    }

    @PostMapping("/{shotId}/video-takes/{takeId}/select")
    public R<com.astra.freyja.dto.drama.ShotVideoTakeSelectVO> selectVideoTake(
            @PathVariable Long shotId,
            @PathVariable Long takeId) {
        return R.ok(shotVideoTakeService.selectTake(shotId, takeId));
    }

    @DeleteMapping("/{shotId}/video-takes/{takeId}")
    public R<Void> deleteVideoTake(@PathVariable Long shotId, @PathVariable Long takeId) {
        shotVideoTakeService.deleteTake(shotId, takeId);
        return R.ok();
    }
}
