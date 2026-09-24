package com.astra.freyja.dto.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * AI 技能标准包版本预览对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSkillPreviewVO implements Serializable {

    private AiSkillVersionVO version;

    /** 标准 SKILL.md 正文。 */
    private String content;

    /** 标准 Skill 包内文件索引。 */
    private List<AiSkillFileVO> files;
}
