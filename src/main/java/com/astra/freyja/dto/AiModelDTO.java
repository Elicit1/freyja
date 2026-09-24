package com.astra.freyja.dto;

import lombok.Data;

/**
 * AI 模型新增/修改请求体。
 */
@Data
public class AiModelDTO {

    /** 主键，修改时必传 */
    private Long id;

    /** 归属提供商 ID */
    private Long providerId;

    /** 模型标识，同提供商下唯一 */
    private String modelCode;

    /** 模型名称（展示用） */
    private String modelName;

    /** 模型类型 CHAT/TXT2IMG/IMG2IMG/TXT_IMG2IMG/TXT2VIDEO_FIRST_LAST/TXT2VIDEO_REF/TTS/LIP_SYNC/VIDEO_UPSCALE/FRAME_INTERPOLATION/EMBEDDING */
    private String modelType;

    /** 采样温度 */
    private Double temperature;

    /** 最大输出 Token 数 */
    private Integer maxTokens;

    /** 核采样概率 */
    private Double topP;

    /** 额外模型参数 JSON */
    private String paramsJson;

    /** 最大参考图数限制 (图生图/图生视频模型必填) */
    private Integer maxImages;

    /** 最大参考音频数限制 (图生视频模型扩展配置) */
    private Integer maxAudios;

    /** 最大参考视频数限制 (图生视频模型扩展配置) */
    private Integer maxVideos;

    /** 显示排序 */
    private Integer sortOrder;

    /** 状态 0-停用 1-启用 */
    private Integer status;

    /** 备注 */
    private String remark;
}