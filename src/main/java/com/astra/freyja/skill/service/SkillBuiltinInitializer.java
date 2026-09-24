package com.astra.freyja.skill.service;

import com.astra.freyja.dao.AiSkillMapper;
import com.astra.freyja.entity.AiSkill;
import com.astra.freyja.skill.model.StandardSkillPackage;
import com.astra.freyja.skill.validation.SkillPackageValidator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 内置 Skill 自动化初始化装载器。
 * <p>
 * 应用启动就绪后自动扫描预置 skills 目录。
 * 若数据库中缺少某内置技能或其尚未发布有效版本，则自动静默解析 ZIP 包并保存至数据库与 MinIO，
 * 实现 100% 零人工干预的开箱即用。
 * </p>
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class SkillBuiltinInitializer {

    private final AiSkillMapper skillMapper;
    private final SkillAdminService skillAdminService;
    private final SkillPackageValidator packageValidator;

    @Value("${freyja.skills.builtin-dir:}")
    private String customSkillsDir;

    @EventListener(ApplicationReadyEvent.class)
    public void initBuiltinSkills() {
        try {
            Path skillsPath = resolveSkillsDir();
            if (skillsPath == null || !Files.isDirectory(skillsPath)) {
                log.info("[SkillBuiltinInitializer] 未检测到内置 skills 目录，跳过自动装载");
                return;
            }

            File[] zipFiles = skillsPath.toFile().listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
            if (zipFiles == null || zipFiles.length == 0) {
                log.info("[SkillBuiltinInitializer] skills 目录中未找到 ZIP 技能包: {}", skillsPath);
                return;
            }

            log.info("[SkillBuiltinInitializer] 扫描到内置 skills 目录: {}, 包含 {} 个技能包", skillsPath, zipFiles.length);
            for (File zipFile : zipFiles) {
                try {
                    initSingleSkill(zipFile);
                } catch (Exception e) {
                    log.error("[SkillBuiltinInitializer] 自动导入预置技能包 [{}] 失败: {}", zipFile.getName(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("[SkillBuiltinInitializer] 内置技能自动装载流程异常: {}", e.getMessage(), e);
        }
    }

    private void initSingleSkill(File zipFile) throws Exception {
        byte[] bytes = Files.readAllBytes(zipFile.toPath());
        StandardSkillPackage skillPackage;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            skillPackage = packageValidator.validateZip(bais, bytes.length);
        }

        String standardName = skillPackage.getMetadata().getName();
        AiSkill existingSkill = skillMapper.selectOne(new LambdaQueryWrapper<AiSkill>()
                .eq(AiSkill::getName, standardName)
                .eq(AiSkill::getDeleted, 0));

        if (existingSkill != null && existingSkill.getCurrentVersionId() != null) {
            log.debug("[SkillBuiltinInitializer] Skill [{}] 已存在有效发布版本 (versionId={})，跳过自动导入",
                    standardName, existingSkill.getCurrentVersionId());
            return;
        }

        log.info("[SkillBuiltinInitializer] 检测到未初始化的内置技能 [{}], 开始自动装载并发布...", standardName);
        skillAdminService.saveSkillPackage(null, null, 10, skillPackage);
        log.info("[SkillBuiltinInitializer] 预置技能 [{}] 自动装载成功并已上线", standardName);
    }

    private Path resolveSkillsDir() {
        if (customSkillsDir != null && !customSkillsDir.isBlank()) {
            Path p = Paths.get(customSkillsDir.trim());
            if (Files.isDirectory(p)) return p;
        }

        // 候选路径 1: 当前工作目录下的 skills 目录 (如 /app/skills 或本地运行 ./skills)
        Path p1 = Paths.get("skills");
        if (Files.isDirectory(p1)) return p1;

        // 候选路径 2: 容器内绝对路径 /app/skills
        Path p2 = Paths.get("/app/skills");
        if (Files.isDirectory(p2)) return p2;

        // 候选路径 3: 宿主机上一级目录 ../skills
        Path p3 = Paths.get("../skills");
        if (Files.isDirectory(p3)) return p3;

        return null;
    }
}
