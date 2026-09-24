package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.drama.DramaDTO;
import com.astra.freyja.dto.drama.DramaOptionVO;
import com.astra.freyja.dto.drama.DramaQuery;
import com.astra.freyja.dto.drama.DramaStatsVO;
import com.astra.freyja.dto.drama.DramaTreeVO;
import com.astra.freyja.dto.drama.DramaVO;
import com.astra.freyja.service.DramaService;
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

/**
 * 短剧项目管理 Controller。
 */
@RestController
@RequestMapping("/drama")
@RequiredArgsConstructor
public class DramaController {

    private final DramaService dramaService;

    @GetMapping("/page")
    public R<Page<DramaVO>> page(DramaQuery query) {
        return R.ok(dramaService.page(query));
    }

    @GetMapping("/{id}")
    public R<DramaVO> getById(@PathVariable Long id) {
        return R.ok(dramaService.getById(id));
    }

    @PostMapping
    public R<Long> create(@RequestBody DramaDTO dto) {
        return R.ok(dramaService.create(dto));
    }

    @PutMapping
    public R<Void> update(@RequestBody DramaDTO dto) {
        dramaService.update(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        dramaService.delete(id);
        return R.ok();
    }

    @GetMapping("/options")
    public R<List<DramaOptionVO>> options() {
        return R.ok(dramaService.options());
    }

    @GetMapping("/tree/{id}")
    public R<DramaTreeVO> getDramaTree(@PathVariable Long id) {
        return R.ok(dramaService.getDramaTree(id));
    }

    @GetMapping("/stats/{id}")
    public R<DramaStatsVO> getStats(@PathVariable Long id) {
        return R.ok(dramaService.getStats(id));
    }
}
