package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.DictTypeDTO;
import com.astra.freyja.dto.DictTypeQuery;
import com.astra.freyja.entity.SysDictType;
import com.astra.freyja.service.DictTypeService;
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
@RequestMapping("/dict/type")
@RequiredArgsConstructor
public class DictTypeController {

    private final DictTypeService dictTypeService;

    @GetMapping("/page")
    public R<Page<SysDictType>> page(DictTypeQuery query) {
        return R.ok(dictTypeService.page(query));
    }

    @GetMapping("/{id}")
    public R<SysDictType> getById(@PathVariable Long id) {
        return R.ok(dictTypeService.getById(id));
    }

    @PostMapping
    public R<Void> create(@RequestBody DictTypeDTO dto) {
        dictTypeService.create(dto);
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody DictTypeDTO dto) {
        dictTypeService.update(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        dictTypeService.delete(id);
        return R.ok();
    }
}