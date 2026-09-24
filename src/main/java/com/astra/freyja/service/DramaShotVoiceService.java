package com.astra.freyja.service;

import com.astra.freyja.dto.voice.BatchVoiceResultVO;
import com.astra.freyja.dto.voice.ShotVoiceGenerateDTO;

/**
 * 分镜台词克隆配音与剧集批量配音服务 (MiMo-V2.5-TTS-VoiceClone)
 */
public interface DramaShotVoiceService {

    /**
     * 单分镜台词声音克隆配音合成
     *
     * @param shotId 分镜 ID
     * @param dto    配音生成参数
     * @return 包含生成的音频 URL 及音频时长等信息的配音结果
     */
    BatchVoiceResultVO.ShotVoiceItemResult generateShotVoice(Long shotId, ShotVoiceGenerateDTO dto);

    /**
     * 一键整集批量分镜台词克隆配音
     *
     * @param episodeId 剧集 ID
     * @return 整集批量配音执行统计结果
     */
    BatchVoiceResultVO batchGenerateEpisodeVoice(Long episodeId);
}
