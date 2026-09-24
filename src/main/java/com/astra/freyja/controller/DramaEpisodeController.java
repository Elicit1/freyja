package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.drama.DramaEpisodeBatchDTO;
import com.astra.freyja.dto.drama.DramaEpisodeDTO;
import com.astra.freyja.dto.drama.DramaEpisodeVO;
import com.astra.freyja.service.DramaEpisodeService;
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
 * 剧集管理 Controller。
 */
@RestController
@RequestMapping("/drama/episode")
@RequiredArgsConstructor
public class DramaEpisodeController {

    private final DramaEpisodeService episodeService;

    @GetMapping("/list/{dramaId}")
    public R<List<DramaEpisodeVO>> listByDramaId(@PathVariable Long dramaId) {
        return R.ok(episodeService.listByDramaId(dramaId));
    }

    @GetMapping("/{id}")
    public R<DramaEpisodeVO> getById(@PathVariable Long id) {
        return R.ok(episodeService.getById(id));
    }

    @PostMapping
    public R<Long> create(@RequestBody DramaEpisodeDTO dto) {
        return R.ok(episodeService.create(dto));
    }

    @PostMapping("/batch")
    public R<List<Long>> batchCreate(@RequestBody DramaEpisodeBatchDTO dto) {
        return R.ok(episodeService.batchCreate(dto));
    }

    @PutMapping
    public R<Void> update(@RequestBody DramaEpisodeDTO dto) {
        episodeService.update(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        episodeService.delete(id);
        return R.ok();
    }
}
