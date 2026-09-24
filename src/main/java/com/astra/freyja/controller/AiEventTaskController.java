package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.dto.drama.DramaShotFirstFrameDTO;
import com.astra.freyja.dto.drama.ShotAiVisualPlanRequestDTO;
import com.astra.freyja.dto.voice.CharacterVoiceDesignDTO;
import com.astra.freyja.dto.voice.ShotVoiceGenerateDTO;
import com.astra.freyja.dto.script.ScriptDecomposeRequestDTO;
import com.astra.freyja.dto.script.WorkerRetryDTO;
import com.astra.freyja.dto.script.AiTaskDTO;
import com.astra.freyja.entity.enums.AiTaskType;
import com.astra.freyja.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Entry points for AI prompt tasks on the shared task-center WebSocket. */
@RestController
@RequestMapping("/ai/tasks")
@RequiredArgsConstructor
public class AiEventTaskController {
    private final AiEventTaskService tasks;
    private final ResCharacterService characters;
    private final ResCharacterOutfitService outfits;
    private final ResSceneService scenes;
    private final ResPropService props;
    private final ShotAiVisualPlanService visualPlans;
    private final CharacterVoiceService characterVoices;
    private final DramaShotVoiceService shotVoices;
    private final DramaShotService shots;
    private final AssetImageGenerationService images;
    private final ScriptDecomposeService scripts;
    private final AiTaskService taskDetails;

    @GetMapping("/{taskId}")
    public R<AiTaskDTO> getTask(@PathVariable Long taskId) {
        return R.ok(taskDetails.getTaskById(taskId));
    }

    @PostMapping("/character-prompt")
    public R<String> characterPrompt(@RequestBody CharacterVisualPromptDeriveDTO dto) {
        return R.ok(tasks.start(AiTaskType.CHARACTER_PROMPT_DERIVE, dto.getDramaId(),
                String.valueOf(dto.getCharacterId()), dto.getModelCode(), dto, () -> characters.deriveVisualPrompt(dto)));
    }

    @PostMapping("/look-prompt")
    public R<String> lookPrompt(@RequestBody OutfitPromptDeriveDTO dto) {
        return R.ok(tasks.start(AiTaskType.LOOK_PROMPT_DERIVE, dto.getDramaId(),
                String.valueOf(dto.getLookId()), dto.getModelCode(), dto, () -> outfits.deriveOutfitPrompt(dto)));
    }

    @PostMapping("/scene-prompt")
    public R<String> scenePrompt(@RequestBody ScenePromptDeriveDTO dto) {
        return R.ok(tasks.start(AiTaskType.SCENE_PROMPT_DERIVE, dto.getDramaId(),
                String.valueOf(dto.getSceneId()), dto.getModelCode(), dto, () -> scenes.derivePrompts(dto)));
    }

    @PostMapping("/prop-prompt")
    public R<String> propPrompt(@RequestBody PropPromptDeriveDTO dto) {
        return R.ok(tasks.start(AiTaskType.PROP_PROMPT_DERIVE, dto.getDramaId(),
                String.valueOf(dto.getPropId()), dto.getModelCode(), dto, () -> props.derivePrompts(dto)));
    }

    @PostMapping("/shot/{shotId}/visual-plan")
    public R<String> visualPlan(@PathVariable Long shotId, @RequestBody(required = false) ShotAiVisualPlanRequestDTO dto) {
        var input = dto == null ? new ShotAiVisualPlanRequestDTO() : dto;
        return R.ok(tasks.start(AiTaskType.SHOT_VISUAL_PLAN, null, String.valueOf(shotId),
                input.getModelCode(), input, () -> visualPlans.generate(shotId, input)));
    }

    @PostMapping("/character/{characterId}/voice-design")
    public R<String> voiceDesign(@PathVariable Long characterId, @RequestBody CharacterVoiceDesignDTO dto) {
        dto.setCharacterId(characterId);
        return R.ok(tasks.start(AiTaskType.CHARACTER_VOICE_DESIGN, null, String.valueOf(characterId),
                dto.getModelCode(), dto, () -> characterVoices.designCharacterVoice(characterId, dto)));
    }

    @PostMapping("/voice-preview")
    public R<String> voicePreview(@RequestBody CharacterVoiceDesignDTO dto) {
        return R.ok(tasks.start(AiTaskType.VOICE_PREVIEW, null, String.valueOf(dto.getCharacterId()),
                dto.getModelCode(), dto, () -> characterVoices.previewVoiceDesign(dto)));
    }

    @PostMapping("/shot/{shotId}/voice")
    public R<String> shotVoice(@PathVariable Long shotId, @RequestBody(required = false) ShotVoiceGenerateDTO dto) {
        var input = dto == null ? new ShotVoiceGenerateDTO() : dto;
        input.setShotId(shotId);
        return R.ok(tasks.start(AiTaskType.SHOT_VOICE_GENERATE, null, String.valueOf(shotId),
                input.getModelCode(), input, () -> shotVoices.generateShotVoice(shotId, input)));
    }

    @PostMapping("/episode/{episodeId}/voice")
    public R<String> episodeVoice(@PathVariable Long episodeId) {
        return R.ok(tasks.start(AiTaskType.EPISODE_VOICE_GENERATE, null, String.valueOf(episodeId),
                null, java.util.Map.of("episodeId", String.valueOf(episodeId)),
                () -> shotVoices.batchGenerateEpisodeVoice(episodeId)));
    }

    @PostMapping("/shot/{shotId}/frame")
    public R<String> shotFrame(@PathVariable Long shotId, @RequestBody(required = false) DramaShotFirstFrameDTO dto) {
        var input = dto == null ? new DramaShotFirstFrameDTO() : dto;
        return R.ok(tasks.start(AiTaskType.SHOT_FRAME_GENERATE, null, String.valueOf(shotId),
                input.getModelCode(), input, () -> shots.generateFirstFrame(shotId, input)));
    }

    @PostMapping("/asset-image")
    public R<String> assetImage(@RequestBody AssetImageGenerationRequest dto) {
        return R.ok(tasks.start(AiTaskType.ASSET_IMAGE_GENERATE, null, String.valueOf(dto.getTargetId()),
                dto.getModelCode(), dto, () -> images.submit(dto)));
    }

    @PostMapping("/episode/decompose")
    public R<String> episodeDecompose(@RequestBody ScriptDecomposeRequestDTO dto) {
        return R.ok(tasks.start(AiTaskType.EPISODE_DECOMPOSE, dto.getDramaId(),
                String.valueOf(dto.getEpisodeId()), dto.getModelCode(), dto,
                () -> scripts.decomposeEpisode(dto)));
    }

    @PostMapping("/worker/{taskId}/retry")
    public R<String> retryWorker(@PathVariable Long taskId, @RequestBody WorkerRetryDTO dto) {
        return R.ok(tasks.startWorkerRetry(taskId, dto.getModelCodeOverride(), dto,
                () -> scripts.retryWorker(taskId, dto)));
    }
}
