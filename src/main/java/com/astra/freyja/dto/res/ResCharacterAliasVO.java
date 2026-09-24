package com.astra.freyja.dto.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 角色别名响应 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResCharacterAliasVO {

    private Long id;

    private Long dramaId;

    private Long characterId;

    private String alias;

    private String aliasType;

    private Long sourceEpisodeId;

    private BigDecimal confidence;

    private Integer status;

    private String remark;

    private LocalDateTime createTime;
}
