package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.voice.BatchVoiceResultVO;
import com.astra.freyja.dto.voice.ShotVoiceGenerateDTO;
import com.astra.freyja.service.DramaShotVoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 分镜台词克隆配音与剧集批量配音 Controller (MiMo-V2.5-TTS-VoiceClone)
 */
@RestController
@RequestMapping("/drama")
@RequiredArgsConstructor
public class DramaShotVoiceController {

    private final DramaShotVoiceService dramaShotVoiceService;

    /**
     * 单分镜台词克隆配音合成
     */
    @PostMapping("/shot/{shotId}/voice/generate")
    public R<BatchVoiceResultVO.ShotVoiceItemResult> generateShotVoice(
            @PathVariable("shotId") Long shotId,
            @RequestBody(required = false) ShotVoiceGenerateDTO dto) {
        if (dto == null) {
            dto = new ShotVoiceGenerateDTO();
        }
        dto.setShotId(shotId);
        return R.ok(dramaShotVoiceService.generateShotVoice(shotId, dto));
    }

    /**
     * 一键整集批量分镜台词克隆配音
     */
    @PostMapping("/episode/{episodeId}/voice/batch-generate")
    public R<BatchVoiceResultVO> batchGenerateEpisodeVoice(
            @PathVariable("episodeId") Long episodeId) {
        return R.ok(dramaShotVoiceService.batchGenerateEpisodeVoice(episodeId));
    }
}
