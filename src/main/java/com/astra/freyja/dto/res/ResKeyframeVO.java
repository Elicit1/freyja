package com.astra.freyja.dto.res;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关键帧资产展示 VO。
 */
@Data
public class ResKeyframeVO {

    /** 关键帧ID */
    private Long id;

    /** 归属短剧ID，0为公共资源库 */
    private Long dramaId;

    /** 归属短剧名称 (为0时展示全局公共库) */
    private String dramaTitle;

    /** 关联分镜镜头ID */
    private Long shotId;

    /** 关联分镜名称标识 (如: S01-01) */
    private String shotName;

    /** 关联分镜序号 (如: 1) */
    private Integer shotNo;

    /** 关键帧名称 (如: 开场回眸特写帧、雨夜决战首帧) */
    private String name;

    /** 关键图在目标视频中的默认 Picture 用途。 */
    private String frameType;

    /** 关键帧图片URL */
    private String frameUrl;

    /** 关键帧生图/视觉控制Prompt */
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

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
