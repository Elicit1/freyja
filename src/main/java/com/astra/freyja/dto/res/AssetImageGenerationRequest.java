package com.astra.freyja.dto.res;

import lombok.Data;

import java.util.List;

/** 资产参考图生成请求。 */
@Data
public class AssetImageGenerationRequest {
    private String targetType;
    private Long targetId;
    private String slot;
    private Long providerId;
    private String modelCode;
    private String prompt;
    private String negativePrompt;
    private List<String> referenceImageUrls;
    private String size;
    private Long seed;
}
