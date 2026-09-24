package com.astra.freyja.dto.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 切换 AI 技能版本请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSkillSwitchVersionDTO implements Serializable {

    /** 目标历史版本 ID */
    private Long versionId;
}
