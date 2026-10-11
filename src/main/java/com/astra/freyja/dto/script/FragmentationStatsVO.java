package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 镜头时长分布。保留历史字段名与分桶边界，短时长不代表剧情过碎。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FragmentationStatsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总镜头数 */
    @Builder.Default
    private Integer totalShots = 0;

    /** 短镜头数 (< 5s) */
    @Builder.Default
    private Integer shortShots = 0;

    /** 中等时长镜头数 (5~8s)，不是推荐时长 */
    @Builder.Default
    private Integer normalShots = 0;

    /** 较长时长镜头数 (> 8s) */
    @Builder.Default
    private Integer longShots = 0;

    /** 平均镜头时长 (秒) */
    @Builder.Default
    private Double averageDuration = 0.0;

    /** 短时长镜头占比 (shortShots / totalShots)，不作碎片率判断 */
    @Builder.Default
    private Double shortShotRatio = 0.0;

    /** 已停用的历史兼容字段；新结果固定 false，不由时长猜测叙事边界 */
    @Builder.Default
    private Boolean isHighFragmentation = false;

    /** 已停用的历史兼容字段；新结果为空 */
    private String warningMessage;
}
