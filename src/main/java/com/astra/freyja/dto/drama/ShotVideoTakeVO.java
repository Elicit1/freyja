package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分镜视频抽卡候选版本 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShotVideoTakeVO {

    private Long id;
    private Long shotId;
    private Integer takeNo;
    private String taskId;
    private String sourceType;
    private String status;
    private String videoUrl;
    private Boolean current;
    private Long providerId;
    private String providerName;
    private String modelCode;
    private String generationMode;
    private Long seed;
    private String size;
    private BigDecimal duration;
    private String promptSnapshot;
    private String negativePromptSnapshot;
    private String firstFrameUrl;
    private String endFrameUrl;
    private LocalDateTime createTime;
}
