package com.astra.freyja.dto.render;

import lombok.Data;

/**
 * 历史渲染任务分页查询参数。
 */
@Data
public class RenderTaskQuery {

    private Integer current = 1;
    private Integer size = 10;

    /** 关联短剧 ID */
    private Long dramaId;

    /** 关联剧集 ID */
    private Long episodeId;

    /** 任务类型: SHOT_FRAME, SHOT_VIDEO, ASSET_IMAGE (GROUP_SERIAL is legacy history) */
    private String taskType;

    /** 任务状态: SUCCESS, FAILED, CANCELLED */
    private String status;

    /** 任务名称或提示词模糊搜索 */
    private String keyword;

    /** 起始提交时间 */
    private String startTime;

    /** 截止提交时间 */
    private String endTime;
}
