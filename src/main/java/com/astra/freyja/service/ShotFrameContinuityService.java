package com.astra.freyja.service;

import com.astra.freyja.dto.drama.PreviousVideoTailRequest;
import com.astra.freyja.dto.drama.PreviousVideoTailVO;

/**
 * 镜头视听连续性服务 (首尾帧继承与尾部稳定帧按需提取)。
 */
public interface ShotFrameContinuityService {

    /**
     * 从同镜头组的直接前驱镜头视频中按需提取尾帧并设置为当前镜首帧。
     *
     * @param currentShotId 当前分镜 ID
     * @param request        提取参数 (强制提取标志、偏移量)
     * @return 提取与引用结果
     */
    PreviousVideoTailVO inheritPreviousVideoTail(Long currentShotId, PreviousVideoTailRequest request);
}
