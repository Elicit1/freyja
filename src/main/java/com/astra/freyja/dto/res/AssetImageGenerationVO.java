package com.astra.freyja.dto.res;

import lombok.Builder;
import lombok.Data;

/** 资产参考图生成任务结果。 */
@Data
@Builder
public class AssetImageGenerationVO {
    private String taskId;
    private String mode;
    private String status;
    private Integer progress;
    private String outputUrl;
    private String errorMessage;
}
