package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.res.ResPropDTO;
import com.astra.freyja.dto.res.ResPropOptionVO;
import com.astra.freyja.dto.res.ResPropQuery;
import com.astra.freyja.dto.res.ResPropVO;
import com.astra.freyja.service.ResPropService;
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
 * 道具资产管理 Controller。
 */
@RestController
@RequestMapping("/res/prop")
@RequiredArgsConstructor
public class ResPropController {

    private final ResPropService propService;

    @GetMapping("/page")
    public R<Page<ResPropVO>> page(ResPropQuery query) {
        return R.ok(propService.page(query));
    }

    @GetMapping("/{id}")
    public R<ResPropVO> getById(@PathVariable Long id) {
        return R.ok(propService.getById(id));
    }

    @PostMapping
    public R<Long> create(@RequestBody ResPropDTO dto) {
        return R.ok(propService.create(dto));
    }

    @PutMapping
    public R<Void> update(@RequestBody ResPropDTO dto) {
        propService.update(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        propService.delete(id);
        return R.ok();
    }

    @GetMapping("/options")
    public R<List<ResPropOptionVO>> options(@RequestParam(value = "dramaId", required = false) Long dramaId) {
        return R.ok(propService.options(dramaId));
    }

    /**
     * AI 智能衍生道具专属提示词 (propPrompt + negativePrompt)
     */
    @PostMapping("/derive-prompts")
    public R<com.astra.freyja.dto.res.PropPromptDeriveVO> derivePrompts(
            @RequestBody com.astra.freyja.dto.res.PropPromptDeriveDTO dto) {
        return R.ok(propService.derivePrompts(dto));
    }

    /**
     * AI 智能衍生道具专属提示词 (流式 SSE)
     */
    @PostMapping(value = "/derive-prompts-stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter derivePromptsStream(
            @RequestBody com.astra.freyja.dto.res.PropPromptDeriveDTO dto) {
        return propService.derivePromptsStream(dto);
    }

    /**
     * 构建道具提示词任务导出包 (专供外部 AI 手工生成通道复制使用)
     */
    @PostMapping("/derive-prompt-package")
    public R<com.astra.freyja.dto.res.AssetPromptPackageVO> derivePromptPackage(
            @RequestBody com.astra.freyja.dto.res.PropPromptDeriveDTO dto) {
        return R.ok(propService.buildPromptPackage(dto));
    }

    /**
     * 解析并校验外部 AI 返回的道具提示词结果文本 (清洗 Markdown、校验字段并比对指纹)
     */
    @PostMapping("/parse-derived-prompt")
    public R<com.astra.freyja.dto.res.PropPromptValidationResult> parseDerivedPrompt(
            @RequestBody com.astra.freyja.dto.res.PropPromptParseRequestDTO dto) {
        return R.ok(propService.parseAndValidateDerivedPrompt(dto));
    }
}
