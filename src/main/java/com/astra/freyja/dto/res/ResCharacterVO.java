package com.astra.freyja.dto.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 人物角色详情响应 VO（含多造型列表、别名列表与身份状态）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResCharacterVO {

    private Long id;

    private Long dramaId;

    /** 人物名称/角色名 (展示名或正式名) */
    private String name;

    /** 正式规范姓名 (如 林婉清) */
    private String canonicalName;

    /** 展示称谓/初次提及称谓 (如 年轻女人) */
    private String displayName;

    /** 唯一人物身份设定参考图URL (MinIO) */
    private String referenceImageUrl;

    private String gender;

    private String ageGroup;

    private String roleType;

    /** 身份状态: UNKNOWN, PARTIAL, CONFIRMED, UNRESOLVED, MERGED */
    private String identityStatus;

    /** 若已合并，指向目标主角色ID */
    private Long mergedToId;

    private String personality;

    private String appearanceDesc;

    private String appearancePrompt;

    private String negativePrompt;

    private String triggerWords;

    private String loraName;

    private BigDecimal loraWeight;

    private String voiceId;
    private String voiceDesc;
    private String voiceSampleText;
    private String voiceSampleUrl;

    private Integer sortOrder;

    private Integer status;

    private String firstAppearance;

    private String lastAppearance;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 关联的造型列表 */
    private List<ResCharacterOutfitVO> outfits;

    /** 关联的造型列表 (新标准) */
    private List<ResCharacterLookVO> looks;

    /** 默认造型 */
    private ResCharacterOutfitVO defaultOutfit;

    /** 默认造型 (新标准) */
    private ResCharacterLookVO defaultLook;

    /** 造型数量统计 */
    private Integer outfitCount;

    /** 角色已知别名列表 */
    private List<ResCharacterAliasVO> aliases;

    /** 角色身份依据/证据列表 */
    private List<ResCharacterEvidenceVO> evidences;
}
