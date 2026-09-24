package com.astra.freyja.director.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 导演决策计划中记录的实际生效 Skill 引用与不可变哈希快照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppliedSkillRef implements Serializable {

    /** 技能唯一标识，如 cinematography */
    private String name;

    /** 实际加载生效的版本号，如 1.0.0 */
    private String version;

    /** 版本 SHA-256 哈希值 */
    private String contentHash;
}
