package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.skill.AiSkillFileVO;
import com.astra.freyja.dto.skill.AiSkillPreviewVO;
import com.astra.freyja.dto.skill.AiSkillSwitchVersionDTO;
import com.astra.freyja.dto.skill.AiSkillVO;
import com.astra.freyja.dto.skill.AiSkillVersionVO;
import com.astra.freyja.skill.model.SkillExportPackage;
import com.astra.freyja.skill.service.SkillAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * AI Skills 技能包管理 Controller。
 */
@RestController
@RequestMapping("/system/skills")
@RequiredArgsConstructor
public class AiSkillController {

    private final SkillAdminService skillAdminService;

    /**
     * 查询所有 Skill 列表
     */
    @GetMapping
    public R<List<AiSkillVO>> listSkills() {
        return R.ok(skillAdminService.listSkills());
    }

    /**
     * 查询指定 Skill 详情
     */
    @GetMapping("/{id}")
    public R<AiSkillVO> getSkillById(@PathVariable("id") Long id) {
        return R.ok(skillAdminService.getSkillById(id));
    }

    /** 上传标准 Agent Skill ZIP；同名或指定 skillId 时自动创建新版本并立即生效。 */
    @PostMapping("/upload")
    public R<AiSkillPreviewVO> uploadPackage(
            @RequestParam(value = "skillId", required = false) Long skillId,
            @RequestParam(value = "displayName", required = false) String displayName,
            @RequestParam(value = "sortOrder", required = false) Integer sortOrder,
            @RequestParam("file") MultipartFile file) {
        return R.ok(skillAdminService.uploadPackage(skillId, displayName, sortOrder, file));
    }

    /** 删除整个 Skill 及其历史版本。 */
    @DeleteMapping("/{id}")
    public R<Void> deleteSkill(@PathVariable("id") Long id) {
        skillAdminService.deleteSkill(id);
        return R.ok();
    }

    /**
     * 查询指定 Skill 的所有历史版本
     */
    @GetMapping("/{id}/versions")
    public R<List<AiSkillVersionVO>> listVersions(@PathVariable("id") Long id) {
        return R.ok(skillAdminService.listVersions(id));
    }

    /** 预览指定版本的 SKILL.md 正文及标准包文件索引。 */
    @GetMapping("/versions/{versionId}/preview")
    public R<AiSkillPreviewVO> previewVersion(@PathVariable("versionId") Long versionId) {
        return R.ok(skillAdminService.previewVersion(versionId));
    }

    /** 查询指定版本的标准包文件索引。 */
    @GetMapping("/versions/{versionId}/files")
    public R<List<AiSkillFileVO>> listVersionFiles(@PathVariable("versionId") Long versionId) {
        return R.ok(skillAdminService.listVersionFiles(versionId));
    }

    /**
     * 切换 Skill 当前生效版本 (回滚或切换)
     */
    @PostMapping("/{id}/switch-version")
    public R<AiSkillVO> switchVersion(@PathVariable("id") Long id, @RequestBody AiSkillSwitchVersionDTO dto) {
        return R.ok(skillAdminService.switchVersion(id, dto.getVersionId()));
    }

    /**
     * 启停 Skill
     */
    @PutMapping("/{id}/enabled")
    public R<Void> setEnabled(@PathVariable("id") Long id, @RequestParam("enabled") Integer enabled) {
        skillAdminService.setEnabled(id, enabled);
        return R.ok();
    }

    /** 手动刷新本地 Skill 目录与正文缓存。 */
    @PostMapping("/cache/refresh")
    public R<Void> refreshCache() {
        skillAdminService.refreshCache();
        return R.ok();
    }

    /**
     * 下载指定 Skill 当前生效版本的标准 ZIP 包。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> downloadCurrentPackage(@PathVariable("id") Long id) {
        SkillExportPackage pkg = skillAdminService.exportSkillPackage(id);
        return buildZipDownloadResponse(pkg);
    }

    /**
     * 下载指定历史版本的标准 ZIP 包。
     */
    @GetMapping("/versions/{versionId}/download")
    public ResponseEntity<byte[]> downloadVersionPackage(@PathVariable("versionId") Long versionId) {
        SkillExportPackage pkg = skillAdminService.exportVersionPackage(versionId);
        return buildZipDownloadResponse(pkg);
    }

    private ResponseEntity<byte[]> buildZipDownloadResponse(SkillExportPackage pkg) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(pkg.filename(), StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(pkg.zipData().length)
                .body(pkg.zipData());
    }

}
