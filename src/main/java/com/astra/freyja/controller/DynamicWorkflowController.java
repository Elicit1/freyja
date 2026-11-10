package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.drama.DynamicWorkflowOptions.*;
import com.astra.freyja.service.video.DynamicWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/production/dynamic-workflows") @RequiredArgsConstructor
public class DynamicWorkflowController {
    private final DynamicWorkflowService service;
    @GetMapping public R<?> list(@RequestParam(required=false) Long providerId,@RequestParam(required=false) Long modelId){return R.ok(service.list(providerId,modelId));}
    @GetMapping("/capabilities") public R<?> capabilities(@RequestParam Long providerId){return R.ok(service.capabilities(providerId));}
    @PostMapping public R<?> save(@RequestBody DynamicWorkflowService.Edit edit){return R.ok(service.save(edit));}
    @DeleteMapping("/{id}") public R<?> delete(@PathVariable Long id,@RequestParam Integer version){service.delete(id,version);return R.ok();}
    public record Preview(Long providerId,Long modelId,String size,Selection selection){}
    @PostMapping("/preview") public R<Plan> preview(@RequestBody Preview req){return R.ok(service.resolve(req.selection(),req.providerId(),req.modelId(),req.size(),null));}
    @GetMapping("/tasks/{taskId}/steps") public R<?> steps(@PathVariable String taskId){return R.ok(service.steps(taskId));}
}
