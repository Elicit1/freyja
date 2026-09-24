package com.astra.freyja.dto.res;

import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.entity.ResCharacterLook;
import com.astra.freyja.entity.ResCharacterOutfit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色与造型视觉资产解析结果。
 * 统一承载运行时角色 Prompt 组装与单一参考图决策结果。
 * 铁律：明确指定 lookId 则只使用该造型图，禁止借图；未指定则查默认造型；无默认造型则只使用人物文字 Prompt。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolvedCharacterVisual {

    /** 关联的基础人物实体 */
    private ResCharacter character;

    /** 关联的具体造型变体（若无造型则为 null） */
    private ResCharacterLook look;

    /** 兼容旧引用 outfit */
    public ResCharacterOutfit getOutfit() {
        if (look == null) return null;
        if (look instanceof ResCharacterOutfit outfit) return outfit;
        ResCharacterOutfit o = new ResCharacterOutfit();
        org.springframework.beans.BeanUtils.copyProperties(look, o);
        return o;
    }

    public void setOutfit(ResCharacterOutfit outfit) {
        this.look = outfit;
    }

    /** 正向提示词片段列表 (人物外观 + 造型附加状态 + 造型服装) */
    @Builder.Default
    private List<String> positivePromptSegments = new ArrayList<>();

    /** 负向提示词片段列表 (人物全局负向 + 造型专属负向) */
    @Builder.Default
    private List<String> negativePromptSegments = new ArrayList<>();

    /** 决议出的唯一参考图URL (只能是当前具体造型或默认造型的参考图，绝不借用其他造型图) */
    private String referenceImageUrl;

    /** 参考图角色定位: COMBINED(造型+身份一体图) */
    private String referenceRole;

    /** 图片一致性状态: MISSING / SYNCED / STALE */
    private String imageStatus;

    /** 提示词图文不一致警告信息 */
    private String staleWarning;
}
