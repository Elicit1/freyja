package com.astra.freyja.service;

import com.astra.freyja.dto.video.ResolvedVideoProcessSource;
import com.astra.freyja.dto.video.VideoProcessProbeSourceDTO;
import com.astra.freyja.dto.video.VideoProcessSubmitDTO;

/**
 * 视频后处理来源解析与安全校验器。
 */
public interface VideoProcessSourceResolver {

    /**
     * 解析任务提交请求中的视频源
     */
    ResolvedVideoProcessSource resolve(VideoProcessSubmitDTO dto);

    /**
     * 解析探测请求中的视频源
     */
    ResolvedVideoProcessSource resolveForProbe(VideoProcessProbeSourceDTO dto);

    /**
     * 校验视频 URL 的安全性 (防止 SSRF / 非法协议 / 私网穿透)
     */
    void validateUrlSafety(String url);
}
