package com.astra.freyja.service.video;

import com.astra.freyja.common.BizException;
import com.astra.freyja.dto.drama.*;
import com.astra.freyja.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** Persist every stage before HTTP submission; uncertain outcomes are reconciled, never guessed. */
@Service @RequiredArgsConstructor
public class DynamicWorkflowExecutor {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AiImageApiService api;
    private final ReferenceVideoMediaService media;
    private final AiTaskEventStore events;
    private final ContinuationArtifactService artifacts;

    public VideoGenerationResultVO render(VideoBatchModels.Snapshot snapshot)throws Exception {
        String task=snapshot.getRequest().getTaskId();
        String base=execute(snapshot,task,"single:00:BASE","BASE",null);
        String result=enhance(snapshot,base,task,"single");
        return VideoGenerationResultVO.builder().videoUrl(result).build();
    }
    public String enhance(VideoBatchModels.Snapshot snapshot,String base,String task,String prefix)throws Exception {
        return enhance(snapshot,base,task,prefix,null);
    }
    public String enhance(VideoBatchModels.Snapshot snapshot,String base,String task,String prefix,Map<String,Object> latent)throws Exception {
        var plan=snapshot.getRequest().getDynamicWorkflowPlan();
        if(plan==null)return base;
        String output=base;
        if(plan.getOptions().isUpscale())output=execute(snapshot,task,prefix+":01:UPSCALE","UPSCALE",output,plan.isNativeLatentInput()?latent:null);
        if(plan.getOptions().isFaceRepair())output=execute(snapshot,task,prefix+":02:FACE","FACE",output);
        return output;
    }
    public record Enhancement(String videoUrl,Map<String,Object> latent){}
    public Enhancement enhanceContinuation(VideoBatchModels.Snapshot snapshot,String base,String task,String prefix,
                                          Map<String,Object> baseLatent,Map<String,Object> predecessor,int overlap)throws Exception {
        var plan=snapshot.getRequest().getDynamicWorkflowPlan();
        if(plan==null||!plan.isSecondPassContinuity()||!plan.getOptions().isUpscale())throw new BizException(409,"当前快照未启用二采接续");
        if(overlap>0&&(predecessor==null||!"SECOND_PASS".equals(predecessor.get("stage"))))
            throw new BizException(409,"前序片段缺少二采 latent，请从首段开始连续重跑二采");
        var result=executeStage(snapshot,task,prefix+":01:UPSCALE","UPSCALE",base,
                plan.isNativeLatentInput()?baseLatent:null,true,predecessor,overlap);
        String output=result.videoUrl();
        if(plan.getOptions().isFaceRepair())output=execute(snapshot,task,prefix+":02:FACE","FACE",output);
        return new Enhancement(output,result.latent());
    }
    public String activeExecution(String task){
        var rows=jdbc.query("SELECT execution_id FROM dynamic_workflow_step WHERE task_id=? AND status IN ('SUBMITTING','ARCHIVING') ORDER BY updated_at DESC LIMIT 1",(rs,n)->rs.getString(1),task);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private String execute(VideoBatchModels.Snapshot snapshot,String task,String key,String stage,String source)throws Exception {
        return execute(snapshot,task,key,stage,source,null);
    }
    private String execute(VideoBatchModels.Snapshot snapshot,String task,String key,String stage,String source,Map<String,Object> latent)throws Exception {
        return executeStage(snapshot,task,key,stage,source,latent,false,null,0).videoUrl();
    }
    private Enhancement executeStage(VideoBatchModels.Snapshot snapshot,String task,String key,String stage,String source,
                                     Map<String,Object> latent,boolean saveLatent,Map<String,Object> predecessor,int overlap)throws Exception {
        var request=json.convertValue(snapshot.getRequest(),DramaShotRenderRequestDTO.class);
        var plan=request.getDynamicWorkflowPlan();
        var transport=new LinkedHashMap<String,Object>();transport.put("schema_version",1);transport.put("stage",stage);transport.put("revision",plan.getCapabilityRevision());
        if(!"BASE".equals(stage)){
            var controlSources=ShotControlNetOptions.sourceIds(request.getH3Options(),request.getDynamicWorkflowStage());
            if(!controlSources.isEmpty())transport.put("control_source_ids",new ArrayList<>(controlSources));
            request.setH3Options(null);request.setSize("UPSCALE".equals(stage)||plan.getOptions().isUpscale()?Objects.requireNonNullElse(plan.getProcessingSize(),plan.getOutputSize()):plan.getBaseSize());
            transport.put("scale","UPSCALE".equals(stage)?2:1);
            transport.put("denoise","UPSCALE".equals(stage)?plan.getOptions().getUpscaleDenoise():plan.getOptions().getFaceDenoise());
            var tiling=plan.getOptions().getUpscaleTiling();
            if("UPSCALE".equals(stage)&&"FREYJA".equals(plan.getOptions().getUpscaleEngine())){
                var component=new LinkedHashMap<String,Object>();
                component.put("version",1);component.put("method",plan.getOptions().getUpscaleMethod());
                if("H3_LEARNED".equals(plan.getOptions().getUpscaleMethod())&&plan.getOptions().getUpscaleLatentModel()!=null
                        &&!plan.getOptions().getUpscaleLatentModel().isBlank())component.put("latent_model",plan.getOptions().getUpscaleLatentModel());
                component.put("steps",plan.getOptions().getUpscaleSteps());component.put("memory",plan.getOptions().getUpscaleMemory());
                if(!"STANDARD".equals(plan.getOptions().getUpscaleSampler()))component.put("sampler",plan.getOptions().getUpscaleSampler());
                component.put("tiles",Map.of("tile_width",tiling.getTileWidth(),"tile_height",tiling.getTileHeight(),"overlap",tiling.getOverlap(),
                        "window_frames",tiling.getWindowFrames(),"overlap_frames",tiling.getOverlapFrames()));
                transport.put("component",component);
            }
            if(("FACE".equals(stage)||!plan.getOptions().isFaceRepair())&&!plan.getOutputSize().equals(request.getSize())){
                var output=plan.getOutputSize().split("x");transport.put("output_width",Integer.parseInt(output[0]));transport.put("output_height",Integer.parseInt(output[1]));
            }
        }
        request.setDynamicWorkflowStage(transport);request.setContinuationSegments(null);request.setContinuationPlan(null);
        if(latent!=null){
            transport.put("source_latent",Map.of("content_sha256",Objects.requireNonNull(latent.get("sha256"))));
            transport.put("source_size",plan.getBaseSize());
        }
        if(saveLatent)transport.put("save_latent",true);
        if(predecessor!=null&&overlap>0)transport.put("latent_context",Map.of("content_sha256",Objects.requireNonNull(predecessor.get("sha256")),"frames",overlap));
        // Stage identity excludes unrelated enhancement settings and expiring source transport URLs.
        var identity=json.convertValue(request,DramaShotRenderRequestDTO.class);
        identity.setTaskId(null);identity.setDynamicWorkflow(null);identity.setDynamicWorkflowPlan(null);
        var shot=snapshot.getShot();
        String reuseTask=plan.getReuseFromTaskId();
        if(reuseTask!=null&&(task.equals(reuseTask)||!Objects.equals(jdbc.queryForObject("SELECT COUNT(*) FROM render_task WHERE task_id=? AND shot_id=?",Integer.class,reuseTask,shot.getId()),1)))throw new BizException(400,"仅可复用当前分镜其他任务的步骤");
        var mediaIdentity=Arrays.asList(shot.getGenerationMode(),shot.getDuration(),shot.getPreviewImageUrl(),shot.getEndFrameImageUrl(),shot.getRefImagesJson(),shot.getRefAudiosJson(),shot.getRefVideosJson(),shot.getNegativePrompt());
        String hash=VideoBatchPreparation.hash(json.writeValueAsString(Arrays.asList(stage,source,identity,mediaIdentity,snapshot.getModelFingerprint(),plan.getNodeId())));
        var rows=jdbc.queryForList("SELECT * FROM dynamic_workflow_step WHERE task_id=? AND step_key=?",task,key);
        String execution=task+"-dw-"+UUID.randomUUID();
        var state=VideoExecutionState.current();boolean child=state!=null&&state.continuationChild;
        var previous=state==null?null:state.upstream;
        if(state!=null){state.continuationChild=true;state.recoveryUrl=null;}
        try {
            VideoExecutionState.stage(stage);
            VideoExecutionState.progress(stage,"BASE".equals(stage)?"基础视频生成":"UPSCALE".equals(stage)?"二采放大":"人脸修复");
            if(!rows.isEmpty()){
                var row=rows.getFirst();
                if(!hash.equals(row.get("input_hash")))throw new BizException(409,"视频处理输入与任务快照不一致");
                if("SUCCESS".equals(row.get("status"))){String url=Objects.toString(row.get("output_url"),"");if(url.isBlank())throw new BizException(500,"工作流成功步骤缺少产物");return new Enhancement(url,saveLatent?secondPassManifest(row.get("result_manifest_json")):null);}
                execution=row.get("execution_id").toString();
                if("ARCHIVING".equals(row.get("status")))return complete(snapshot,task,key,stage,execution,Objects.toString(row.get("output_url"),null),saveLatent);
                if("SUBMITTING".equals(row.get("status"))){
                    String prompt=Objects.toString(row.get("prompt_id"),null),node=Objects.toString(row.get("node_id"),null);
                    var remote=prompt!=null&&node!=null?api.queryVideoTask(request.getProviderId(),execution,prompt,node):api.queryVideoTask(request.getProviderId(),execution);
                    if(remote!=null&&"SUCCESS".equals(remote.status())){
                        if(remote.videoUrl()==null||remote.videoUrl().isBlank())throw new BizException(502,"上游工作流步骤缺少视频地址");
                        String output=api.archiveExistingVideo(snapshot.getShot().getId(),snapshot.getShot().getDramaId(),remote.videoUrl(),snapshot.getRequest().getProviderId()).getVideoUrl();
                        return complete(snapshot,task,key,stage,execution,output,saveLatent);
                    }
                    if(remote==null||!Set.of("FAILED","CANCELLED").contains(remote.status()))throw new BizException(409,"原工作流步骤尚未确认结束，请稍后恢复，避免重复生成");
                }
                execution=task+"-dw-"+UUID.randomUUID();
            } else {
                if(jdbc.update("INSERT INTO dynamic_workflow_step(task_id,step_key,input_hash,execution_id,stage,status,updated_at) VALUES (?,?,?,?,?,'PREPARING',?)",task,key,hash,execution,stage,LocalDateTime.now())!=1)throw new BizException(500,"工作流步骤持久化失败");
                String sourceTask=plan.getReuseFromTaskId();
                if(sourceTask!=null){
                    var reusable=jdbc.queryForList("SELECT output_url,result_manifest_json FROM dynamic_workflow_step WHERE task_id=? AND step_key=? AND input_hash=? AND status='SUCCESS'",sourceTask,key,hash);
                    if(!reusable.isEmpty()&&(!saveLatent||reusable.getFirst().get("result_manifest_json")!=null)){
                        String output=Objects.toString(reusable.getFirst().get("output_url"),null);
                        var saved=saveLatent?secondPassManifest(reusable.getFirst().get("result_manifest_json")):null;
                        if(jdbc.update("UPDATE dynamic_workflow_step SET source_task_id=? WHERE task_id=? AND step_key=?",sourceTask,task,key)!=1)throw new BizException(500,"工作流复用来源保存失败");
                        success(task,key,stage,output,saved);return new Enhancement(output,saved);
                    }
                }
            }
            if(source!=null){
                Path file=Files.createTempFile("freyja-dynamic-source-",".mp4");
                try{media.downloadPrepared(source,file);var asset=media.archiveDynamicSource(file);transport.put("source_url",media.transportUrl(asset.objectPath()));transport.put("source_sha256",asset.sha256());}
                finally{Files.deleteIfExists(file);}
            }
            request.setTaskId(execution);
            if(latent!=null)transport.put("source_latent",artifacts.transport(latent));
            if(predecessor!=null&&overlap>0){
                var context=new LinkedHashMap<String,Object>(artifacts.transport(predecessor));context.put("frames",overlap);transport.put("latent_context",context);
            }
            if(jdbc.update("UPDATE dynamic_workflow_step SET execution_id=?,status='SUBMITTING',prompt_id=NULL,node_id=NULL,error_message=NULL,updated_at=? WHERE task_id=? AND step_key=?",execution,LocalDateTime.now(),task,key)!=1)throw new BizException(500,"工作流执行身份保存失败");
            if(state!=null)state.upstream=(prompt,node)->{if(jdbc.update("UPDATE dynamic_workflow_step SET prompt_id=?,node_id=?,updated_at=? WHERE task_id=? AND step_key=?",prompt,node,LocalDateTime.now(),task,key)!=1)throw new BizException(500,"工作流上游身份保存失败");};
            String output;
            try {
                output=api.generateAndArchiveVideo(snapshot.getShot().getId(),snapshot.getShot().getDramaId(),snapshot.getShot(),request).getVideoUrl();
            } catch(Exception error){
                boolean rejected=state!=null&&Set.of("PREPARATION","REJECTED").contains(state.phase);
                if(jdbc.update("UPDATE dynamic_workflow_step SET status=?,error_message=?,updated_at=? WHERE task_id=? AND step_key=?",rejected?"FAILED":"SUBMITTING",error.getMessage(),LocalDateTime.now(),task,key)!=1)throw new BizException(500,"工作流失败状态保存失败");
                throw error;
            }
            return complete(snapshot,task,key,stage,execution,output,saveLatent);
        } finally { if(state!=null){state.upstream=previous;state.continuationChild=child;state.recoveryUrl=null;} }
    }
    private Enhancement complete(VideoBatchModels.Snapshot snapshot,String task,String key,String stage,String execution,String output,boolean saveLatent)throws Exception {
        if(output==null||output.isBlank())throw new BizException(502,"视频处理步骤未返回视频");
        Map<String,Object> manifest=null;
        if(saveLatent){
            if(jdbc.update("UPDATE dynamic_workflow_step SET status='ARCHIVING',output_url=?,updated_at=? WHERE task_id=? AND step_key=?",output,LocalDateTime.now(),task,key)!=1)throw new BizException(500,"二采 latent 归档阶段保存失败");
            var identity=jdbc.queryForMap("SELECT prompt_id,node_id FROM dynamic_workflow_step WHERE task_id=? AND step_key=?",task,key);
            manifest=artifacts.archive(snapshot.getRequest().getProviderId(),execution,Objects.toString(identity.get("prompt_id"),null),Objects.toString(identity.get("node_id"),null),snapshot.getShot().getDramaId(),snapshot.getShot().getId(),"SECOND_PASS");
            if(manifest==null||!"SECOND_PASS".equals(manifest.get("stage")))throw new BizException(502,"二采 latent 归档结果无效");
        }
        success(task,key,stage,output,manifest);return new Enhancement(output,manifest);
    }
    private Map<String,Object> secondPassManifest(Object value)throws Exception {
        if(value==null)throw new BizException(409,"历史片段缺少二采 latent，请重新生成该二采片段");
        String text=value instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):value.toString();
        var manifest=json.readValue(text,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        if(!"SECOND_PASS".equals(manifest.get("stage")))throw new BizException(409,"二采 latent 阶段不匹配，不能使用一采产物接续二采");
        return manifest;
    }
    private void success(String task,String key,String stage,String url,Map<String,Object> manifest)throws Exception {
        if(url==null||url.isBlank())throw new BizException(502,"视频处理步骤未返回视频");
        if(jdbc.update("UPDATE dynamic_workflow_step SET status='SUCCESS',output_url=?,result_manifest_json=?,error_message=NULL,updated_at=? WHERE task_id=? AND step_key=?",url,manifest==null?null:json.writeValueAsString(manifest),LocalDateTime.now(),task,key)!=1)throw new BizException(500,"工作流产物保存失败");
        events.append(task,"DYNAMIC_WORKFLOW_STAGE_COMPLETE",json.writeValueAsString(Map.of("stepKey",key,"stage",stage,"videoUrl",url)));
    }
}
