package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dao.AiModelMapper;
import com.astra.freyja.dto.drama.DynamicWorkflowOptions;
import com.astra.freyja.dto.drama.ShotVideoModelOptionVO;
import com.astra.freyja.service.ShotVideoCapabilityService;
import com.astra.freyja.service.ShotVideoReferenceService;
import com.astra.freyja.service.video.VideoBatchModels.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.astra.freyja.entity.AiModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Freeze historical outputs and rerun only the authored stages of selected segments. */
@Service @RequiredArgsConstructor
public class ContinuationSegmentEnhancementService {
    private final VideoBatchStore store;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DynamicWorkflowService workflows;
    private final AiModelMapper models;
    private final ShotVideoCapabilityService videoCapabilities;
    private final ShotVideoReferenceService videoReferences;

    public record Segment(String id,int index,String name,int startFrame,int endFrame,String videoUrl,boolean allowed,String reason,DynamicWorkflowOptions options,boolean baseAllowed){}
    public record Summary(boolean supported,String reason,String shotId,String providerId,String modelCode,DynamicWorkflowOptions options,List<Segment> segments){}

    private Snapshot source(String task){
        var item=store.task(task);
        if(item==null)throw new BizException(404,"渲染任务不存在");
        if(!"SUCCESS".equals(item.getStatus()))throw new BizException(409,"请等待原任务成功完成后替换片段");
        try{
            var snapshot=json.readValue(item.getResolvedJson()!=null?item.getResolvedJson():item.getSnapshotJson(),Snapshot.class);
            var req=snapshot.getRequest();
            if(req.getContinuationPlan()==null||req.getContinuationSegments()==null)
                throw new BizException(400,"此任务没有可重跑的接续片段");
            return snapshot;
        }catch(BizException e){throw e;}catch(Exception e){throw new BizException(409,"原任务快照无法读取");}
    }
    private List<SegmentOutput> outputs(String task,Snapshot snapshot){
        var result=new ArrayList<SegmentOutput>();
        for(int i=0;i<snapshot.getRequest().getContinuationSegments().size();i++){
            var base=jdbc.queryForList("SELECT output_url,result_manifest_json FROM render_task_step WHERE task_id=? AND step_key=? AND status='SUCCESS'",task,"segment-"+i);
            if(base.size()!=1||base.getFirst().get("output_url")==null)throw new BizException(409,"原任务缺少完整的一采产物，不能局部替换");
            var output=new SegmentOutput();output.setBaseVideoUrl(base.getFirst().get("output_url").toString());output.setVideoUrl(output.getBaseVideoUrl());
            var manifest=base.getFirst().get("result_manifest_json");
            if(manifest!=null)try{
                output.setLatent(json.readValue(manifest instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):manifest.toString(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
            }catch(Exception e){throw new BizException(409,"原始 latent 归档清单无效");}
            var plan=snapshot.getRequest().getContinuationSegments().get(i).getRequest().getDynamicWorkflowPlan();
            if(plan!=null&&(plan.getOptions().isUpscale()||plan.getOptions().isFaceRepair())){
                if(plan.getOptions().isUpscale()){
                    var saved=jdbc.queryForList("SELECT result_manifest_json FROM dynamic_workflow_step WHERE task_id=? AND step_key=? AND status='SUCCESS'",task,String.format(Locale.ROOT,"segment-%03d:01:UPSCALE",i));
                    if(!saved.isEmpty()&&saved.getFirst().get("result_manifest_json")!=null)try{
                        var value=saved.getFirst().get("result_manifest_json");
                        output.setUpscaleLatent(json.readValue(value instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):value.toString(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
                    }catch(Exception e){throw new BizException(409,"二采 latent 归档清单无效");}
                }
                String stage=plan.getOptions().isFaceRepair()?"02:FACE":"01:UPSCALE";
                var rows=jdbc.queryForList("SELECT output_url FROM dynamic_workflow_step WHERE task_id=? AND step_key=? AND status='SUCCESS'",task,String.format(Locale.ROOT,"segment-%03d:",i)+stage);
                if(rows.size()!=1||rows.getFirst().get("output_url")==null)throw new BizException(409,"原任务缺少完整增强产物，不能局部替换");
                output.setVideoUrl(rows.getFirst().get("output_url").toString());
            }
            result.add(output);
        }
        return result;
    }
    private boolean nativeAvailable(SegmentOutput output){
        var latent=output.getLatent();
        return latent!=null&&"FIRST_PASS".equals(latent.get("stage"))&&"FREYJA_H3_AV_V1".equals(latent.get("format"))
                &&Objects.toString(latent.get("sha256"),"").matches("[a-f0-9]{64}")&&latent.get("objectPath")!=null;
    }
    public Summary summary(String task){
        try{
            var snapshot=source(task);var req=snapshot.getRequest();var outputs=outputs(task,snapshot);var segments=new ArrayList<Segment>();
            for(int i=0;i<outputs.size();i++){
                var range=req.getContinuationPlan().getRanges().get(i);
                var child=req.getContinuationPlan().getDraft().getSegments().get(i);
                boolean allowed=nativeAvailable(outputs.get(i));
                var enhancement=req.getContinuationSegments().get(i).getRequest().getDynamicWorkflowPlan();
                segments.add(new Segment(range.getSegmentId(),i+1,child.getName(),range.getKeepStartFrame(),range.getKeepEndFrame(),outputs.get(i).getVideoUrl(),allowed,allowed?null:"此片段缺少可复用的原始生成数据",enhancement==null?null:enhancement.getOptions(),true));
            }
            return new Summary(true,null,snapshot.getShot().getId().toString(),req.getProviderId().toString(),req.getWorkflowTemplateId(),req.getDynamicWorkflowPlan()==null?null:req.getDynamicWorkflowPlan().getOptions(),segments);
        }catch(BizException e){return new Summary(false,e.getMessage(),null,null,null,null,List.of());}
    }
    public Snapshot prepare(String task,String segmentId,DynamicWorkflowOptions options){
        return prepare(task,Collections.singletonList(segmentId),options);
    }
    public static List<String> validateSegmentIds(List<String> ids){
        if(ids==null||ids.isEmpty()||ids.stream().anyMatch(id->id==null||id.isBlank())||new HashSet<>(ids).size()!=ids.size())
            throw new BizException(400,"请选择至少一个片段，且片段标识不能为空或重复");
        return ids.stream().sorted().toList();
    }
    public Snapshot prepare(String task,List<String> segmentIds,DynamicWorkflowOptions options){
        return prepare(task,segmentIds,options,"UPSCALE_ONLY");
    }
    public static String validateRerunMode(String mode){
        if(mode==null)return "UPSCALE_ONLY";
        if(!Set.of("UPSCALE_ONLY","BASE_ONLY","BASE_AND_UPSCALE").contains(mode))throw new BizException(400,"无效的片段重跑范围");
        return mode;
    }
    public Snapshot prepare(String task,List<String> segmentIds,DynamicWorkflowOptions options,String mode){
        mode=validateRerunMode(mode);
        boolean baseOnly="BASE_ONLY".equals(mode),rerunBase=!"UPSCALE_ONLY".equals(mode);
        var selected=new HashSet<>(validateSegmentIds(segmentIds));
        var snapshot=source(task);var req=snapshot.getRequest();var outputs=outputs(task,snapshot);
        var indices=new ArrayList<Integer>();
        for(int i=0;i<req.getContinuationPlan().getRanges().size();i++)if(selected.contains(req.getContinuationPlan().getRanges().get(i).getSegmentId()))indices.add(i);
        if(indices.size()!=selected.size())throw new BizException(400,"片段不属于来源任务");
        for(int index:indices)if(!rerunBase&&!nativeAvailable(outputs.get(index)))throw new BizException(409,"片段 "+(index+1)+" 没有可复用的一采 latent，不能仅重跑二采");
        if(rerunBase)for(int index:indices){
            var range=req.getContinuationPlan().getRanges().get(index);
            if(index>0&&range.getEffectiveContinuity()!=null&&range.getEffectiveContinuity().requiresLatent()&&!indices.contains(index-1)&&!nativeAvailable(outputs.get(index-1)))
                throw new BizException(409,"前序片段缺少一采 latent，请连同前序片段重跑一采");
            if(range.getAnchorSourceSegmentId()!=null){
                var ranges=req.getContinuationPlan().getRanges();
                for(int anchor=0;anchor<index;anchor++)if(range.getAnchorSourceSegmentId().equals(ranges.get(anchor).getSegmentId())&&!indices.contains(anchor)&&!nativeAvailable(outputs.get(anchor)))
                    throw new BizException(409,"锚点片段缺少一采 latent，请连同锚点片段重跑一采");
            }
        }
        if(!baseOnly&&(options==null||!options.isUpscale()||!"FREYJA".equals(options.getUpscaleEngine())))throw new BizException(400,"片段替换须使用 FREYJA 二采");
        var model=models.selectOne(new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId,req.getProviderId()).eq(AiModel::getModelCode,req.getWorkflowTemplateId()).last("LIMIT 1"));
        if(model==null)throw new BizException(409,"原视频模型已不存在");
        ShotVideoModelOptionVO referenceModel=null;
        for(int index:indices){
            var child=req.getContinuationSegments().get(index);
            var original=child.getRequest().getDynamicWorkflowPlan();
            var childOptions=options==null?new DynamicWorkflowOptions():json.convertValue(options,DynamicWorkflowOptions.class);
            childOptions.setUpscale(!baseOnly);
            childOptions.setFaceRepair(original!=null&&original.getOptions().isFaceRepair());
            if(original!=null)childOptions.setFaceDenoise(original.getOptions().getFaceDenoise());
            var selection=new DynamicWorkflowOptions.Selection();selection.setOptions(childOptions);
            var parentPlan=req.getDynamicWorkflowPlan();
            var plan=workflows.resolve(selection,req.getProviderId(),model.getId(),req.getSize(),parentPlan==null?null:parentPlan.getNodeId());
            if(!baseOnly&&(plan==null||!plan.isNativeLatentInput()))throw new BizException(409,"请更新网关和算力节点以支持原始 latent 二采");
            if(!baseOnly&&parentPlan!=null&&!Objects.equals(plan.getOutputSize(),parentPlan.getOutputSize()))throw new BizException(400,"替换片段须保持原视频输出尺寸");
            if(plan!=null)plan.setReuseFromTaskId(null);
            var references=child.getRequest().getResolvedRefVideos();
            if(references!=null&&!references.isEmpty()){
                if(referenceModel==null)referenceModel=videoCapabilities.describe(model);
                videoReferences.validateSnapshot(references,referenceModel,child.getShot());
                if(plan!=null&&!Objects.equals(referenceModel.getNodeId(),plan.getNodeId()))throw new BizException(409,"参考视频与二采算力节点不一致，请重新预检");
                // A new replacement pins the current contract only after validating its archived media.
                child.getRequest().setReferenceVideoRevision(referenceModel.getWorkflowRevision());
                child.getRequest().setReferenceVideoNodeId(referenceModel.getNodeId());
            }
            child.getRequest().setDynamicWorkflowPlan(plan);
            if(rerunBase&&plan!=null&&plan.isNativeLatentInput()){
                req.getContinuationPlan().getRanges().get(index).setSaveLatent(true);
                child.getRequest().getH3Options().put("save_latent",true);
                child.getRequest().getH3Options().put("schema_version",2);
            }
            if(rerunBase)child.getRequest().setDynamicWorkflowStage(plan==null?null:Map.of("schema_version",1,"stage","BASE","revision",plan.getCapabilityRevision()));
            if(rerunBase)child.getRequest().setSeed(java.util.concurrent.ThreadLocalRandom.current().nextLong(0,Long.MAX_VALUE));
        }
        for(int index:indices)if(!baseOnly&&index>0&&req.getContinuationSegments().get(index).getRequest().getDynamicWorkflowPlan().isSecondPassContinuity()&&!indices.contains(index-1)){
            var predecessor=outputs.get(index-1).getUpscaleLatent();
            if(predecessor==null||!"SECOND_PASS".equals(predecessor.get("stage")))throw new BizException(409,"前序片段缺少二采 latent，请从首段开始连续选择并重跑二采");
        }
        var plan=req.getContinuationSegments().get(indices.getFirst()).getRequest().getDynamicWorkflowPlan();
        var selection=new DynamicWorkflowOptions.Selection();selection.setOptions(plan==null?null:plan.getOptions());
        if(!baseOnly){req.setDynamicWorkflowPlan(plan);req.setDynamicWorkflow(selection);}
        req.getContinuationPlan().getDraft().setReuseFromTaskId(null);
        var replacement=new SegmentReplacement();replacement.setRerunMode(mode);replacement.setSourceTaskId(task);replacement.setSegmentIndices(indices);replacement.setOutputs(outputs);snapshot.setSegmentReplacement(replacement);
        snapshot.setFirstFramePlan(null);snapshot.setLastFramePlan(null);
        return snapshot;
    }
    public void copySteps(String source,String target,int index){
        copySteps(source,target,List.of(index));
    }
    public void copySteps(String source,String target,List<Integer> indices){
        copySteps(source,target,indices,"UPSCALE_ONLY");
    }
    public void copySteps(String source,String target,List<Integer> indices,String mode){
        var baseArgs=new ArrayList<Object>(List.of(target,source,source));
        String excluded="";
        if(!"UPSCALE_ONLY".equals(mode)){excluded=" AND step_key NOT IN ("+String.join(",",Collections.nCopies(indices.size(),"?"))+")";for(int index:indices)baseArgs.add("segment-"+index);}
        jdbc.update("INSERT INTO render_task_step(task_id,step_key,input_hash,execution_id,status,output_url,prompt_id,node_id,source_task_id,result_manifest_json,updated_at) SELECT ?,step_key,input_hash,execution_id,status,output_url,prompt_id,node_id,?,result_manifest_json,CURRENT_TIMESTAMP FROM render_task_step WHERE task_id=? AND status='SUCCESS'"+excluded,baseArgs.toArray());
        var args=new ArrayList<Object>(List.of(target,source,source));
        for(int index:indices)args.add(String.format(Locale.ROOT,"segment-%03d:%%",index));
        jdbc.update("INSERT INTO dynamic_workflow_step(task_id,step_key,input_hash,execution_id,stage,status,output_url,prompt_id,node_id,source_task_id,result_manifest_json,updated_at) SELECT ?,step_key,input_hash,execution_id,stage,status,output_url,prompt_id,node_id,?,result_manifest_json,CURRENT_TIMESTAMP FROM dynamic_workflow_step WHERE task_id=? AND status='SUCCESS' AND "+String.join(" AND ",Collections.nCopies(indices.size(),"step_key NOT LIKE ?")),args.toArray());
    }
}
