package com.astra.freyja.skill.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiSkillMapper;
import com.astra.freyja.dao.AiSkillVersionMapper;
import com.astra.freyja.entity.AiSkill;
import com.astra.freyja.entity.AiSkillVersion;
import com.astra.freyja.skill.model.LoadedSkill;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 从数据库按需加载标准 Skill 的 SKILL.md 正文。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillContentServiceImpl implements SkillContentService {

    private final AiSkillMapper skillMapper;
    private final AiSkillVersionMapper versionMapper;
    private final ConcurrentMap<Long, LoadedSkill> versionCache = new ConcurrentHashMap<>();

    @Override
    public LoadedSkill loadSkill(String skillName) {
        if (StringUtils.isBlank(skillName)) {
            throw new BizException("加载的 Skill 名称不能为空");
        }
        AiSkill skill = findEnabledSkill(skillName);
        if (skill.getCurrentVersionId() == null) {
            throw new BizException("AI 技能 [" + skill.getName() + "] 尚未上传有效版本");
        }
        return loadSkillVersion(skill.getCurrentVersionId());
    }

    @Override
    public LoadedSkill loadSkillVersion(Long versionId) {
        if (versionId == null) {
            throw new BizException("Skill 版本 ID 不能为空");
        }
        LoadedSkill cached = versionCache.get(versionId);
        if (cached != null) {
            log.info("[SkillCache] content HIT: versionId={}, skill={}, version={}, loadedMarkdownFile={}/SKILL.md",
                    versionId, cached.getName(), cached.getVersion(), cached.getName());
            return cached;
        }

        AiSkillVersion version = versionMapper.selectById(versionId);
        if (version == null || StringUtils.isBlank(version.getContent())) {
            throw new BizException("Skill 版本不存在或正文为空: " + versionId);
        }
        AiSkill skill = skillMapper.selectById(version.getSkillId());
        if (skill == null) {
            throw new BizException("AI 技能不存在");
        }
        LoadedSkill loaded = LoadedSkill.builder()
                .name(skill.getName())
                .version(String.valueOf(version.getRevisionNo()))
                .contentHash(version.getContentHash())
                .content(version.getContent())
                .build();
        LoadedSkill existing = versionCache.putIfAbsent(versionId, loaded);
        log.info("[SkillCache] content MISS -> loaded: versionId={}, skill={}, version={}, loadedMarkdownFile={}/SKILL.md",
                versionId, loaded.getName(), loaded.getVersion(), loaded.getName());
        return existing != null ? existing : loaded;
    }

    @Override
    public void refreshCache() {
        int size = versionCache.size();
        versionCache.clear();
        log.info("[SkillCache] content cache cleared: versionEntries={}", size);
    }

    private AiSkill findEnabledSkill(String skillName) {
        String cleanName = skillName.trim().toLowerCase();
        AiSkill skill = skillMapper.selectOne(new LambdaQueryWrapper<AiSkill>()
                .eq(AiSkill::getName, cleanName));
        if (skill == null) {
            throw new BizException("未找到名为 [" + cleanName + "] 的 AI 技能");
        }
        if (!Integer.valueOf(1).equals(skill.getEnabled())) {
            throw new BizException("AI 技能 [" + cleanName + "] 已被停用，无法加载");
        }
        return skill;
    }
}
