package com.astra.freyja.dto.drama;

import lombok.Data;

/**
 * 镜头组拆分请求 DTO。
 * 从指定的分镜开始，将原镜头组拆分为两个独立的镜头组。
 */
@Data
public class DramaShotGroupSplitDTO {

    /** 原镜头组 ID */
    private Long sourceGroupId;

    /** 拆分起点的分镜 ID (该分镜及其后续分镜将移入新组) */
    private Long splitAtShotId;

    /** 新镜头组名称 (如: "双方发生口角并对峙") */
    private String newGroupName;

    /** 新镜头组目的 (如: "制造戏剧高潮") */
    private String newGroupPurpose;
}
