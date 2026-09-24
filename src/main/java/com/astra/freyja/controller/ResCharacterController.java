package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.res.*;
import com.astra.freyja.service.CharacterRegistryService;
import com.astra.freyja.service.ResCharacterService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 人物角色资产管理与消歧/合并 Controller。
 */
@RestController
@RequestMapping("/res/character")
@RequiredArgsConstructor
public class ResCharacterController {

    private final ResCharacterService characterService;
    private final CharacterRegistryService characterRegistryService;

    @GetMapping("/page")
    public R<Page<ResCharacterVO>> page(ResCharacterQuery query) {
        return R.ok(characterService.page(query));
    }

    @GetMapping("/{id}")
    public R<ResCharacterVO> getById(@PathVariable Long id) {
        return R.ok(characterService.getById(id));
    }

    @PostMapping
    public R<Long> create(@RequestBody ResCharacterDTO dto) {
        return R.ok(characterService.create(dto));
    }

    @PutMapping
    public R<Void> update(@RequestBody ResCharacterDTO dto) {
        characterService.update(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        characterService.delete(id);
        return R.ok();
    }

    @GetMapping("/options")
    public R<List<ResCharacterOptionVO>> options(@RequestParam(value = "dramaId", required = false) Long dramaId) {
        return R.ok(characterService.options(dramaId));
    }

    // ==========================================================
    // 角色合并与消歧相关接口 (Character Entity Resolution & Merge)
    // ==========================================================

    /**
     * 角色合并 (Merge Characters)：将源角色全量迁移并合并至目标主角色
     */
    @PostMapping("/merge")
    public R<Void> merge(@RequestBody CharacterMergeDTO dto) {
        characterService.merge(dto);
        return R.ok();
    }

    /**
     * 获取角色的所有别名与提及词
     */
    @GetMapping("/{id}/aliases")
    public R<List<ResCharacterAliasVO>> listAliases(@PathVariable("id") Long characterId) {
        return R.ok(characterRegistryService.listAliasesByCharacterId(characterId));
    }

    /**
     * 为角色新增别名/称谓
     */
    @PostMapping("/aliases")
    public R<Long> addAlias(@RequestBody ResCharacterAliasDTO dto) {
        return R.ok(characterRegistryService.addAlias(dto));
    }

    /**
     * 修改角色别名/称谓
     */
    @PutMapping("/aliases")
    public R<Void> updateAlias(@RequestBody ResCharacterAliasDTO dto) {
        characterRegistryService.updateAlias(dto);
        return R.ok();
    }

    /**
     * 删除角色别名
     */
    @DeleteMapping("/aliases/{aliasId}")
    public R<Void> removeAlias(@PathVariable("aliasId") Long aliasId) {
        characterRegistryService.removeAlias(aliasId);
        return R.ok();
    }

    /**
     * 获取角色的身份依据/证据链
     */
    @GetMapping("/{id}/evidences")
    public R<List<ResCharacterEvidenceVO>> listEvidences(@PathVariable("id") Long characterId) {
        return R.ok(characterRegistryService.listEvidencesByCharacterId(characterId));
    }

    /**
     * 查询待人工审核的未决消歧列表
     */
    @GetMapping("/resolutions/pending")
    public R<List<ResCharacterResolutionVO>> listPendingResolutions(@RequestParam(value = "dramaId", required = false) Long dramaId) {
        return R.ok(characterRegistryService.listPendingResolutions(dramaId));
    }

    /**
     * 人工决议处理未决消歧项
     */
    @PostMapping("/resolutions/resolve")
    public R<Void> resolveResolution(@RequestBody ResCharacterResolutionDTO dto) {
        characterRegistryService.resolveResolution(dto);
        return R.ok();
    }

    /**
     * AI 智能衍生角色身份层纯净视觉提示词 (仅稳定外貌与生物特征，不含服装与镜头)
     */
    @PostMapping("/derive-visual-prompt")
    public R<com.astra.freyja.dto.res.CharacterVisualPromptDeriveVO> deriveVisualPrompt(
            @RequestBody com.astra.freyja.dto.res.CharacterVisualPromptDeriveDTO dto) {
        return R.ok(characterService.deriveVisualPrompt(dto));
    }

    /**
     * AI 智能衍生角色身份层纯净视觉提示词 (流式 SSE)
     */
    @PostMapping(value = "/derive-visual-prompt-stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter deriveVisualPromptStream(
            @RequestBody com.astra.freyja.dto.res.CharacterVisualPromptDeriveDTO dto) {
        return characterService.deriveVisualPromptStream(dto);
    }

    /**
     * 构建角色身份层提示词任务导出包 (外部 AI 复制使用)
     */
    @PostMapping("/derive-visual-prompt-package")
    public R<com.astra.freyja.dto.res.AssetPromptPackageVO> deriveVisualPromptPackage(
            @RequestBody com.astra.freyja.dto.res.CharacterVisualPromptDeriveDTO dto) {
        return R.ok(characterService.buildVisualPromptPackage(dto));
    }

}

