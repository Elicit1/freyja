package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.AiProviderDTO;
import com.astra.freyja.dto.AiProviderQuery;
import com.astra.freyja.dto.AiProviderVO;
import com.astra.freyja.entity.AiModel;
import com.astra.freyja.service.AiModelService;
import com.astra.freyja.service.AiProviderService;
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

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/ai/provider")
@RequiredArgsConstructor
public class AiProviderController {

    private final AiProviderService aiProviderService;
    private final AiModelService aiModelService;

    @GetMapping("/page")
    public R<Page<AiProviderVO>> page(AiProviderQuery query) {
        return R.ok(aiProviderService.page(query));
    }

    @GetMapping("/list/enabled")
    public R<List<AiProviderVO>> listEnabled() {
        return R.ok(aiProviderService.listEnabled());
    }

    @GetMapping("/{id}")
    public R<AiProviderVO> getById(@PathVariable Long id) {
        return R.ok(aiProviderService.getById(id));
    }

    @PostMapping
    public R<Void> create(@RequestBody AiProviderDTO dto) {
        aiProviderService.create(dto);
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody AiProviderDTO dto) {
        aiProviderService.update(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        aiProviderService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/test")
    public R<Void> test(@PathVariable Long id, @RequestParam(required = false) String modelCode) {
        aiProviderService.test(id, modelCode);
        return R.ok();
    }

    @GetMapping("/{id}/model/list")
    public R<List<AiModel>> modelList(@PathVariable Long id,
            @RequestParam(required = false) String modelType) {
        List<AiModel> all = aiModelService.listByProviderId(id);
        if (StringUtils.isBlank(modelType)) {
            return R.ok(all);
        }
        Set<String> types = Set.of(modelType.split(","));
        return R.ok(all.stream()
                .filter(m -> m.getModelType() != null && types.contains(m.getModelType()))
                .toList());
    }
}