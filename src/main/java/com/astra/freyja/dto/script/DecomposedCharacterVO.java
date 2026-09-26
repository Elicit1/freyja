package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 剧本拆解提取的角色信息 VO（含跨集实体消歧字段）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecomposedCharacterVO {

    @JsonPropertyDescription("当前剧本中提取的人物名称/称谓，例如: 林婉清 或 女人")
    private String name;

    @JsonPropertyDescription("角色的正式规范姓名（如 林婉清）。若剧本中该角色尚未出现正式姓名，则为 null，严禁 AI 臆造姓名！")
    private String canonicalName;

    @JsonPropertyDescription("展示称谓/初次提及称谓，例如: 年轻女人")
    private String displayName;

    @JsonPropertyDescription("角色身份状态: PARTIAL(临时称谓未定名), CONFIRMED(已明确正式名), UNRESOLVED(歧义待确认)")
    private String identityStatus;

    @JsonPropertyDescription("角色定位分类: PROTAGONIST(主角), ANTAGONIST(反派), SUPPORTING(配角), EXTRA(路人)")
    private String roleType;

    @JsonPropertyDescription("角色性别: MALE(男), FEMALE(女), OTHER(其他), UNKNOWN(未知)")
    private String gender;

    @JsonPropertyDescription("年龄段: TEENAGER(少年), YOUTH(青年), MIDDLE_AGED(中年), ELDERLY(老年)")
    private String ageGroup;

    @JsonPropertyDescription("性格与人设特点描述 (内在心智、处事原则与对白口吻)")
    private String personality;

    @JsonPropertyDescription("角色稳定外貌特征中文描述，仅包含五官骨相、发型发色、身材体态，不包含衣着造型或即时空间动作")
    private String appearanceDesc;

    @JsonPropertyDescription("已废弃/免生成：剧本拆解阶段强制为 null 无需输出英文，以最大化节省 Token。英文生图提示词统一在资产库定妆阶段生成")
    private String appearancePrompt;

    @JsonPropertyDescription("剧本拆解阶段不生成衣着设定，必须为 null；服饰造型由资产库单独维护")
    private String outfitPrompt;

    @JsonPropertyDescription("角色专属触发词/LoRA识别词，例如: lin_wanqing")
    private String triggerWords;

    @JsonPropertyDescription("识别或提取到的该角色全部别名与提及词列表，例如: ['女人', '她', '林小姐', '林婉清']")
    private List<String> aliases;

    @JsonPropertyDescription("若匹配到已有 Character Registry 中的角色，回填对应已有角色 ID，否则为 null")
    private Long matchedCharacterId;

    @JsonPropertyDescription("消歧匹配置信度 (0.0 ~ 1.0)，例如: 0.95")
    private Double confidence;

    @JsonPropertyDescription("身份判定证据类型: FIRST_APPEARANCE, SELF_INTRODUCTION, EXPLICIT_NAME, EXPLICIT_REFERENCE, RELATIONSHIP, APPEARANCE_MATCH, CONTEXT_MATCH, SCENE_CONTINUITY, OTHER")
    private String evidenceType;

    @JsonPropertyDescription("身份判断对应的剧本原文依据，例如: “我叫林婉清。”")
    private String evidenceText;

    @JsonPropertyDescription("若存在歧义无法确定 (UNRESOLVED)，列出所有可能的已有候选角色 ID 列表，例如: [1001, 1003]")
    private List<Long> candidateCharacterIds;

    @JsonPropertyDescription("兼容字段：若匹配到系统已有角色资产，回填对应已有角色ID")
    private Long existingCharacterId;
}
