package com.astra.freyja.dto.res;

import lombok.Data;

/**
 * 道具下拉选项与分镜装配轻量级 VO。
 */
@Data
public class ResPropOptionVO {

    /** 道具ID */
    private Long id;

    /** 归属短剧ID */
    private Long dramaId;

    /** 道具中文名称 */
    private String name;

    /** 道具类型 */
    private String propType;

    /** 英文生图/分镜组装 Prompt */
    private String propPrompt;

    /** 道具参考图/设计图URL */
    private String coverUrl;
}
