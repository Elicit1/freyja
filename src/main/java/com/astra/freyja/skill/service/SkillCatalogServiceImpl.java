package com.astra.freyja.skill.service;

import com.astra.freyja.dao.AiSkillMapper;
import com.astra.freyja.dao.AiSkillVersionMapper;
import com.astra.freyja.entity.AiSkill;
import com.astra.freyja.entity.AiSkillVersion;
import com.astra.freyja.skill.model.SkillCatalogItem;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillCatalogServiceImpl implements SkillCatalogService {

    private final AiSkillMapper skillMapper;
    private final AiSkillVersionMapper versionMapper;

    private volatile List<SkillCatalogItem> enabledCatalogCache;

    @Override
    public List<SkillCatalogItem> getEnabledCatalog() {
        List<SkillCatalogItem> cached = enabledCatalogCache;
        if (cached != null) {
            log.info("[SkillCache] enabled catalog HIT: count={}", cached.size());
            return cached;
        }

        synchronized (this) {
            cached = enabledCatalogCache;
            if (cached != null) {
                log.info("[SkillCache] enabled catalog HIT-after-lock: count={}", cached.size());
                return cached;
            }

            List<AiSkill> enabledSkills = skillMapper.selectList(new LambdaQueryWrapper<AiSkill>()
                    .eq(AiSkill::getEnabled, 1)
                    .isNotNull(AiSkill::getCurrentVersionId)
                    .orderByAsc(AiSkill::getSortOrder)
                    .orderByAsc(AiSkill::getId));

            List<SkillCatalogItem> result = new ArrayList<>();
            for (AiSkill skill : enabledSkills) {
                AiSkillVersion version = versionMapper.selectById(skill.getCurrentVersionId());
                if (version != null && version.getRevisionNo() != null) {
                    String revision = String.valueOf(version.getRevisionNo());
                    result.add(SkillCatalogItem.builder()
                            .name(skill.getName())
                            .displayName(skill.getDisplayName())
                            .description(skill.getDescription())
                            .version(revision)
                            .versionId(version.getId())
                            .build());
                }
            }
            enabledCatalogCache = List.copyOf(result);
            log.info("[SkillCache] enabled catalog MISS -> loaded: count={}, skills={}",
                    enabledCatalogCache.size(), enabledCatalogCache.stream().map(SkillCatalogItem::getName).toList());
            return enabledCatalogCache;
        }
    }

    @Override
    public synchronized void refreshCache() {
        enabledCatalogCache = null;
        log.info("[SkillCache] enabled catalog cache cleared");
    }

    @Override
    public String formatCatalogForPrompt() {
        return SkillCatalogService.super.formatCatalogForPrompt(getEnabledCatalog());
    }
}
