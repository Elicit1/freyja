package com.astra.freyja.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 人物角色资产表 res_character (人物身份层 SSOT)。
 * 仅保存跨造型稳定的人物设定，不再区分头像、FaceID、三视图三套并行模型。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("res_character")
public class ResCharacter extends BaseEntity {

    /** 归属短剧ID，0为公共资源库 */
    private Long dramaId;

    /** 人物名称/角色名 (初次提及或正式名) */
    private String name;

    /** 正式规范姓名 (如 林婉清) */
    private String canonicalName;

    /** 展示称谓/初次提及称谓 (如 年轻女人) */
    private String displayName;

    /** 唯一人物身份设定参考图URL (已废弃并迁移至默认造型，保留字段作为内存透传兼容) */
    @TableField(exist = false)
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

    /** 基础稳定身份外貌特征Prompt (英文通用，不包含具体服装) */
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
}
