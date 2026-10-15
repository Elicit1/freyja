package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.drama.VideoRenderBatchRequest;
import com.astra.freyja.dto.task.TaskCancelResultVO;
import com.astra.freyja.service.video.VideoBatchModels.*;
import com.astra.freyja.service.video.VideoRenderBatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/drama/video-render-batches")
@RequiredArgsConstructor
public class VideoRenderBatchController {
    private final VideoRenderBatchService service;
    @GetMapping("/selection-tree") public R<List<Node>> tree(@RequestParam Long dramaId){return R.ok(service.tree(dramaId));}
    @PostMapping("/preview") public R<Preview> preview(@RequestBody VideoRenderBatchRequest req){return R.ok(service.preview(req));}
    @PostMapping public R<Batch> create(@RequestBody VideoRenderBatchRequest req){return R.ok(service.create(req));}
    @GetMapping public R<List<Batch>> list(@RequestParam(defaultValue="true") boolean active,@RequestParam(defaultValue="50") int limit){return R.ok(service.list(active,limit));}
    @GetMapping("/{id}") public R<Batch> detail(@PathVariable Long id){return R.ok(service.detail(id));}
    @GetMapping("/{id}/items") public R<List<Item>> items(@PathVariable Long id){return R.ok(service.detail(id).getItems());}
    @PostMapping("/{id}/cancel") public R<TaskCancelResultVO> cancel(@PathVariable Long id){return R.ok(service.cancel(id));}
    public record ReconcileRequest(String taskId,String evidence) {}
    @PostMapping("/{id}/reconcile") public R<Void> reconcile(@PathVariable Long id,@RequestBody ReconcileRequest req){service.reconcile(id,req.taskId(),req.evidence());return R.ok();}
    @PostMapping("/{id}/retry-archive") public R<Void> archive(@PathVariable Long id,@RequestBody ReconcileRequest req){service.retryArchive(id,req.taskId());return R.ok();}
}
