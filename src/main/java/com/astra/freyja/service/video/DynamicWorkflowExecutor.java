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

    public VideoGenerationResultVO render(VideoBatchModels.Snapshot snapshot)throws Exception {
        String task=snapshot.getRequest().getTaskId();
        String base=execute(snapshot,task,"single:00:BASE","BASE",null);
        String result=enhance(snapshot,base,task,"single");
        return VideoGenerationResultVO.builder().videoUrl(result).build();
    }
    public String enhance(VideoBatchModels.Snapshot snapshot,String base,String task,String prefix)throws Exception {
        var plan=snapshot.getRequest().getDynamicWorkflowPlan();
        if(plan==null)return base;
        String output=base;
        if(plan.getOptions().isUpscale())output=execute(snapshot,task,prefix+":01:UPSCALE","UPSCALE",output);
        if(plan.getOptions().isFaceRepair())output=execute(snapshot,task,prefix+":02:FACE","FACE",output);
        return output;
    }
    public String activeExecution(String task){
        var rows=jdbc.query("SELECT execution_id FROM dynamic_workflow_step WHERE task_id=? AND status='SUBMITTING' ORDER BY updated_at DESC LIMIT 1",(rs,n)->rs.getString(1),task);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private String execute(VideoBatchModels.Snapshot snapshot,String task,String key,String stage,String source)throws Exception {
        var request=json.convertValue(snapshot.getRequest(),DramaShotRenderRequestDTO.class);
        var plan=request.getDynamicWorkflowPlan();
        var transport=new LinkedHashMap<String,Object>();transport.put("schema_version",1);transport.put("stage",stage);transport.put("revision",plan.getCapabilityRevision());
        if(!"BASE".equals(stage)){
            request.setH3Options(null);request.setSize("UPSCALE".equals(stage)||plan.getOptions().isUpscale()?Objects.requireNonNullElse(plan.getProcessingSize(),plan.getOutputSize()):plan.getBaseSize());
            transport.put("scale","UPSCALE".equals(stage)?2:1);
            transport.put("denoise","UPSCALE".equals(stage)?plan.getOptions().getUpscaleDenoise():plan.getOptions().getFaceDenoise());
            var tiling=plan.getOptions().getUpscaleTiling();
            if("UPSCALE".equals(stage)&&"FREYJA".equals(plan.getOptions().getUpscaleEngine())){
                var component=new LinkedHashMap<String,Object>();
                component.put("version",1);component.put("method",plan.getOptions().getUpscaleMethod());
                if("H3_LEARNED".equals(plan.getOptions().getUpscaleMethod()))component.put("latent_model",plan.getOptions().getUpscaleLatentModel());
                component.put("steps",plan.getOptions().getUpscaleSteps());component.put("memory",plan.getOptions().getUpscaleMemory());
                component.put("tiles",Map.of("tile_width",tiling.getTileWidth(),"tile_height",tiling.getTileHeight(),"overlap",tiling.getOverlap(),
                        "window_frames",tiling.getWindowFrames(),"overlap_frames",tiling.getOverlapFrames()));
                transport.put("component",component);
            }
            if(("FACE".equals(stage)||!plan.getOptions().isFaceRepair())&&!plan.getOutputSize().equals(request.getSize())){
                var output=plan.getOutputSize().split("x");transport.put("output_width",Integer.parseInt(output[0]));transport.put("output_height",Integer.parseInt(output[1]));
            }
        }
        request.setDynamicWorkflowStage(transport);request.setContinuationSegments(null);request.setContinuationPlan(null);
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
            VideoExecutionState.progress(stage,"BASE".equals(stage)?"动态工作流：基础视频生成":"UPSCALE".equals(stage)?"动态工作流：二采放大":"动态工作流：人脸修复");
            if(!rows.isEmpty()){
                var row=rows.getFirst();
                if(!hash.equals(row.get("input_hash")))throw new BizException(409,"动态工作流输入与任务快照不一致");
                if("SUCCESS".equals(row.get("status"))){String url=Objects.toString(row.get("output_url"),"");if(url.isBlank())throw new BizException(500,"工作流成功步骤缺少产物");return url;}
                execution=row.get("execution_id").toString();
                if("SUBMITTING".equals(row.get("status"))){
                    String prompt=Objects.toString(row.get("prompt_id"),null),node=Objects.toString(row.get("node_id"),null);
                    var remote=prompt!=null&&node!=null?api.queryVideoTask(request.getProviderId(),execution,prompt,node):api.queryVideoTask(request.getProviderId(),execution);
                    if(remote!=null&&"SUCCESS".equals(remote.status())){
                        if(remote.videoUrl()==null||remote.videoUrl().isBlank())throw new BizException(502,"上游工作流步骤缺少视频地址");
                        String output=api.archiveExistingVideo(snapshot.getShot().getId(),snapshot.getShot().getDramaId(),remote.videoUrl()).getVideoUrl();
                        success(task,key,stage,output);return output;
                    }
                    if(remote==null||!Set.of("FAILED","CANCELLED").contains(remote.status()))throw new BizException(409,"原工作流步骤尚未确认结束，请稍后恢复，避免重复生成");
                }
                execution=task+"-dw-"+UUID.randomUUID();
            } else {
                if(jdbc.update("INSERT INTO dynamic_workflow_step(task_id,step_key,input_hash,execution_id,stage,status,updated_at) VALUES (?,?,?,?,?,'PREPARING',?)",task,key,hash,execution,stage,LocalDateTime.now())!=1)throw new BizException(500,"工作流步骤持久化失败");
                String sourceTask=plan.getReuseFromTaskId();
                if(sourceTask!=null){
                    var reusable=jdbc.queryForList("SELECT output_url FROM dynamic_workflow_step WHERE task_id=? AND step_key=? AND input_hash=? AND status='SUCCESS'",sourceTask,key,hash);
                    if(!reusable.isEmpty()){
                        String output=Objects.toString(reusable.getFirst().get("output_url"),null);
                        if(jdbc.update("UPDATE dynamic_workflow_step SET source_task_id=? WHERE task_id=? AND step_key=?",sourceTask,task,key)!=1)throw new BizException(500,"工作流复用来源保存失败");
                        success(task,key,stage,output);return output;
                    }
                }
            }
            if(source!=null){
                Path file=Files.createTempFile("freyja-dynamic-source-",".mp4");
                try{media.downloadPrepared(source,file);var asset=media.archiveDynamicSource(file);transport.put("source_url",media.transportUrl(asset.objectPath()));transport.put("source_sha256",asset.sha256());}
                finally{Files.deleteIfExists(file);}
            }
            request.setTaskId(execution);
            if(jdbc.update("UPDATE dynamic_workflow_step SET execution_id=?,status='SUBMITTING',prompt_id=NULL,node_id=NULL,error_message=NULL,updated_at=? WHERE task_id=? AND step_key=?",execution,LocalDateTime.now(),task,key)!=1)throw new BizException(500,"工作流执行身份保存失败");
            if(state!=null)state.upstream=(prompt,node)->{if(jdbc.update("UPDATE dynamic_workflow_step SET prompt_id=?,node_id=?,updated_at=? WHERE task_id=? AND step_key=?",prompt,node,LocalDateTime.now(),task,key)!=1)throw new BizException(500,"工作流上游身份保存失败");};
            try {
                String output=api.generateAndArchiveVideo(snapshot.getShot().getId(),snapshot.getShot().getDramaId(),snapshot.getShot(),request).getVideoUrl();
                success(task,key,stage,output);return output;
            } catch(Exception error){
                boolean rejected=state!=null&&Set.of("PREPARATION","REJECTED").contains(state.phase);
                if(jdbc.update("UPDATE dynamic_workflow_step SET status=?,error_message=?,updated_at=? WHERE task_id=? AND step_key=?",rejected?"FAILED":"SUBMITTING",error.getMessage(),LocalDateTime.now(),task,key)!=1)throw new BizException(500,"工作流失败状态保存失败");
                throw error;
            }
        } finally { if(state!=null){state.upstream=previous;state.continuationChild=child;state.recoveryUrl=null;} }
    }
    private void success(String task,String key,String stage,String url)throws Exception {
        if(url==null||url.isBlank())throw new BizException(502,"动态工作流步骤未返回视频");
        if(jdbc.update("UPDATE dynamic_workflow_step SET status='SUCCESS',output_url=?,error_message=NULL,updated_at=? WHERE task_id=? AND step_key=?",url,LocalDateTime.now(),task,key)!=1)throw new BizException(500,"工作流产物保存失败");
        events.append(task,"DYNAMIC_WORKFLOW_STAGE_COMPLETE",json.writeValueAsString(Map.of("stepKey",key,"stage",stage,"videoUrl",url)));
    }
}
