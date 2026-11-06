package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.*;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.entity.*;
import com.astra.freyja.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class ShotContinuationApplication {
    private final DramaShotMapper shots;
    private final AiModelMapper models;
    private final AiProviderMapper providers;
    private final ShotContinuationService service;
    private final ShotContinuationPlanner planner;
    private final ShotContinuationReferenceSplitter splitter;
    private final ShotVideoReferenceService videos;
    private final ShotContinuationPreparation preparation;
    private final ShotContinuationAiService ai;
    private final DramaEpisodeService episodes;
    private final ObjectMapper json;
    private final ShotContinuationSelections selections;

    public DramaShot shot(Long id){var value=shots.selectById(id);if(value==null)throw new BizException(404,"镜头不存在");return value;}
    public ShotContinuation read(Long id){return service.read(shot(id));}
    public List<ShotContinuationPlanner.SegmentPreset> presets(Long id){shot(id);return planner.presets();}
    public ShotContinuationPlan plan(Long id,ShotContinuation draft,boolean auto){var shot=shot(id);selections.validate(shot,draft);return planner.plan(draft,auto);}
    @Transactional(rollbackFor=Exception.class)
    public void save(Long id,ShotContinuation draft)throws Exception{
        var current=shot(id);selections.validate(current,draft);
        if(draft.isEnabled())planner.plan(draft,false);else if(!Double.isFinite(draft.getDurationSeconds())||draft.getDurationSeconds()<=0||draft.getDurationSeconds()>300)throw new BizException(400,"时长无效");
        var update=new UpdateWrapper<DramaShot>().eq("id",id).set("continuation_json",com.astra.freyja.util.VideoReferenceSnapshotJson.write(draft,json));
        if(draft.isEnabled())update.set("duration",draft.getDurationSeconds());else if(current.getDuration()!=null&&current.getDuration().doubleValue()>15)update.set("duration",15);
        if(shots.update(null,update)!=1)throw new BizException(409,"镜头配置保存失败");
        if(current.getEpisodeId()!=null)episodes.recalculateEpisodeDuration(current.getEpisodeId());
    }
    public AiModel model(Long providerId,String code,String type){
        if(providerId==null||code==null||code.isBlank())throw new BizException(400,"请选择提供商和模型");
        var provider=providers.selectById(providerId);
        var model=models.selectOne(new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId,providerId).eq(AiModel::getModelCode,code).eq(AiModel::getStatus,1));
        if(provider==null||!Integer.valueOf(1).equals(provider.getStatus())||model==null||!Integer.valueOf(1).equals(model.getStatus())||!Objects.equals(model.getProviderId(),providerId)||!Objects.equals(model.getModelType(),type))throw new BizException(400,"所选提供商或模型已停用、不存在，或与当前模式不匹配");
        return model;
    }
    public AiModel videoModel(DramaShot shot,Long provider,String code){return model(provider,code,videoModelType(shot));}
    private String videoModelType(DramaShot shot){return switch(Objects.toString(shot.getGenerationMode(),"")){case "REFERENCE_MODE"->"TXT2VIDEO_REF";case "FIRST_LAST_FRAME"->"TXT2VIDEO_FIRST_LAST";default->throw new BizException(400,"请选择有效的生成模式");};}
    public ShotContinuationPlan preview(Long id,ShotContinuation draft,boolean auto,Long provider,String code){var shot=shot(id);selections.validate(shot,draft);var model=videoModel(shot,provider,code);return service.preview(draft,auto,model,providers.selectById(provider));}
    public String planTask(Long id,ShotContinuation draft,Long provider,String code,List<String> selected,String requirements)throws Exception{var shot=shot(id);selections.validate(shot,draft);model(provider,code,"CHAT");return ai.start(shot,draft,provider,code,selected,requirements);}
    public ShotContinuationPlan split(Long id,ShotContinuation draft,ShotRefVideoDTO source,List<String> targets){
        var shot=shot(id);if(!"REFERENCE_MODE".equals(shot.getGenerationMode()))throw new BizException(400,"参考视频切分仅用于参考生成模式");
        selections.validate(shot,draft);planner.plan(draft,false);var measured=videos.probe(id,source);return splitter.split(draft,source,measured.getDuration(),targets);
    }
    public ShotContinuationPreparation.State prepare(Long id,Long provider,String code,String size,String hash){var shot=shot(id);var model=videoModel(shot,provider,code);validateSize(model,size);var draft=service.read(shot);if(!draft.isEnabled())throw new BizException(400,"请先开启并保存接续模式");selections.validate(shot,draft);return preparation.start(shot,model,providers.selectById(provider),VideoBatchPreparation.normalizeSize(size),hash);}
    public ShotContinuationPreparation.State preparation(Long id,String preparationId){shot(id);return preparation.get(id,preparationId);}
    public VideoReferencePreflightVO prepareReference(Long id,Long provider,String code){var shot=shot(id);model(provider,code,"TXT2VIDEO_REF");var req=new DramaShotRenderRequestDTO();req.setProviderId(provider);req.setWorkflowTemplateId(code);return videos.start(id,req);}
    public VideoReferencePreflightVO referencePreparation(Long id,String preflight){shot(id);return videos.get(id,preflight);}
    private List<String> defaultSizes(){var values=new TreeSet<>(VideoBatchPreparation.SIZES.values());values.addAll(List.of("1024x576","1280x736","1344x768","576x1024","736x1280","768x1344","1024x1024"));return List.copyOf(values);}
    public void validateSize(AiModel model,String raw){
        String size=VideoBatchPreparation.normalizeSize(raw);var options=modelOptions(model);
        var sizes=options.path("supportedSizes");if(!sizes.isArray()&&!defaultSizes().contains(size))throw new BizException(400,"请选择有效的尺寸选项");if(sizes.isArray()&&java.util.stream.StreamSupport.stream(sizes.spliterator(),false).noneMatch(v->size.equals(v.asText())))throw new BizException(400,"所选尺寸不在模型允许的选项中");
        var ratios=options.path("supportedAspectRatios");if(ratios.isArray()&&java.util.stream.StreamSupport.stream(ratios.spliterator(),false).noneMatch(v->matchesRatio(size,v.asText())))throw new BizException(400,"所选尺寸的画幅不在模型允许的选项中");
    }
    private com.fasterxml.jackson.databind.JsonNode modelOptions(AiModel model){try{return model.getParamsJson()==null||model.getParamsJson().isBlank()?json.createObjectNode():json.readTree(model.getParamsJson()).path("videoGeneration");}catch(Exception e){throw new BizException(400,"模型选项配置无效");}}
    private boolean matchesRatio(String size,String ratio){try{var pixels=size.split("x");var values=ratio.split(":");if(pixels.length!=2||values.length!=2)return false;double w=Double.parseDouble(values[0]),h=Double.parseDouble(values[1]);return w>0&&h>0&&Math.abs(Double.parseDouble(pixels[0])/Double.parseDouble(pixels[1])-w/h)<0.08;}catch(Exception e){return false;}}
    public Map<String,Object> options(Long id,Long provider,String code){
        var shot=shot(id);String type=videoModelType(shot);
        var enabled=providers.selectList(new LambdaQueryWrapper<AiProvider>().eq(AiProvider::getStatus,1));
        var available=models.selectList(new LambdaQueryWrapper<AiModel>().eq(AiModel::getStatus,1).eq(AiModel::getModelType,type));
        Set<Long> ids=new HashSet<>();enabled.forEach(p->ids.add(p.getId()));
        var result=new LinkedHashMap<String,Object>();result.put("presets",planner.presets());result.put("shotTypes",selections.dictionary("shot_type"));result.put("cameraMovements",selections.dictionary("camera_movement"));
        result.put("providers",enabled.stream().filter(p->available.stream().anyMatch(m->p.getId().equals(m.getProviderId()))).map(p->Map.of("id",p.getId().toString(),"name",p.getProviderName())).toList());
        result.put("models",available.stream().filter(m->ids.contains(m.getProviderId())).map(m->Map.of("id",m.getId().toString(),"providerId",m.getProviderId().toString(),"modelCode",m.getModelCode(),"name",m.getModelName())).toList());
        result.put("controlTypes",ShotControlNetOptions.CONTROL_TYPES.stream().sorted().toList());
        if(provider!=null||code!=null){var model=videoModel(shot,provider,code);var caps=service.capabilities(model,providers.selectById(provider));result.put("capabilities",caps);result.put("controlTypes",json.convertValue(caps.path("h3").path("controlTypes"),List.class));
            var declared=modelOptions(model);var sizes=declared.path("supportedSizes");result.put("outputSizes",sizes.isArray()?json.convertValue(sizes,List.class):defaultSizes());
            var ratios=declared.path("supportedAspectRatios");result.put("aspectRatios",ratios.isArray()?json.convertValue(ratios,List.class):VideoBatchPreparation.SIZES.keySet().stream().sorted().toList());}
        return result;
    }
}
