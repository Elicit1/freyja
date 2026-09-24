package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.drama.DramaShotGroupDTO;
import com.astra.freyja.dto.drama.DramaShotGroupMergeDTO;
import com.astra.freyja.dto.drama.DramaShotGroupReorderDTO;
import com.astra.freyja.dto.drama.DramaShotGroupSplitDTO;
import com.astra.freyja.dto.drama.DramaShotGroupVO;
import com.astra.freyja.service.DramaShotGroupService;
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
 * 连续镜头组管理 Controller。
 */
@RestController
@RequestMapping("/drama/shot-group")
@RequiredArgsConstructor
public class DramaShotGroupController {

    private final DramaShotGroupService shotGroupService;

    @GetMapping("/list/{sceneId}")
    public R<List<DramaShotGroupVO>> listBySceneId(@PathVariable Long sceneId) {
        return R.ok(shotGroupService.listBySceneId(sceneId));
    }

    @GetMapping("/list-by-episode")
    public R<List<DramaShotGroupVO>> listByEpisodeId(@RequestParam("episodeId") Long episodeId) {
        return R.ok(shotGroupService.listByEpisodeId(episodeId));
    }

    @GetMapping("/{id}")
    public R<DramaShotGroupVO> getById(@PathVariable Long id) {
        return R.ok(shotGroupService.getById(id));
    }

    @PostMapping
    public R<Long> create(@RequestBody DramaShotGroupDTO dto) {
        return R.ok(shotGroupService.create(dto));
    }

    @PutMapping
    public R<Void> update(@RequestBody DramaShotGroupDTO dto) {
        shotGroupService.update(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        shotGroupService.delete(id);
        return R.ok();
    }

    @PostMapping("/split")
    public R<Long> splitGroup(@RequestBody DramaShotGroupSplitDTO dto) {
        return R.ok(shotGroupService.splitGroup(dto));
    }

    @PostMapping("/merge")
    public R<Long> mergeGroups(@RequestBody DramaShotGroupMergeDTO dto) {
        return R.ok(shotGroupService.mergeGroups(dto));
    }

    @PutMapping("/{groupId}/reorder-shots")
    public R<Void> reorderShotsInGroup(@PathVariable Long groupId, @RequestBody List<Long> shotIds) {
        shotGroupService.reorderShotsInGroup(groupId, shotIds);
        return R.ok();
    }

    @PutMapping("/reorder-groups")
    public R<Void> reorderGroups(@RequestBody DramaShotGroupReorderDTO dto) {
        shotGroupService.reorderGroups(dto);
        return R.ok();
    }
}
