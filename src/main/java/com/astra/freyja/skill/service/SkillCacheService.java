package com.astra.freyja.skill.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 统一清理本地 Skill 目录与正文缓存。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillCacheService {

    private final SkillCatalogService skillCatalogService;
    private final SkillContentService skillContentService;

    public void refreshAll() {
        skillContentService.refreshCache();
        skillCatalogService.refreshCache();
        log.info("[SkillCache] 已手动刷新 Skill 目录与 SKILL.md 正文缓存");
    }
}
