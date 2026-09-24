package com.astra.freyja.dto.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色合并操作请求 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharacterMergeDTO {

    /** 待合并源角色 ID (将被合并并标记为 MERGED) */
    private Long sourceCharacterId;

    /** 目标主角色 ID (合并保留的主角色) */
    private Long targetCharacterId;

    /** 归属短剧 ID (可选) */
    private Long dramaId;

    /** 合并原因与说明 */
    private String reason;
}
