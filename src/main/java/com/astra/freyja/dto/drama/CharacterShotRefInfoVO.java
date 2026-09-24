package com.astra.freyja.dto.drama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分镜关联角色及造型/动作丰富展示 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CharacterShotRefInfoVO {

    private Long characterId;
    private String characterName;
    private String roleType;
    private Long lookId;

    private String outfitName;

    /** 人物身份设定参考图URL */
    private String characterReferenceImageUrl;

    /** 造型专属参考图URL (COMBINED) */
    private String outfitReferenceImageUrl;

    /** 造型视觉概念描述 (颜色/面料/轮廓/配饰/妆发) */
    private String designDesc;

    /** 服饰装扮Prompt (英文) */
    private String outfitPrompt;

    /** 造型特有外貌与妆发Prompt */
    private String appearancePrompt;

    private String actionPrompt;
    private String emotionPrompt;
    private String positionTag;
}
