package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.drama.DramaSceneDTO;
import com.astra.freyja.dto.drama.DramaSceneVO;
import com.astra.freyja.service.DramaSceneService;
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
 * 情景场次管理 Controller。
 */
@RestController
@RequestMapping("/drama/scene")
@RequiredArgsConstructor
public class DramaSceneController {

    private final DramaSceneService sceneService;

    @GetMapping("/list/{episodeId}")
    public R<List<DramaSceneVO>> listByEpisodeId(@PathVariable Long episodeId) {
        return R.ok(sceneService.listByEpisodeId(episodeId));
    }

    @GetMapping("/{id}")
    public R<DramaSceneVO> getById(@PathVariable Long id) {
        return R.ok(sceneService.getById(id));
    }

    @PostMapping
    public R<Long> create(@RequestBody DramaSceneDTO dto) {
        return R.ok(sceneService.create(dto));
    }

    @PutMapping
    public R<Void> update(@RequestBody DramaSceneDTO dto) {
        sceneService.update(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        sceneService.delete(id);
        return R.ok();
    }
}
