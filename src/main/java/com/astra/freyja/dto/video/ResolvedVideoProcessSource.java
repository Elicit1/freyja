package com.astra.freyja.dto.video;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 经过校验解析后的不可变视频处理源引用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolvedVideoProcessSource {

    /** 来源类型: DIRECT_URL / SHOT_CURRENT / SHOT_VIDEO_TAKE */
    private String sourceType;

    /** 真实有效的源视频 URL 快照 */
    private String videoUrl;

    /** 所属短剧 ID */
    private Long dramaId;

    /** 所属剧集 ID */
    private Long episodeId;

    /** 所属场次 ID */
    private Long sceneId;

    /** 所属分镜 ID */
    private Long shotId;

    /** 关联的 Take ID */
    private Long videoTakeId;

    /** 分镜序号 */
    private Integer shotNo;

    /** 镜头标识名称 */
    private String shotName;

    /** 短剧标题 */
    private String dramaTitle;

    /** 剧集标题 */
    private String episodeTitle;

    /** 场次名称 */
    private String sceneName;
}
