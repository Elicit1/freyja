package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 镜头时长与碎片率统计指标 (FragmentationStatsVO)。
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

    /** 标准镜头数 (5~8s) */
    @Builder.Default
    private Integer normalShots = 0;

    /** 长镜头/复杂镜头数 (> 8s) */
    @Builder.Default
    private Integer longShots = 0;

    /** 平均镜头时长 (秒) */
    @Builder.Default
    private Double averageDuration = 0.0;

    /** 短镜头碎片率 (shortShots / totalShots) */
    @Builder.Default
    private Double shortShotRatio = 0.0;

    /** 是否触发过碎预警 (shortShotRatio > 30%) */
    @Builder.Default
    private Boolean isHighFragmentation = false;

    /** 预警提示信息 */
    private String warningMessage;
}
