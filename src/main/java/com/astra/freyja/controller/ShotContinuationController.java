package com.astra.freyja.controller;

import com.astra.freyja.common.*;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.video.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/drama/shot/{id}/continuation") @RequiredArgsConstructor
public class ShotContinuationController {
    private final DramaShotMapper shots;
    private final AiModelMapper models;
    private final AiProviderMapper providers;
    private final ShotContinuationService service;
    private final ShotContinuationPlanner planner;
    private final ShotContinuationReferenceSplitter referenceSplitter;
    private final com.astra.freyja.service.ShotVideoReferenceService videoReferences;
    private final ShotContinuationPreparation preparation;
    private final ShotContinuationAiService aiPlanning;
    private final com.astra.freyja.service.DramaEpisodeService episodes;
    private final ObjectMapper json;
    @Data public static class PreviewRequest {private ShotContinuation draft;private boolean auto;private Long providerId;private String modelCode;private java.util.List<String> selectedSegmentIds;private String requirements;}
    /** Editing and frame planning never require a render provider or an online GPU. */
    @PostMapping("/plan") public R<ShotContinuationPlan> plan(@PathVariable Long id,@RequestBody PreviewRequest request){
        shot(id);return R.ok(planner.plan(request.getDraft(),request.isAuto()));
    }
    @PostMapping("/plan-task") public R<String> planTask(@PathVariable Long id,@RequestBody PreviewRequest request)throws Exception{
        if(request.getProviderId()==null||request.getModelCode()==null)throw new BizException(400,"请选择文本规划模型");
        var model=models.selectOne(new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId,request.getProviderId()).eq(AiModel::getModelCode,request.getModelCode()).eq(AiModel::getModelType,"CHAT").eq(AiModel::getStatus,1));
        if(model==null)throw new BizException(400,"文本规划模型不可用");
        return R.ok(aiPlanning.start(shot(id),request.getDraft(),request.getProviderId(),request.getModelCode(),request.getSelectedSegmentIds(),request.getRequirements()));
    }
    @Data public static class PrepareRequest {private Long providerId;private String modelCode;private String size;private String planHash;}
    @PostMapping("/prepare") public R<ShotContinuationPreparation.State> prepare(@PathVariable Long id,@RequestBody PrepareRequest request){
        var provider=providers.selectById(request.getProviderId());var model=models.selectOne(new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId,request.getProviderId()).eq(AiModel::getModelCode,request.getModelCode()).eq(AiModel::getStatus,1));
        if(provider==null||model==null||!Integer.valueOf(1).equals(provider.getStatus()))throw new BizException(400,"请选择已启用的提供商和模型");
        return R.ok(preparation.start(shot(id),model,provider,VideoBatchPreparation.normalizeSize(request.getSize()),request.getPlanHash()));
    }
    @GetMapping("/preparation/{preparationId}") public R<ShotContinuationPreparation.State> preparation(@PathVariable Long id,@PathVariable String preparationId){shot(id);return R.ok(preparation.get(id,preparationId));}
    @GetMapping public R<ShotContinuation> get(@PathVariable Long id){return R.ok(service.read(shot(id)));}
    @GetMapping("/presets") public R<java.util.List<ShotContinuationPlanner.SegmentPreset>> presets(@PathVariable Long id){shot(id);return R.ok(planner.presets());}
    @Data public static class SplitReferenceRequest {private ShotContinuation draft;private ShotRefVideoDTO referenceVideo;private java.util.List<String> targetSegmentIds;}
    @PostMapping("/split-reference") public R<ShotContinuationPlan> splitReference(@PathVariable Long id,@RequestBody SplitReferenceRequest request){
        var current=shot(id);
        if(!"REFERENCE_MODE".equals(current.getGenerationMode()))throw new BizException(400,"参考视频切分仅用于参考生成模式");
        planner.plan(request.getDraft(),false);
        var measured=videoReferences.probe(id,request.getReferenceVideo());
        return R.ok(referenceSplitter.split(request.getDraft(),request.getReferenceVideo(),measured.getDuration(),request.getTargetSegmentIds()));
    }
    @PutMapping @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public R<Void> save(@PathVariable Long id,@RequestBody ShotContinuation draft)throws Exception{
        var current=shot(id);if(draft.isEnabled())planner.plan(draft,false);else if(!Double.isFinite(draft.getDurationSeconds())||draft.getDurationSeconds()<=0||draft.getDurationSeconds()>300)throw new BizException(400,"时长无效");
        var update=new UpdateWrapper<DramaShot>().eq("id",id).set("continuation_json",com.astra.freyja.util.VideoReferenceSnapshotJson.write(draft,json));
        if(draft.isEnabled())update.set("duration",draft.getDurationSeconds());
        else if(current.getDuration()!=null&&current.getDuration().compareTo(new java.math.BigDecimal("15"))>0)update.set("duration",15);
        if(shots.update(null,update)!=1)throw new BizException(409,"镜头配置保存失败");
        if(current.getEpisodeId()!=null)episodes.recalculateEpisodeDuration(current.getEpisodeId());return R.ok();
    }
    @PostMapping("/preview") public R<ShotContinuationPlan> preview(@PathVariable Long id,@RequestBody PreviewRequest request){
        shot(id);var provider=providers.selectById(request.getProviderId());
        var model=models.selectOne(new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId,request.getProviderId()).eq(AiModel::getModelCode,request.getModelCode()).eq(AiModel::getStatus,1));
        if(provider==null||!Integer.valueOf(1).equals(provider.getStatus())||model==null||!java.util.Set.of("TXT2VIDEO_REF","TXT2VIDEO_FIRST_LAST").contains(model.getModelType()))throw new BizException(400,"请选择已启用的视频提供商与模型");
        return R.ok(service.preview(request.getDraft(),request.isAuto(),model,provider));
    }
    private DramaShot shot(Long id){var shot=shots.selectById(id);if(shot==null)throw new BizException(404,"镜头不存在");return shot;}
}
