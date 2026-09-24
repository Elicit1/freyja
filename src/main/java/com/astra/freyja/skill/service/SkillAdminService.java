package com.astra.freyja.skill.service;

import com.astra.freyja.dto.skill.AiSkillPreviewVO;
import com.astra.freyja.dto.skill.AiSkillFileVO;
import com.astra.freyja.dto.skill.AiSkillVO;
import com.astra.freyja.dto.skill.AiSkillVersionVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Skill 后台管理服务接口。
 * 负责标准 Agent Skill 包导入、版本维护、回滚切换与启停。
 */
public interface SkillAdminService {

    List<AiSkillVO> listSkills();

    AiSkillVO getSkillById(Long skillId);

    /**
     * 导入标准 Agent Skill 目录的 ZIP 包。ZIP 根目录必须包含标准 SKILL.md。
     */
    AiSkillPreviewVO uploadPackage(Long skillId, String displayName,
                                   Integer sortOrder, MultipartFile file);

    /**
     * 直接保存并发布已解析的标准 SkillPackage。
     */
    AiSkillPreviewVO saveSkillPackage(Long skillId, String displayName,
                                      Integer sortOrder, com.astra.freyja.skill.model.StandardSkillPackage skillPackage);

    List<AiSkillFileVO> listVersionFiles(Long versionId);

    List<AiSkillVersionVO> listVersions(Long skillId);

    AiSkillPreviewVO previewVersion(Long versionId);

    AiSkillVO switchVersion(Long skillId, Long versionId);

    void setEnabled(Long skillId, Integer enabled);

    /** 手动清除并重新建立本地 Skill 缓存。 */
    void refreshCache();

    /** 删除整个 Skill 及其历史版本。 */
    void deleteSkill(Long skillId);

    /**
     * 导出 Skill 当前生效版本的标准 ZIP 包。
     */
    com.astra.freyja.skill.model.SkillExportPackage exportSkillPackage(Long skillId);

    /**
     * 导出 Skill 指定历史版本的标准 ZIP 包。
     */
    com.astra.freyja.skill.model.SkillExportPackage exportVersionPackage(Long versionId);
}
