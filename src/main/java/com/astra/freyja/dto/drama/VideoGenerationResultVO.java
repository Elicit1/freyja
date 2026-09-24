package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 视频生成与 MinIO 归档结果 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoGenerationResultVO {

    /** 归档后可直接访问的 MinIO 视频 URL (.mp4) */
    private String videoUrl;

    /** 从视频中抽取的末帧图 MinIO URL (.jpg)，用于下游镜头连续性接续 */
    private String lastFrameUrl;

    /** 模型实际执行的修订后提示词 (如包含参考关系绑定的 prompt) */
    private String revisedPrompt;
}
