package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.res.AssetPromptPackageVO;
import com.astra.freyja.dto.res.OutfitPromptDeriveDTO;
import com.astra.freyja.dto.res.OutfitPromptDeriveVO;
import com.astra.freyja.dto.res.ResCharacterLookDTO;
import com.astra.freyja.dto.res.ResCharacterLookVO;
import com.astra.freyja.service.ResCharacterLookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 人物造型管理 Controller。
 */
@RestController
@RequestMapping("/res/character")
@RequiredArgsConstructor
public class ResCharacterLookController {

    private final ResCharacterLookService lookService;

    /**
     * 查询某角色的所有造型
     */
    @GetMapping("/{characterId}/looks")
    public R<List<ResCharacterLookVO>> listByCharacterId(@PathVariable Long characterId) {
        return R.ok(lookService.listByCharacterId(characterId));
    }

    /**
     * 获取人物的默认造型
     */
    @GetMapping("/{characterId}/default-look")
    public R<ResCharacterLookVO> getDefaultLook(@PathVariable Long characterId) {
        return R.ok(lookService.getDefaultLook(characterId));
    }

    /**
     * 获取单个造型详情
     */
    @GetMapping("/look/{lookId}")
    public R<ResCharacterLookVO> getById(@PathVariable Long lookId) {
        return R.ok(lookService.getById(lookId));
    }

    /**
     * 为某角色创建新造型
     */
    @PostMapping("/{characterId}/looks")
    public R<Long> createForCharacter(@PathVariable Long characterId, @RequestBody ResCharacterLookDTO dto) {
        dto.setCharacterId(characterId);
        return R.ok(lookService.create(dto));
    }

    /**
     * 通用新增造型
     */
    @PostMapping("/look")
    public R<Long> create(@RequestBody ResCharacterLookDTO dto) {
        return R.ok(lookService.create(dto));
    }

    /**
     * 修改造型
     */
    @PutMapping("/look/{lookId}")
    public R<Void> update(@PathVariable Long lookId, @RequestBody ResCharacterLookDTO dto) {
        dto.setId(lookId);
        lookService.update(dto);
        return R.ok();
    }

    /**
     * 删除造型
     */
    @DeleteMapping("/look/{lookId}")
    public R<Void> delete(@PathVariable Long lookId) {
        lookService.delete(lookId);
        return R.ok();
    }

    /**
     * 设为该人物的默认造型
     */
    @PutMapping("/look/{lookId}/set-default")
    public R<Void> setDefault(@PathVariable Long lookId) {
        lookService.setDefault(lookId);
        return R.ok();
    }

    /**
     * AI 智能衍生造型提示词
     */
    @PostMapping("/look/derive-prompt")
    public R<OutfitPromptDeriveVO> derivePrompt(@RequestBody OutfitPromptDeriveDTO dto) {
        return R.ok(lookService.deriveLookPrompt(dto));
    }

    /**
     * AI 智能衍生造型提示词 (流式 SSE)
     */
    @PostMapping(value = "/look/derive-prompt-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter derivePromptStream(@RequestBody OutfitPromptDeriveDTO dto) {
        return lookService.deriveLookPromptStream(dto);
    }

    /**
     * 构建造型提示词任务导出包
     */
    @PostMapping("/look/derive-prompt-package")
    public R<AssetPromptPackageVO> derivePromptPackage(@RequestBody OutfitPromptDeriveDTO dto) {
        return R.ok(lookService.buildLookPromptPackage(dto));
    }
}
