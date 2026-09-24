package com.astra.freyja.dto.drama;

import lombok.Data;

/**
 * 短剧项目分页查询参数。
 */
@Data
public class DramaQuery {

    private Integer current = 1;
    private Integer size = 10;

    /** 短剧名称模糊搜索 */
    private String title;

    /** 题材类型 */
    private String genre;

    /** 状态 */
    private String status;
}
