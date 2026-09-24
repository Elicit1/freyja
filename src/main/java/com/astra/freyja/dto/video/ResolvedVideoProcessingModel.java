package com.astra.freyja.dto.video;

import com.astra.freyja.entity.AiModel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 经校验解析后的视频后处理模型及其受控网关参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolvedVideoProcessingModel {

    /** 模型实体 */
    private AiModel model;

    /** 模型 ID */
    private Long modelId;

    /** 模型标识代码 */
    private String modelCode;

    /** 模型展示名称 */
    private String modelName;

    /** 模型参数配置 */
    private VideoProcessingModelParams params;

    /** 输入源视频帧率 */
    private int sourceFps;

    /** 目标输出帧率 */
    private int targetFps;

    /** 超分放大倍率 */
    private int scale;

    /** 补帧倍率 */
    private int multiplier;

    /** CRF 质量压缩系数 */
    private int crf;

    /** 显存清理间隔帧数 */
    private int clearCacheFrames;

    /** 发送给网关的受控 extra_body 白名单参数 */
    private Map<String, Object> extraBody;
}
