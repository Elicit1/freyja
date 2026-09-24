package com.astra.freyja.dto.drama;

import lombok.Data;

import java.util.List;

/**
 * 镜头组合并请求 DTO。
 * 将多个连续镜头组合并为一个镜头组，并重新链接状态链。
 */
@Data
public class DramaShotGroupMergeDTO {

    /** 目标合并到的主镜头组 ID (若为空则合并入第一个组) */
    private Long targetGroupId;

    /** 待合并的镜头组 ID 列表 (按合并先后顺序) */
    private List<Long> sourceGroupIds;

    /** 合并后的新名称 (可选) */
    private String mergedGroupName;
}
