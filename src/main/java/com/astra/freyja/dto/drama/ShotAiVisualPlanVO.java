package com.astra.freyja.dto.drama;

import com.astra.freyja.dto.res.ControlImageVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分镜 AI 视觉方案。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShotAiVisualPlanVO {

    private Long shotId;
    private String firstFramePrompt;
    private String negativePrompt;
    private String videoPrompt;
    private List<ControlImageVO> controlImages;
}
