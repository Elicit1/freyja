package com.astra.freyja.controller;

import com.astra.freyja.common.R;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.service.video.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/drama/shot/{id}/continuation") @RequiredArgsConstructor
public class ShotContinuationController {
    private final ShotContinuationApplication application;
    @Data public static class PreviewRequest {private ShotContinuation draft;private boolean auto;private Long providerId;private String modelCode;private java.util.List<String> selectedSegmentIds;private String requirements;}
    @Data public static class PrepareRequest {private Long providerId;private String modelCode;private String size;private String planHash;}
    @Data public static class SplitReferenceRequest {private ShotContinuation draft;private ShotRefVideoDTO referenceVideo;private java.util.List<String> targetSegmentIds;}
    @GetMapping public R<ShotContinuation> get(@PathVariable Long id){return R.ok(application.read(id));}
    @GetMapping("/presets") public R<java.util.List<ShotContinuationPlanner.SegmentPreset>> presets(@PathVariable Long id){return R.ok(application.presets(id));}
    @GetMapping("/options") public R<java.util.Map<String,Object>> options(@PathVariable Long id,@RequestParam(required=false) Long providerId,@RequestParam(required=false) String modelCode){return R.ok(application.options(id,providerId,modelCode));}
    @PostMapping("/plan") public R<ShotContinuationPlan> plan(@PathVariable Long id,@RequestBody PreviewRequest r){return R.ok(application.plan(id,r.getDraft(),r.isAuto()));}
    @PostMapping("/plan-task") public R<String> planTask(@PathVariable Long id,@RequestBody PreviewRequest r)throws Exception{return R.ok(application.planTask(id,r.getDraft(),r.getProviderId(),r.getModelCode(),r.getSelectedSegmentIds(),r.getRequirements()));}
    @PutMapping public R<Void> save(@PathVariable Long id,@RequestBody ShotContinuation draft)throws Exception{application.save(id,draft);return R.ok();}
    @PostMapping("/preview") public R<ShotContinuationPlan> preview(@PathVariable Long id,@RequestBody PreviewRequest r){return R.ok(application.preview(id,r.getDraft(),r.isAuto(),r.getProviderId(),r.getModelCode()));}
    @PostMapping("/split-reference") public R<ShotContinuationPlan> splitReference(@PathVariable Long id,@RequestBody SplitReferenceRequest r){return R.ok(application.split(id,r.getDraft(),r.getReferenceVideo(),r.getTargetSegmentIds()));}
    @PostMapping("/prepare") public R<ShotContinuationPreparation.State> prepare(@PathVariable Long id,@RequestBody PrepareRequest r){return R.ok(application.prepare(id,r.getProviderId(),r.getModelCode(),r.getSize(),r.getPlanHash()));}
    @GetMapping("/preparation/{preparationId}") public R<ShotContinuationPreparation.State> preparation(@PathVariable Long id,@PathVariable String preparationId){return R.ok(application.preparation(id,preparationId));}
}
