package com.astra.freyja.dto.res;

import lombok.Data;

/**
 * 场景环境分页查询参数。
 */
@Data
public class ResSceneQuery {

    /** 当前页，默认 1 */
    private Integer current = 1;

    /** 每页大小，默认 10 */
    private Integer size = 10;

    /** 归属短剧ID，0或null为全部公共库/不限 */
    private Long dramaId;

    /** 场景名称模糊查询 */
    private String name;

    /** 空间类型 INDOOR/OUTDOOR/STUDIO/VIRTUAL */
    private String sceneType;

    /** 时间时段 DAY/NIGHT/SUNSET/DAWN */
    private String timeOfDay;

    /** 天气氛围 */
    private String weatherAtmosphere;

    /** 状态 0-停用 1-启用 */
    private Integer status;
}
