package com.astra.freyja.dto.script;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 注入给大模型的已有角色注册表项 (Character Registry Item)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharacterRegistryItemVO {

    @JsonPropertyDescription("角色永久唯一ID")
    private Long id;

    @JsonPropertyDescription("角色永久唯一ID (同 id)")
    private Long characterId;

    @JsonPropertyDescription("正式规范姓名 (如 林婉清)，若此前无名则为 null")
    private String canonicalName;

    @JsonPropertyDescription("展示称谓/初次称呼 (如 年轻女人)")
    private String displayName;

    @JsonPropertyDescription("角色性别: MALE, FEMALE, OTHER, UNKNOWN")
    private String gender;

    @JsonPropertyDescription("年龄段: TEENAGER, YOUTH, MIDDLE_AGED, ELDERLY")
    private String ageGroup;

    @JsonPropertyDescription("角色定位: PROTAGONIST, ANTAGONIST, SUPPORTING, EXTRA")
    private String roleType;

    @JsonPropertyDescription("当前身份状态: UNKNOWN, PARTIAL, CONFIRMED")
    private String identityStatus;

    @JsonPropertyDescription("已知的所有别名、代词与称呼列表，例如: ['女人', '她', '林小姐', '林婉清']")
    private List<String> aliases;

    @JsonPropertyDescription("中文外貌视觉特征描述")
    private String appearanceDesc;

    @JsonPropertyDescription("基础外貌视觉特征Prompt")
    private String appearancePrompt;

    @JsonPropertyDescription("触发词/激活词")
    private String triggerWords;

    @JsonPropertyDescription("性格与人设特点")
    private String personality;

    public Long getId() {
        return id != null ? id : characterId;
    }

    public Long getCharacterId() {
        return characterId != null ? characterId : id;
    }

    public void setId(Long id) {
        this.id = id;
        if (this.characterId == null) {
            this.characterId = id;
        }
    }

    public void setCharacterId(Long characterId) {
        this.characterId = characterId;
        if (this.id == null) {
            this.id = characterId;
        }
    }
}
