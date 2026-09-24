package com.astra.freyja.dto.voice;

import lombok.Data;
import java.util.List;

/**
 * 剧集批量配音执行结果 VO
 */
@Data
public class BatchVoiceResultVO {

    /** 剧集 ID */
    private Long episodeId;

    /** 总分镜数 */
    private Integer totalShots;

    /** 待配音分镜数 */
    private Integer matchedShots;

    /** 成功配音数 */
    private Integer successCount;

    /** 失败分镜数 */
    private Integer failureCount;

    /** 跳过分镜数 (如无台词或无匹配音色) */
    private Integer skippedCount;

    /** 明细结果列表 */
    private List<ShotVoiceItemResult> items;

    @Data
    public static class ShotVoiceItemResult {
        private Long shotId;
        private Integer shotNo;
        private String shotName;
        private String speakerName;
        private String dialogue;
        private String audioUrl;
        private Double duration;
        private Boolean success;
        private String message;
    }
}
