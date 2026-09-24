package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分镜视频抽卡候选版本选择响应 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShotVideoTakeSelectVO {

    private Long shotId;
    private Long previousTakeId;
    private Long currentTakeId;
    private String videoUrl;
    private Boolean changed;
}
