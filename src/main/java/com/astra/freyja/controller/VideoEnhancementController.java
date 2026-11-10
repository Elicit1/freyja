package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.drama.DynamicWorkflowOptions.*;
import com.astra.freyja.service.video.DynamicWorkflowService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/production/video-enhancements") @RequiredArgsConstructor
public class VideoEnhancementController {
    private final DynamicWorkflowService service;
    private final ObjectMapper json;
    private final com.astra.freyja.service.video.ContinuationSegmentEnhancementService segments;
    private final com.astra.freyja.service.video.VideoRenderBatchService batches;
    @GetMapping("/capabilities") public R<Map<String,Object>> capabilities(@RequestParam Long providerId){
        // Spring MVC uses Jackson 3; do not expose Jackson 2 tree nodes at this boundary.
        return R.ok(json.convertValue(service.capabilities(providerId),new TypeReference<Map<String,Object>>(){}));
    }
    @GetMapping("/upscale-models") public R<Map<String,Object>> upscaleModels(@RequestParam Long providerId,
            @RequestParam String modelCode, @RequestParam String nodeId) {
        return R.ok(json.convertValue(service.upscaleModels(providerId,modelCode,nodeId),new TypeReference<Map<String,Object>>(){}));
    }
    public record Preview(Long providerId,Long modelId,String size,Selection selection){}
    @PostMapping("/preview") public R<Plan> preview(@RequestBody Preview req){return R.ok(service.resolve(req.selection(),req.providerId(),req.modelId(),req.size(),null));}
    @GetMapping("/tasks/{taskId}/steps") public R<?> steps(@PathVariable String taskId){return R.ok(service.steps(taskId));}
    @GetMapping("/tasks/{taskId}/segments") public R<?> segments(@PathVariable String taskId){return R.ok(segments.summary(taskId));}
    public record SegmentUpscale(String requestId,com.astra.freyja.dto.drama.DynamicWorkflowOptions options){}
    @PostMapping("/tasks/{taskId}/segments/{segmentId}/upscale") public R<?> upscaleSegment(@PathVariable String taskId,@PathVariable String segmentId,@RequestBody SegmentUpscale req){return R.ok(batches.enqueueSegmentEnhancement(taskId,segmentId,req.options(),req.requestId()));}
    public record SegmentsUpscale(String requestId,List<String> segmentIds,com.astra.freyja.dto.drama.DynamicWorkflowOptions options){}
    @PostMapping("/tasks/{taskId}/segments/upscale") public R<?> upscaleSegments(@PathVariable String taskId,@RequestBody SegmentsUpscale req){return R.ok(batches.enqueueSegmentEnhancements(taskId,req.segmentIds(),req.options(),req.requestId()));}
}
