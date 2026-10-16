package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.task.TaskCancelResultVO;
import com.astra.freyja.service.mediaexport.ShotVideoExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.astra.freyja.service.mediaexport.VideoExportModels.*;

@RestController
@RequestMapping("/drama/shot-video-exports")
@RequiredArgsConstructor
public class ShotVideoExportController {
    private final ShotVideoExportService service;
    @GetMapping("/selection-tree") public R<List<Node>> tree(@RequestParam Long dramaId){return R.ok(service.tree(dramaId));}
    @PostMapping("/preview") public R<Preview> preview(@RequestBody Request request){return R.ok(service.preview(request));}
    @PostMapping public R<Task> create(@RequestBody Request request){return R.ok(service.create(request));}
    @GetMapping public R<Page<Task>> page(@RequestParam(required=false) Long dramaId,@RequestParam(defaultValue="1") int current,@RequestParam(defaultValue="20") int size){return R.ok(service.page(dramaId,current,size));}
    @GetMapping("/{id}") public R<Task> detail(@PathVariable Long id){return R.ok(service.detail(id));}
    @GetMapping("/{id}/items") public R<Page<Item>> items(@PathVariable Long id,@RequestParam(defaultValue="1") int current,@RequestParam(defaultValue="50") int size){return R.ok(service.items(id,current,size));}
    @PostMapping("/{id}/cancel") public R<TaskCancelResultVO> cancel(@PathVariable Long id){return R.ok(service.cancel(id));}
    public record RetryRequest(String clientRequestId) {}
    @PostMapping("/{id}/retry") public R<Task> retry(@PathVariable Long id,@RequestBody RetryRequest request){return R.ok(service.retry(id,request.clientRequestId()));}
    @PostMapping("/{id}/download-link") public R<DownloadLink> download(@PathVariable Long id){return R.ok(service.download(id));}
}
