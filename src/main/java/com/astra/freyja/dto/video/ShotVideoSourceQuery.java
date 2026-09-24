package com.astra.freyja.dto.video;

import lombok.Data;

/**
 * 分镜视频选择器分页查询条件。
 */
@Data
public class ShotVideoSourceQuery {

    /** 所属短剧 ID */
    private Long dramaId;

    /** 所属剧集 ID */
    private Long episodeId;

    /** 所属场次 ID */
    private Long sceneId;

    /** 关键词搜索 (匹配 shotName 或 shotNo) */
    private String keyword;

    /** 当前页码 (默认 1) */
    private Integer current = 1;

    /** 每页条数 (默认 20，最大 50) */
    private Integer size = 20;
}
