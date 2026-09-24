package com.astra.freyja.dto.drama;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上一镜视频尾帧提取与引用响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreviousVideoTailVO {

    /** 当前分镜 ID */
    private Long currentShotId;

    /** 来源分镜 ID (上一镜) */
    private Long sourceShotId;

    /** 来源分镜序号 */
    private Integer sourceShotNo;

    /** 来源分镜标识/名称 */
    private String sourceShotName;

    /** 来源视频 URL */
    private String sourceVideoUrl;

    /** 提取或复用的尾帧图 URL (MinIO URL) */
    private String tailFrameUrl;

    /** 是否复用了已有缓存 */
    private Boolean reused;

    /** 是否已成功应用并绑定到当前分镜首帧 */
    private Boolean applied;
}
