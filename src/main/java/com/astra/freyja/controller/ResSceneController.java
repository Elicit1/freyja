package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.res.ResSceneDTO;
import com.astra.freyja.dto.res.ResSceneOptionVO;
import com.astra.freyja.dto.res.ResSceneQuery;
import com.astra.freyja.dto.res.ResSceneVO;
import com.astra.freyja.service.ResSceneService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 场景环境资产管理 Controller。
 */
@RestController
@RequestMapping("/res/scene")
@RequiredArgsConstructor
public class ResSceneController {

    private final ResSceneService sceneService;

    @GetMapping("/page")
    public R<Page<ResSceneVO>> page(ResSceneQuery query) {
        return R.ok(sceneService.page(query));
    }

    @GetMapping("/{id}")
    public R<ResSceneVO> getById(@PathVariable Long id) {
        return R.ok(sceneService.getById(id));
    }

    @PostMapping
    public R<Long> create(@RequestBody ResSceneDTO dto) {
        return R.ok(sceneService.create(dto));
    }

    @PutMapping
    public R<Void> update(@RequestBody ResSceneDTO dto) {
        sceneService.update(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        sceneService.delete(id);
        return R.ok();
    }

    @GetMapping("/options")
    public R<List<ResSceneOptionVO>> options(@RequestParam(value = "dramaId", required = false) Long dramaId) {
        return R.ok(sceneService.options(dramaId));
    }

    /**
     * AI 智能衍生场景专属提示词 (scenePrompt + negativePrompt)
     */
    @PostMapping("/derive-prompts")
    public R<com.astra.freyja.dto.res.ScenePromptDeriveVO> derivePrompts(
            @RequestBody com.astra.freyja.dto.res.ScenePromptDeriveDTO dto) {
        return R.ok(sceneService.derivePrompts(dto));
    }

    /**
     * AI 智能衍生场景专属提示词 (流式 SSE)
     */
    @PostMapping(value = "/derive-prompts-stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter derivePromptsStream(
            @RequestBody com.astra.freyja.dto.res.ScenePromptDeriveDTO dto) {
        return sceneService.derivePromptsStream(dto);
    }

    /**
     * 构建场景提示词任务导出包 (专供外部 AI 手工生成通道复制使用)
     */
    @PostMapping("/derive-prompt-package")
    public R<com.astra.freyja.dto.res.AssetPromptPackageVO> derivePromptPackage(
            @RequestBody com.astra.freyja.dto.res.ScenePromptDeriveDTO dto) {
        return R.ok(sceneService.buildPromptPackage(dto));
    }

    /**
     * 解析并校验外部 AI 返回的场景提示词结果文本 (清洗 Markdown、校验字段并比对指纹)
     */
    @PostMapping("/parse-derived-prompt")
    public R<com.astra.freyja.dto.res.ScenePromptValidationResult> parseDerivedPrompt(
            @RequestBody com.astra.freyja.dto.res.ScenePromptParseRequestDTO dto) {
        return R.ok(sceneService.parseAndValidateDerivedPrompt(dto));
    }
}
