package com.astra.freyja.dto.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 角色消歧未决项人工决议 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResCharacterResolutionDTO {

    /** 消歧记录 ID */
    private Long resolutionId;

    /** 决议动作: RESOLVE(绑定指定角色), CREATE_NEW(作为新角色独立), IGNORE(忽略) */
    private String action;

    /** 决议绑定的目标角色 ID (action=RESOLVE 时必填) */
    private Long targetCharacterId;

    /** 若创建新角色时的正式姓名 */
    private String newCharacterName;

    /** 决议说明 */
    private String remark;
}
