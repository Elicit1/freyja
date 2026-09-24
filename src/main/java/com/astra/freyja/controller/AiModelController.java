package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.AiModelDTO;
import com.astra.freyja.dto.AiModelQuery;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.service.AiModelService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/model")
@RequiredArgsConstructor
public class AiModelController {

    private final AiModelService aiModelService;

    @GetMapping("/page")
    public R<Page<AiModel>> page(AiModelQuery query) {
        return R.ok(aiModelService.page(query));
    }

    @GetMapping("/{id}")
    public R<AiModel> getById(@PathVariable Long id) {
        return R.ok(aiModelService.getById(id));
    }

    @PostMapping
    public R<Void> create(@RequestBody AiModelDTO dto) {
        aiModelService.create(dto);
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody AiModelDTO dto) {
        aiModelService.update(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        aiModelService.delete(id);
        return R.ok();
    }
}