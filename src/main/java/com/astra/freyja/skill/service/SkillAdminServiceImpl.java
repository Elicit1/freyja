package com.astra.freyja.skill.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiSkillFileMapper;
import com.astra.freyja.dao.AiSkillMapper;
import com.astra.freyja.dao.AiSkillVersionMapper;
import com.astra.freyja.dto.skill.AiSkillFileVO;
import com.astra.freyja.dto.skill.AiSkillPreviewVO;
import com.astra.freyja.dto.skill.AiSkillVO;
import com.astra.freyja.dto.skill.AiSkillVersionVO;
import com.astra.freyja.entity.AiSkill;
import com.astra.freyja.entity.AiSkillFile;
import com.astra.freyja.entity.AiSkillVersion;
import com.astra.freyja.skill.model.SkillExportPackage;
import com.astra.freyja.skill.model.StandardSkillPackage;
import com.astra.freyja.skill.repository.SkillRepository;
import com.astra.freyja.skill.validation.SkillPackageValidator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Agent Skills 标准包管理服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillAdminServiceImpl implements SkillAdminService {

    private final AiSkillMapper skillMapper;
    private final AiSkillVersionMapper versionMapper;
    private final AiSkillFileMapper fileMapper;
    private final SkillPackageValidator packageValidator;
    private final SkillRepository skillRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SkillCacheService skillCacheService;

    @Override
    public List<AiSkillVO> listSkills() {
        List<AiSkill> skills = skillMapper.selectList(new LambdaQueryWrapper<AiSkill>()
                .orderByAsc(AiSkill::getSortOrder)
                .orderByAsc(AiSkill::getId));
        return skills.stream().map(this::toSkillVO).toList();
    }

    @Override
    public AiSkillVO getSkillById(Long skillId) {
        if (skillId == null) return null;
        AiSkill skill = skillMapper.selectById(skillId);
        if (skill == null) throw new BizException(404, "Skill 不存在: " + skillId);
        return toSkillVO(skill);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiSkillPreviewVO uploadPackage(Long skillId, String displayName,
                                           Integer sortOrder, MultipartFile file) {
        StandardSkillPackage skillPackage = packageValidator.validate(file);
        return saveSkillPackage(skillId, displayName, sortOrder, skillPackage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiSkillPreviewVO saveSkillPackage(Long skillId, String displayName,
                                             Integer sortOrder, StandardSkillPackage skillPackage) {
        String standardName = skillPackage.getMetadata().getName();
        String standardDescription = skillPackage.getMetadata().getDescription();

        AiSkill skill = skillId == null
                ? skillMapper.selectOne(new LambdaQueryWrapper<AiSkill>().eq(AiSkill::getName, standardName))
                : skillMapper.selectById(skillId);
        if (skillId != null && skill == null) {
            throw new BizException(404, "Skill 不存在: " + skillId);
        }
        if (skill != null && !standardName.equals(skill.getName())) {
            throw new BizException("SKILL.md 的 name 与目标 Skill 不一致，不能修改 Skill 标识");
        }
        if (skill != null) {
            // 通过锁定 Skill 行串行化同一 Skill 的版本号分配，避免并发上传撞上唯一索引。
            AiSkill lockedSkill = skillMapper.selectForUpdate(skill.getId());
            if (lockedSkill != null) {
                skill = lockedSkill;
            }
        }

        if (skill == null) {
            skill = new AiSkill();
            skill.setName(standardName);
            skill.setEnabled(1);
            skill.setSortOrder(sortOrder == null ? 10 : sortOrder);
            skill.setDisplayName(StringUtils.isBlank(displayName) ? standardName : displayName.trim());
            skill.setDescription(standardDescription);
            skillMapper.insert(skill);
        } else {
            if (StringUtils.isNotBlank(displayName)) {
                skill.setDisplayName(displayName.trim());
            }
            if (sortOrder != null) {
                skill.setSortOrder(sortOrder);
            }
            skill.setDescription(standardDescription);
            if (StringUtils.isBlank(skill.getDisplayName())) {
                skill.setDisplayName(standardName);
            }
            skillMapper.updateById(skill);
        }

        List<AiSkillVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<AiSkillVersion>()
                .eq(AiSkillVersion::getSkillId, skill.getId()));
        int nextRevision = versions.stream()
                .map(AiSkillVersion::getRevisionNo)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;

        AiSkillVersion version = new AiSkillVersion();
        version.setSkillId(skill.getId());
        version.setRevisionNo(nextRevision);
        version.setContent(skillPackage.getEntrypointContent());
        version.setContentHash(skillPackage.getPackageHash());
        version.setContentSize(skillPackage.getTotalSize());

        List<String> savedObjectKeys = new ArrayList<>();
        try {
            versionMapper.insert(version);
            for (Map.Entry<String, byte[]> entry : skillPackage.getFiles().entrySet()) {
                String relativePath = entry.getKey();
                String objectKey = buildObjectKey(standardName, nextRevision,
                        skillPackage.getPackageHash(), relativePath);
                skillRepository.saveFile(objectKey, entry.getValue(), contentType(relativePath));
                savedObjectKeys.add(objectKey);

                AiSkillFile skillFile = new AiSkillFile();
                skillFile.setSkillVersionId(version.getId());
                skillFile.setRelativePath(relativePath);
                skillFile.setFileType(fileType(relativePath));
                skillFile.setObjectKey(objectKey);
                skillFile.setContentHash(skillPackage.getFileHashes().get(relativePath));
                skillFile.setContentSize((long) entry.getValue().length);
                fileMapper.insert(skillFile);
            }

            skill.setCurrentVersionId(version.getId());
            skillMapper.updateById(skill);
            AiSkillPreviewVO preview = preview(skill, version);
            log.info("[SkillAdmin] 标准 Skill 包上传成功: name={}, revision={}, versionId={}, files={}",
                    skill.getName(), nextRevision, version.getId(), skillPackage.getFiles().size());
            refreshCacheIfAvailable();
            return preview;
        } catch (RuntimeException e) {
            // 数据库事务会回滚，但对象存储不参与事务，必须主动清理已经上传的对象。
            skillRepository.removeFiles(savedObjectKeys);
            throw e;
        }
    }

    @Override
    public List<AiSkillFileVO> listVersionFiles(Long versionId) {
        AiSkillVersion version = versionMapper.selectById(versionId);
        if (version == null) throw new BizException(404, "Skill 版本不存在: " + versionId);
        return fileMapper.selectList(new LambdaQueryWrapper<AiSkillFile>()
                        .eq(AiSkillFile::getSkillVersionId, versionId)
                        .orderByAsc(AiSkillFile::getRelativePath))
                .stream().map(this::toFileVO).toList();
    }

    @Override
    public List<AiSkillVersionVO> listVersions(Long skillId) {
        AiSkill skill = skillMapper.selectById(skillId);
        if (skill == null) throw new BizException(404, "Skill 不存在: " + skillId);
        return versionMapper.selectList(new LambdaQueryWrapper<AiSkillVersion>()
                        .eq(AiSkillVersion::getSkillId, skillId)
                        .orderByDesc(AiSkillVersion::getRevisionNo))
                .stream().map(v -> toVersionVO(v, skill.getName(), skill.getCurrentVersionId())).toList();
    }

    @Override
    public AiSkillPreviewVO previewVersion(Long versionId) {
        AiSkillVersion version = versionMapper.selectById(versionId);
        if (version == null) throw new BizException(404, "Skill 版本不存在: " + versionId);
        AiSkill skill = skillMapper.selectById(version.getSkillId());
        if (skill == null) throw new BizException(404, "Skill 不存在: " + version.getSkillId());
        return preview(skill, version);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiSkillVO switchVersion(Long skillId, Long versionId) {
        AiSkill skill = skillMapper.selectById(skillId);
        AiSkillVersion version = versionMapper.selectById(versionId);
        if (skill == null || version == null || !skillId.equals(version.getSkillId())) {
            throw new BizException(404, "目标 Skill 或版本不存在");
        }
        skill.setCurrentVersionId(versionId);
        skillMapper.updateById(skill);
        refreshCacheIfAvailable();
        return toSkillVO(skill);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setEnabled(Long skillId, Integer enabled) {
        AiSkill skill = skillMapper.selectById(skillId);
        if (skill == null) throw new BizException(404, "Skill 不存在: " + skillId);
        skill.setEnabled(enabled != null && enabled != 0 ? 1 : 0);
        skillMapper.updateById(skill);
        refreshCacheIfAvailable();
    }

    @Override
    public void refreshCache() {
        if (skillCacheService == null) {
            log.warn("[SkillAdmin] SkillCacheService 未注入，无法刷新缓存");
            return;
        }
        skillCacheService.refreshAll();
    }

    /** 物理删除逻辑 Skill、全部历史版本及其 MinIO 附属文件。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSkill(Long skillId) {
        AiSkill skill = skillMapper.selectById(skillId);
        if (skill == null) throw new BizException(404, "Skill 不存在: " + skillId);

        List<AiSkillVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<AiSkillVersion>()
                .eq(AiSkillVersion::getSkillId, skillId));
        List<String> objectKeys = new ArrayList<>();
        for (AiSkillVersion version : versions) {
            fileMapper.selectList(new LambdaQueryWrapper<AiSkillFile>()
                            .eq(AiSkillFile::getSkillVersionId, version.getId()))
                    .stream()
                    .map(AiSkillFile::getObjectKey)
                    .filter(StringUtils::isNotBlank)
                    .forEach(objectKeys::add);
            fileMapper.hardDeleteByVersionId(version.getId());
        }
        skillRepository.removeFiles(objectKeys);
        versionMapper.hardDeleteBySkillId(skillId);
        skillMapper.hardDeleteById(skillId);
        refreshCacheIfAvailable();
        log.info("[SkillAdmin] 删除 Skill: id={}, name={}, files={}", skillId, skill.getName(), objectKeys.size());
    }

    @Override
    public SkillExportPackage exportSkillPackage(Long skillId) {
        AiSkill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new BizException(404, "Skill 不存在: " + skillId);
        }
        if (skill.getCurrentVersionId() == null) {
            throw new BizException("Skill [" + skill.getName() + "] 暂无已发布版本，无法下载");
        }
        return exportVersionPackage(skill.getCurrentVersionId());
    }

    @Override
    public SkillExportPackage exportVersionPackage(Long versionId) {
        AiSkillVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BizException(404, "Skill 版本不存在: " + versionId);
        }
        AiSkill skill = skillMapper.selectById(version.getSkillId());
        if (skill == null) {
            throw new BizException(404, "Skill 不存在: " + version.getSkillId());
        }

        List<AiSkillFile> files = fileMapper.selectList(new LambdaQueryWrapper<AiSkillFile>()
                .eq(AiSkillFile::getSkillVersionId, version.getId())
                .orderByAsc(AiSkillFile::getRelativePath));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            boolean skillMdWritten = false;
            for (AiSkillFile file : files) {
                String relativePath = file.getRelativePath();
                if (StringUtils.isBlank(relativePath)) {
                    continue;
                }

                byte[] data = null;
                if ("skill.md".equalsIgnoreCase(relativePath.trim())) {
                    try {
                        data = skillRepository.loadFile(file.getObjectKey(), file.getContentHash(), file.getContentSize());
                    } catch (Exception e) {
                        log.warn("[SkillAdmin] MinIO 读取 SKILL.md 失败，使用数据库版本正文降级: {}", e.getMessage());
                        if (StringUtils.isNotBlank(version.getContent())) {
                            data = version.getContent().getBytes(StandardCharsets.UTF_8);
                        }
                    }
                    skillMdWritten = true;
                } else {
                    data = skillRepository.loadFile(file.getObjectKey(), file.getContentHash(), file.getContentSize());
                }

                if (data != null) {
                    ZipEntry entry = new ZipEntry(relativePath);
                    zos.putNextEntry(entry);
                    zos.write(data);
                    zos.closeEntry();
                }
            }

            // 防御性保底：若文件索引中缺少 SKILL.md，但版本存在 content，自动补全
            if (!skillMdWritten && StringUtils.isNotBlank(version.getContent())) {
                ZipEntry entry = new ZipEntry("SKILL.md");
                zos.putNextEntry(entry);
                zos.write(version.getContent().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            zos.finish();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[SkillAdmin] 打包导出 Skill ZIP 失败: skill={}, version={}", skill.getName(), version.getRevisionNo(), e);
            throw new BizException("导出 Skill ZIP 失败: " + e.getMessage());
        }

        String filename = String.format("%s-v%s.zip", skill.getName(), version.getRevisionNo());
        return new SkillExportPackage(filename, baos.toByteArray());
    }

    private void refreshCacheIfAvailable() {
        if (skillCacheService != null) {
            skillCacheService.refreshAll();
        }
    }

    private AiSkillPreviewVO preview(AiSkill skill, AiSkillVersion version) {
        return AiSkillPreviewVO.builder()
                .version(toVersionVO(version, skill.getName(), skill.getCurrentVersionId()))
                .content(version.getContent())
                .files(fileMapper.selectList(new LambdaQueryWrapper<AiSkillFile>()
                        .eq(AiSkillFile::getSkillVersionId, version.getId())
                        .orderByAsc(AiSkillFile::getRelativePath))
                        .stream().map(this::toFileVO).toList())
                .build();
    }

    private AiSkillVO toSkillVO(AiSkill skill) {
        AiSkillVersion current = skill.getCurrentVersionId() == null ? null
                : versionMapper.selectById(skill.getCurrentVersionId());
        return AiSkillVO.builder()
                .id(skill.getId())
                .name(skill.getName())
                .displayName(skill.getDisplayName())
                .description(skill.getDescription())
                .enabled(skill.getEnabled())
                .currentVersionId(skill.getCurrentVersionId())
                .currentVersion(current == null ? null : String.valueOf(current.getRevisionNo()))
                .sortOrder(skill.getSortOrder())
                .createTime(skill.getCreateTime())
                .updateTime(skill.getUpdateTime())
                .build();
    }

    private AiSkillVersionVO toVersionVO(AiSkillVersion version, String skillName, Long currentId) {
        return AiSkillVersionVO.builder()
                .id(version.getId())
                .skillId(version.getSkillId())
                .skillName(skillName)
                .version(String.valueOf(version.getRevisionNo()))
                .status(version.getId().equals(currentId) ? "CURRENT" : "HISTORY")
                .contentHash(version.getContentHash())
                .contentSize(version.getContentSize())
                .createTime(version.getCreateTime())
                .build();
    }

    private AiSkillFileVO toFileVO(AiSkillFile file) {
        return AiSkillFileVO.builder()
                .id(file.getId())
                .skillVersionId(file.getSkillVersionId())
                .relativePath(file.getRelativePath())
                .fileType(file.getFileType())
                .contentHash(file.getContentHash())
                .contentSize(file.getContentSize())
                .build();
    }

    private String buildObjectKey(String skillName, int revision, String packageHash, String relativePath) {
        String safeHash = StringUtils.defaultString(packageHash).replace(':', '_');
        return "skills/" + skillName + "/v" + revision + "/" + safeHash + "/" + relativePath;
    }

    private String fileType(String relativePath) {
        String path = relativePath.toLowerCase(Locale.ROOT);
        if ("skill.md".equals(path)) return "ENTRYPOINT";
        if (path.startsWith("scripts/")) return "SCRIPT";
        if (path.startsWith("references/")) return "REFERENCE";
        if (path.startsWith("assets/")) return "ASSET";
        return "RESOURCE";
    }

    private String contentType(String relativePath) {
        String path = relativePath.toLowerCase(Locale.ROOT);
        if (path.endsWith(".md")) return "text/markdown; charset=utf-8";
        if (path.endsWith(".txt") || path.endsWith(".csv")) return "text/plain; charset=utf-8";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".yaml") || path.endsWith(".yml")) return "application/yaml";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
}
