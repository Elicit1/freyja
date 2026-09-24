package com.astra.freyja.dto.res;

import com.astra.freyja.entity.ResCharacter;
import com.astra.freyja.entity.ResCharacterLook;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

/**
 * 角色与造型提示词上下文解析结果。
 * 遵循严格的造型选择优先级与 SSOT 隔离：
 * 1. 有 lookId：请求字段优先，缺失由造型库补充，严禁使用 appearanceDesc，严禁查询默认造型；
 * 2. 无 lookId 但传了造型字段：视为显式造型，仅使用请求中的造型描述，严禁使用 appearanceDesc；
 * 3. 完全无造型信息：使用角色 appearanceDesc 兜底，严禁自动装配默认造型；
 * 4. lookId 无效或不属于该角色：直接抛出异常，不静默回退。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolvedCharacterPromptContext {

    /** 关联的基础人物实体 */
    private ResCharacter character;

    /** 关联的具体造型变体（若未通过 lookId 关联则可为 null） */
    private ResCharacterLook look;

    /** 造型 ID */
    private Long lookId;

    /** 是否有显式/指定造型 */
    private boolean hasLook;

    /** 角色原著外貌描述（仅在 !hasLook 时有值，有造型时严格为 null） */
    private String appearanceDesc;

    /** 中文造型视觉概念描述 */
    private String designDesc;

    /** 造型特有外貌与妆发 Prompt */
    private String appearancePrompt;

    /** 本镜装配服装 Prompt */
    private String outfitPrompt;

    /** 角色即时动作 Prompt */
    private String actionPrompt;

    /** 本镜情绪微表情 Prompt */
    private String emotionPrompt;

    /** 画面站位 */
    private String positionTag;

    /**
     * 格式化输出为角色文字上下文的一条记录（用于 buildCharacterContext）。
     */
    public String toCharacterContextItem() {
        if (character == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("- 角色: %s (定位: %s)\n",
                StringUtils.defaultIfBlank(character.getCanonicalName(), character.getName()),
                character.getRoleType()));

        if (!hasLook && StringUtils.isNotBlank(appearanceDesc)) {
            sb.append("  * 原著外貌视觉SSOT: ").append(appearanceDesc).append("\n");
        }
        if (StringUtils.isNotBlank(designDesc)) {
            sb.append("  * 中文造型视觉概念描述: ").append(designDesc).append("\n");
        }
        if (StringUtils.isNotBlank(appearancePrompt)) {
            sb.append("  * 造型特有外貌与妆发Prompt: ").append(appearancePrompt).append("\n");
        }
        if (StringUtils.isNotBlank(outfitPrompt)) {
            sb.append("  * 本镜装配服装Prompt: ").append(outfitPrompt).append("\n");
        }
        if (StringUtils.isNotBlank(actionPrompt)) {
            sb.append("  * 角色专属即时动作Prompt: ").append(actionPrompt).append("\n");
        }
        if (StringUtils.isNotBlank(emotionPrompt)) {
            sb.append("  * 本镜情绪与微表情: ").append(emotionPrompt).append("\n");
        }
        if (StringUtils.isNotBlank(positionTag)) {
            sb.append("  * 画面站位: ").append(positionTag).append("\n");
        }
        return sb.toString();
    }

    /**
     * 格式化输出为 ReferenceManifest 中 Picture 对应角色的特征描述（用于 buildReferenceManifest）。
     */
    public String toManifestDescription() {
        if (character == null) {
            return "";
        }
        StringBuilder dsb = new StringBuilder();
        if (!hasLook) {
            if (StringUtils.isNotBlank(appearanceDesc)) {
                dsb.append(appearanceDesc.trim());
            }
        } else {
            if (StringUtils.isNotBlank(outfitPrompt)) {
                dsb.append(outfitPrompt.trim());
            } else if (StringUtils.isNotBlank(designDesc)) {
                dsb.append(designDesc.trim());
            }
            if (StringUtils.isNotBlank(appearancePrompt)) {
                if (dsb.length() > 0) dsb.append(", ");
                dsb.append(appearancePrompt.trim());
            }
        }
        return dsb.toString();
    }
}
