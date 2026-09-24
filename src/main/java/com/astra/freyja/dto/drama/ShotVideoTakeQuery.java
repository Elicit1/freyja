package com.astra.freyja.dto.drama;

import lombok.Data;

/**
 * 分镜视频抽卡候选版本分页查询入参。
 */
@Data
public class ShotVideoTakeQuery {

    /** 当前页码，默认 1 */
    private long current = 1;

    /** 每页条数，默认 12，最大 50 */
    private long size = 12;

    /** 资产状态过滤，首版默认 AVAILABLE */
    private String status = "AVAILABLE";

    public long getSize() {
        if (size <= 0) {
            return 12;
        }
        return Math.min(size, 50);
    }

    public long getCurrent() {
        return Math.max(current, 1);
    }
}
