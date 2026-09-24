package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.res.ResCharacterOutfitDTO;
import com.astra.freyja.dto.res.ResCharacterOutfitVO;
import com.astra.freyja.service.ResCharacterOutfitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 人物造型/服装管理 Controller。
 */
@RestController
@RequestMapping("/res/character/outfit")
@RequiredArgsConstructor
public class ResCharacterOutfitController {

    private final ResCharacterOutfitService outfitService;

    @GetMapping("/list/{characterId}")
    public R<List<ResCharacterOutfitVO>> listByCharacterId(@PathVariable Long characterId) {
        return R.ok(outfitService.listByCharacterId(characterId));
    }

    @GetMapping("/{id}")
    public R<ResCharacterOutfitVO> getById(@PathVariable Long id) {
        return R.ok(outfitService.getById(id));
    }

    @PostMapping
    public R<Long> create(@RequestBody ResCharacterOutfitDTO dto) {
        return R.ok(outfitService.create(dto));
    }

    @PutMapping
    public R<Void> update(@RequestBody ResCharacterOutfitDTO dto) {
        outfitService.update(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        outfitService.delete(id);
        return R.ok();
    }

    @PutMapping("/{id}/set-default")
    public R<Void> setDefault(@PathVariable Long id) {
        outfitService.setDefault(id);
        return R.ok();
    }

    /**
     * AI 智能衍生角色造型专属提示词
     */
    @PostMapping("/derive-prompt")
    public R<com.astra.freyja.dto.res.OutfitPromptDeriveVO> derivePrompt(@RequestBody com.astra.freyja.dto.res.OutfitPromptDeriveDTO dto) {
        return R.ok(outfitService.deriveOutfitPrompt(dto));
    }

    /**
     * AI 智能衍生角色造型专属提示词 (流式 SSE)
     */
    @PostMapping(value = "/derive-prompt-stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter derivePromptStream(@RequestBody com.astra.freyja.dto.res.OutfitPromptDeriveDTO dto) {
        return outfitService.deriveOutfitPromptStream(dto);
    }

    /**
     * 构建角色造型提示词任务导出包 (外部 AI 复制使用)
     */
    @PostMapping("/derive-prompt-package")
    public R<com.astra.freyja.dto.res.AssetPromptPackageVO> derivePromptPackage(@RequestBody com.astra.freyja.dto.res.OutfitPromptDeriveDTO dto) {
        return R.ok(outfitService.buildOutfitPromptPackage(dto));
    }
}
