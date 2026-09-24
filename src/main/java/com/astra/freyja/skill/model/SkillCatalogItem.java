package com.astra.freyja.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * AI 可见精简技能目录条目。
 * 初始 Prompt 只注入此元数据，绝不注入完整正文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillCatalogItem implements Serializable {

    /** 技能唯一标识，如 cinematography */
    private String name;

    /** 技能展示名，如 电影摄影与分镜运镜 */
    private String displayName;

    /** 技能简短描述，供 AI 判断是否调用 load_skill */
    private String description;

    /** 当前递增版本号，如 1、2、3 */
    private String version;

    /** 本次目录快照锁定的版本 ID，正文不会随后的上传变化。 */
    private Long versionId;
}
