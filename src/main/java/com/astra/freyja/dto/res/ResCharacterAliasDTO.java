package com.astra.freyja.dto.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 角色别名请求 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResCharacterAliasDTO {

    private Long id;

    private Long dramaId;

    private Long characterId;

    /** 别名/提及称谓 */
    private String alias;

    /** 别名类型: NAME, PRONOUN, DESCRIPTION, TITLE, NICKNAME, ROLE, OTHER */
    private String aliasType;

    private Long sourceEpisodeId;

    private BigDecimal confidence;

    private Integer status;

    private String remark;
}
