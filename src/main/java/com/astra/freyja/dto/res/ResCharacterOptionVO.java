package com.astra.freyja.dto.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 角色下拉选项 VO（用于分镜制作快速引用）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResCharacterOptionVO {

    private Long id;

    private Long dramaId;

    private String name;

    private String referenceImageUrl;

    private String roleType;

    private String triggerWords;

    private String appearancePrompt;

    private String voiceSampleUrl;

    private String voiceSampleText;

    private String voiceDesc;

    private List<ResCharacterOutfitVO> outfits;
}
