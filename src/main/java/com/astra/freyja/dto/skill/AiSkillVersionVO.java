package com.astra.freyja.dto.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 技能版本视图展示对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSkillVersionVO implements Serializable {

    private Long id;

    private Long skillId;

    private String skillName;

    /** 递增版本号，如 1、2、3。 */
    private String version;

    /** CURRENT / HISTORY，仅用于前端标识当前指针。 */
    private String status;

    /** 版本 SHA-256 哈希 */
    private String contentHash;

    /** 解压总大小 (字节) */
    private Long contentSize;

    /** 上传创建时间 */
    private LocalDateTime createTime;
}
