package com.astra.freyja.skill.service;

import com.astra.freyja.skill.model.LoadedSkill;

/**
 * 标准 Skill 入口正文按需加载服务，数据库是入口正文的事实来源。
 */
public interface SkillContentService {

    LoadedSkill loadSkill(String skillName);

    /** 按目录快照指定的不可变版本加载正文。 */
    LoadedSkill loadSkillVersion(Long versionId);

    /** 清除 Skill 正文缓存。 */
    void refreshCache();
}
