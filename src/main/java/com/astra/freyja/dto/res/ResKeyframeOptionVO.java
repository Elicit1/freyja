package com.astra.freyja.dto.res;

import lombok.Data;

/**
 * 关键帧资产轻量级下拉选项 VO。
 */
@Data
public class ResKeyframeOptionVO {

    /** 关键帧ID */
    private Long id;

    /** 关键帧名称 */
    private String name;

    /** 关键帧类型 */
    private String frameType;

    /** 图片预览URL */
    private String frameUrl;

    /** 归属短剧ID */
    private Long dramaId;

    /** 关联分镜ID */
    private Long shotId;

    /** 提示词 */
    private String prompt;
}
