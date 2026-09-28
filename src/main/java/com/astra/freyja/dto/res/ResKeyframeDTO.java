package com.astra.freyja.dto.res;

import lombok.Data;

/**
 * 关键帧资产新增/修改请求 DTO。
 */
@Data
public class ResKeyframeDTO {

    /** 关键帧ID，修改时必传 */
    private Long id;

    /** 归属短剧ID，0为公共资源库 */
    private Long dramaId;

    /** 关联分镜镜头ID (drama_shot.id)，为空表示未绑定具体分镜 */
    private Long shotId;

    /** 关键帧名称 (如: 开场回眸特写帧、雨夜决战首帧) */
    private String name;

    /** 关键帧类型: FIRST_FRAME(首帧)/END_FRAME(尾帧)/KEYFRAME(普通关键帧)/ACTION_BEAT(动作节奏帧) */
    private String frameType;

    /** 关键帧图片URL (MinIO URL) */
    private String frameUrl;

    /** 关键帧生图/视觉控制Prompt (英文提示词) */
    private String prompt;

    /** 专属负向Prompt */
    private String negativePrompt;

    /** 画面动作、构图与镜头细节描述 */
    private String description;

    /** 来源: MANUAL_UPLOAD(手动上传)/AI_GENERATED(AI生图)/SHOT_EXTRACT(分镜抽取)/VIDEO_FRAME(视频截帧) */
    private String sourceType;

    /** 画面画幅比例 (如 16:9, 9:16, 1:1) */
    private String aspectRatio;

    /** 显示排序 */
    private Integer sortOrder;

    /** 状态 0-停用 1-启用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
