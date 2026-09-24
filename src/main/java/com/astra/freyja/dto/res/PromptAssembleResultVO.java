package com.astra.freyja.dto.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分镜 Prompt 动态组装结果 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptAssembleResultVO {

    /** 最终拼装与格式化后的正向 Prompt (适用于 T2I 首帧生图) */
    private String positivePrompt;

    /** 专供 I2V 视频生成的极简动态运镜 Prompt */
    private String videoPrompt;

    /** 最终拼装与去重后的负向 Prompt */
    private String negativePrompt;

    /** 聚合后的 LoRA 加载列表 */
    private List<LoraItemVO> loraList;

    /** 聚合后的多模态控制参考图列表 (FaceID / 参考图) */
    private List<ControlImageVO> controlImages;

    /** 选中的场景摘要信息 */
    private ResSceneVO sceneSummary;

    /** 选中的角色及造型摘要列表 */
    private List<ResCharacterVO> characterSummaries;
}
