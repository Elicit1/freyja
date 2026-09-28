package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.res.ResKeyframeDTO;
import com.astra.freyja.dto.res.ResKeyframeOptionVO;
import com.astra.freyja.dto.res.ResKeyframeQuery;
import com.astra.freyja.dto.res.ResKeyframeVO;
import com.astra.freyja.service.ResKeyframeService;
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
 * 关键帧资产管理 Controller。
 */
@RestController
@RequestMapping("/res/keyframe")
@RequiredArgsConstructor
public class ResKeyframeController {

    private final ResKeyframeService keyframeService;

    @GetMapping("/page")
    public R<Page<ResKeyframeVO>> page(ResKeyframeQuery query) {
        return R.ok(keyframeService.page(query));
    }

    @GetMapping("/{id}")
    public R<ResKeyframeVO> getById(@PathVariable Long id) {
        return R.ok(keyframeService.getById(id));
    }

    @PostMapping
    public R<Long> create(@RequestBody ResKeyframeDTO dto) {
        return R.ok(keyframeService.create(dto));
    }

    @PutMapping
    public R<Void> update(@RequestBody ResKeyframeDTO dto) {
        keyframeService.update(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        keyframeService.delete(id);
        return R.ok();
    }

    @PutMapping("/{id}/status")
    public R<Void> changeStatus(@PathVariable Long id, @RequestParam("status") Integer status) {
        keyframeService.changeStatus(id, status);
        return R.ok();
    }

    @GetMapping("/options")
    public R<List<ResKeyframeOptionVO>> options(
            @RequestParam(value = "dramaId", required = false) Long dramaId,
            @RequestParam(value = "shotId", required = false) Long shotId) {
        return R.ok(keyframeService.options(dramaId, shotId));
    }
}
