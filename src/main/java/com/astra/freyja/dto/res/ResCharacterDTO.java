package com.astra.freyja.dto.res;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 人物角色新增/更新请求 DTO。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResCharacterDTO {

    /** 主键，修改时必传 */
    private Long id;

    /** 归属短剧ID，0为公共资源库 */
    private Long dramaId;

    /** 人物名称/角色名 */
    private String name;

    /** 正式规范姓名 (如 林婉清) */
    private String canonicalName;

    /** 展示称谓/初次提及称谓 (如 年轻女人) */
    private String displayName;

    /** 唯一人物身份设定参考图URL (MinIO) */
    private String referenceImageUrl;

    /** 性别 MALE/FEMALE/OTHER/UNKNOWN */
    private String gender;

    /** 年龄段 (如 TEENAGER/YOUTH/MIDDLE_AGED/ELDERLY) */
    private String ageGroup;

    /** 角色定位 PROTAGONIST(主角)/ANTAGONIST(反派)/SUPPORTING(配角)/EXTRA(路人) */
    private String roleType;

    /** 身份状态 UNKNOWN/PARTIAL/CONFIRMED/UNRESOLVED/MERGED */
    private String identityStatus;

    /** 若已合并，指向目标主角色ID */
    private Long mergedToId;

    /** 性格/人设简述 (内在心智、处事原则与对白基调) */
    private String personality;

    /** 中文外貌视觉特征描述 (原著外貌真实源 SSOT) */
    private String appearanceDesc;

    /** 基础稳定身份外貌特征Prompt (英文) */
    private String appearancePrompt;

    /** 角色专属全局负向Prompt */
    private String negativePrompt;

    /** 触发词/激活词 (如 sarah_connor, realistic) */
    private String triggerWords;

    /** 绑定的角色LoRA文件名 */
    private String loraName;

    /** LoRA权重 */
    private BigDecimal loraWeight;

    /** TTS配音音色ID */
    private String voiceId;

    /** 音色设计提示词 (自然语言描述) */
    private String voiceDesc;

    /** 试音参考台词文本 */
    private String voiceSampleText;

    /** 角色基准参考样音 (MinIO URL) */
    private String voiceSampleUrl;

    /** 显示排序 */
    private Integer sortOrder;

    /** 状态 0-停用 1-启用 */
    private Integer status;

    /** 初次出场集数/场景 */
    private String firstAppearance;

    /** 最近出场集数/场景 */
    private String lastAppearance;

    /** 备注 */
    private String remark;

    /** 可选：创建角色时附带的默认造型名称 */
    private String defaultOutfitName;

    /** 可选：创建角色时附带的默认服装Prompt */
    private String defaultOutfitPrompt;

    /** 附带别名列表 (自动兼容字符串列表与对象列表) */
    private List<String> aliases;

    public void setAliases(List<?> list) {
        if (list == null) {
            this.aliases = null;
            return;
        }
        this.aliases = list.stream()
                .map(item -> {
                    if (item == null) return null;
                    if (item instanceof String s) return s;
                    if (item instanceof java.util.Map<?, ?> map) {
                        Object val = map.get("alias");
                        if (val == null) val = map.get("name");
                        return val != null ? val.toString() : null;
                    }
                    return item.toString();
                })
                .filter(s -> s != null && !s.isBlank())
                .toList();
    }
}
