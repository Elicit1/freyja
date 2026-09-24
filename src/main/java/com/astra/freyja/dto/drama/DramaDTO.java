package com.astra.freyja.dto.drama;

import lombok.Data;

/**
 * 短剧项目新增/更新请求 DTO。
 */
@Data
public class DramaDTO {

    /** 短剧 ID，修改时必传 */
    private Long id;

    /** 短剧名称 */
    private String title;

    /** 短剧封面图 URL (MinIO) */
    private String coverUrl;

    /** 题材类型 (字典 drama_genre) */
    private String genre;

    /** 规划目标集数 */
    private Integer targetEpisodes;

    /** 画幅比例 (9:16/16:9/1:1/4:3) */
    private String aspectRatio;

    /** 默认风格预设 (字典 drama_style_preset) */
    private String stylePreset;

    /** 视觉风格基调/导演风格指南（自然语言融入，如光影、色调、镜头质感） */
    private String styleTone;

    /** 短剧故事梗概/大纲 */
    private String synopsis;

    /** 状态 (PLANNING/IN_PROGRESS/COMPLETED/ARCHIVED) */
    private String status;

    /** 显示排序 */
    private Integer sortOrder;

    /** 备注 */
    private String remark;
}
