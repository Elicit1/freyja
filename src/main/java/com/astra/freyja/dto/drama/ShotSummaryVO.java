package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 大纲树中的分镜摘要叶子节点 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShotSummaryVO {

    private Long id;
    private Long sceneId;
    private Long shotGroupId;
    private Integer shotNo;
    private String shotName;
    private String shotType;
    private String cameraMovement;
    private BigDecimal duration;
    private String actionDescription;
    private String dialogue;
    private String dialogueSpeaker;
    private String renderStatus;
    private String generationMode;
    private String previewImageUrl;
    private String endFrameImageUrl;
    private String videoUrl;
    private Integer sortOrder;
}
