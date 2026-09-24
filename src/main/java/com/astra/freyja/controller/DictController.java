package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.DictDataDTO;
import com.astra.freyja.dto.DictDataQuery;
import com.astra.freyja.entity.SysDictData;
import com.astra.freyja.service.DictService;
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

import java.util.List;

@RestController
@RequestMapping("/dict")
@RequiredArgsConstructor
public class DictController {

    private final DictService dictService;

    @GetMapping("/type/{dictType}/data")
    public R<List<SysDictData>> listByType(@PathVariable String dictType) {
        return R.ok(dictService.listByType(dictType));
    }

    @GetMapping("/data/page")
    public R<Page<SysDictData>> page(DictDataQuery query) {
        return R.ok(dictService.page(query));
    }

    @GetMapping("/data/{id}")
    public R<SysDictData> getById(@PathVariable Long id) {
        return R.ok(dictService.getById(id));
    }

    @PostMapping("/data")
    public R<Void> create(@RequestBody DictDataDTO dto) {
        dictService.create(dto);
        return R.ok();
    }

    @PutMapping("/data")
    public R<Void> update(@RequestBody DictDataDTO dto) {
        dictService.update(dto);
        return R.ok();
    }

    @DeleteMapping("/data/{id}")
    public R<Void> delete(@PathVariable Long id) {
        dictService.delete(id);
        return R.ok();
    }

    @DeleteMapping("/cache/{dictType}")
    public R<Void> evict(@PathVariable String dictType) {
        dictService.evict(dictType);
        return R.ok();
    }
}