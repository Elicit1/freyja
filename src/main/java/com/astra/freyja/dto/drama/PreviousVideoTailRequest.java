package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 从上一镜视频提取并引用尾帧请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreviousVideoTailRequest {

    /** 是否强制重新提取 (默认 false，若已提取且视频版本一致则复用缓存) */
    private Boolean forceExtract;
}
